package com.borderless.ankicards.data.tts

import com.borderless.ankicards.data.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Cantonese text-to-speech via the user's Vercel proxy.
 *
 * The proxy mirrors the desktop project's `services/tts.py` — same Google
 * Translate `translate_tts` endpoint with `client=tw-ob`. Going through the
 * proxy means the phone doesn't need a VPN if Google's TTS endpoint is
 * blocked/throttled on its network, and it removes one more direct dependency
 * on Google services from the client.
 *
 * The bearer token is added by `ProxyAuthInterceptor` on the shared OkHttpClient.
 */
class TtsRepository(
    private val httpClient: OkHttpClient,
    private val settings: SettingsRepository
) : TtsClient {

    /**
     * Speak [text] in the language identified by [languageCode] (BCP-47-ish
     * subtag, e.g. `yue`, `cmn`, `ja`, `es`). Returns the MP3 bytes that
     * AnkiDroid will store in its media collection.
     *
     * Falls back to Cantonese (the desktop default) on a null/blank language
     * so calls from the legacy pipeline keep working during the Phase 2
     * transition.
     */
    override suspend fun speak(text: String, languageCode: String?): Result<ByteArray> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(text.isNotBlank()) { "TTS text is blank" }

                val base = settings.proxyUrl.first().trim().trimEnd('/')
                require(base.isNotBlank()) {
                    "Proxy URL not set. Open Settings to configure your Vercel proxy."
                }

                val tl = languageCode?.takeIf { it.isNotBlank() } ?: "yue"
                val trimmed = text.trim().take(MAX_CHARS)

                val url = "$base/api/tts".toHttpUrl()
                    .newBuilder()
                    .addQueryParameter("tl", tl)
                    .addQueryParameter("q", trimmed)
                    .build()

                val request = Request.Builder().url(url).build()

                // Timing logs match the format used by GeminiRepository so
                // `adb logcat -s AnkiCardsAI` shows TTS + LLM + image
                // interleaved. Length-only on the text (no privacy issue
                // and no log noise from long sentences).
                val t0 = System.currentTimeMillis()
                val label = "TTS · $tl · ${trimmed.length} chars"
                android.util.Log.d(AI_LOG_TAG, "▶ $label")
                try {
                    val bytes = httpClient.newCall(request).execute().use { resp ->
                        if (!resp.isSuccessful) error("TTS request failed: HTTP ${resp.code}")
                        val body = resp.body ?: error("TTS response had no body")
                        val responseBytes = body.bytes()
                        if (responseBytes.isEmpty()) error("TTS response was empty")
                        responseBytes
                    }
                    android.util.Log.d(
                        AI_LOG_TAG,
                        "✓ $label — ${System.currentTimeMillis() - t0}ms · ${bytes.size}B"
                    )
                    bytes
                } catch (t: Throwable) {
                    android.util.Log.e(
                        AI_LOG_TAG,
                        "✗ $label — ${System.currentTimeMillis() - t0}ms — ${t.message ?: t::class.simpleName}"
                    )
                    throw t
                }
            }
        }

    /**
     * Backwards-compatible Cantonese shortcut for the legacy generator path.
     * Removed once Phase 2 retires the legacy pipeline.
     */
    suspend fun speakCantonese(text: String): Result<ByteArray> = speak(text, "yue")

    companion object {
        private const val MAX_CHARS = 200

        /** Shared with `GeminiRepository` so all AI logs are one filterable tag. */
        private const val AI_LOG_TAG = "AnkiCardsAI"
    }
}
