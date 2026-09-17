package com.borderless.ankicards.data.gemini

import android.util.Base64
import com.borderless.ankicards.data.gemini.dto.Content
import com.borderless.ankicards.data.gemini.dto.GenerateContentRequest
import com.borderless.ankicards.data.gemini.dto.GenerateContentResponse
import com.borderless.ankicards.data.gemini.dto.GenerationConfig
import com.borderless.ankicards.data.gemini.dto.ImageConfig
import com.borderless.ankicards.data.gemini.dto.Part
import com.borderless.ankicards.data.gemini.dto.ThinkingConfig
import com.borderless.ankicards.domain.model.Card
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.HttpException

/**
 * Wraps the Gemini API to produce flashcard content.
 *
 * Prompts mirror the desktop project (anki-card-manager/services/gemini.py) exactly so
 * mobile-generated cards are indistinguishable from desktop-generated ones.
 */
class GeminiRepository(
    private val api: GeminiApi,
    /**
     * Raw OkHttp client for the streaming endpoint. Retrofit's coroutine
     * support doesn't cope with SSE, so [streamStructured] bypasses it
     * and reads the response body line-by-line via OkHttp directly. The
     * same `ProxyAuthInterceptor` still attaches the bearer token.
     */
    private val httpClient: OkHttpClient,
    private val settings: GeminiSettings
) : GeminiClient {

    /**
     * One JSON instance for both directions — used for parsing responses
     * and for serializing the streaming request body. `explicitNulls = false`
     * is the safety net we discovered the hard way when Gemini rejected
     * `"personGeneration": null`.
     */
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    /**
     * Phase 2 entry point. Sends one structured-output call: Gemini sees
     * [prompt] plus [schema] as the required response shape and returns a JSON
     * object whose properties match the schema's. Values are returned as a
     * map keyed by the schema's property names (the card type's field keys).
     *
     * Properties absent from the response are omitted from the map — the
     * orchestrator treats those as empty / "field has no AI value yet."
     */
    override suspend fun generateStructured(
        prompt: String,
        schema: kotlinx.serialization.json.JsonObject
    ): Result<Map<String, String>> = runCatching {
        val model = textModel()
        val request = GenerateContentRequest(
            contents = listOf(Content(parts = listOf(Part(text = prompt)))),
            generationConfig = GenerationConfig(
                temperature = 0.4f,
                responseMimeType = "application/json",
                responseSchema = schema,
                thinkingConfig = proThinkingConfigFor(model)
            )
        )

        val response = timedHttp(label = "LLM text · $model") {
            runCatching {
                api.generateContent(url = proxyUrlFor(model), request = request)
            }.getOrElse { throw it.unwrapHttp() }
        }

        val text = response.candidates.firstOrNull()
            ?.content?.parts?.firstOrNull()?.text
            ?: error("Empty response from Gemini")

        val obj = json.parseToJsonElement(text.cleanJsonFences()).jsonObject
        obj.mapValues { (_, v) ->
            // We told Gemini every property is a string. If it returns a
            // non-string value anyway (numbers, nested objects), coerce to
            // its JSON text representation rather than throw. Then normalise
            // away literal `\n` escape sequences the model sometimes pads
            // values with — they'd otherwise render as literal text in
            // AnkiDroid.
            v.jsonPrimitiveOrText().normaliseLlmString()
        }.filterValues { it.isNotBlank() }
    }

    override fun streamStructured(
        prompt: String,
        schema: JsonObject
    ): Flow<Map<String, String>> = flow {
        // Direct OkHttp call to Gemini's streaming endpoint. Retrofit's
        // converter layer eats the response in one go, which defeats the
        // point of streaming. SSE format: each event is a `data: <json>\n`
        // line where `<json>` is a complete `GenerateContentResponse`
        // partial with the next chunk of generated text. We accumulate
        // those text chunks into a single buffer and feed them to the
        // StreamingJsonObjectParser, which surfaces field-by-field maps.
        val model = settings.getTextModel()
        val base = settings.getProxyUrl().trim().trimEnd('/')
        require(base.isNotBlank()) {
            "Proxy URL not set. Open Settings to configure your Vercel proxy."
        }
        val url = "$base/api/v1beta/models/$model:streamGenerateContent?alt=sse"

        val bodyJson = json.encodeToString(
            GenerateContentRequest.serializer(),
            GenerateContentRequest(
                contents = listOf(Content(parts = listOf(Part(text = prompt)))),
                generationConfig = GenerationConfig(
                    temperature = 0.4f,
                    responseMimeType = "application/json",
                    responseSchema = schema,
                    thinkingConfig = proThinkingConfigFor(model)
                )
            )
        )

        val httpRequest = Request.Builder()
            .url(url)
            .post(bodyJson.toRequestBody("application/json".toMediaType()))
            .build()

        val t0 = System.currentTimeMillis()
        android.util.Log.d(AI_LOG_TAG, "▶ LLM stream · $model")
        try {
            httpClient.newCall(httpRequest).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val errBody = runCatching { resp.body?.string().orEmpty() }.getOrDefault("")
                    error("HTTP ${resp.code}: ${errBody.take(500)}")
                }
                val source = resp.body?.source()
                    ?: error("Streaming response had no body")

                val parser = StreamingJsonObjectParser()
                var lastEmittedKeys = emptySet<String>()
                var firstFieldLoggedAt = -1L

                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    if (line.isBlank()) continue
                    if (!line.startsWith("data:")) continue
                    val payload = line.removePrefix("data:").trim()
                    if (payload.isEmpty() || payload == "[DONE]") continue

                    val partial = runCatching {
                        json.decodeFromString(
                            GenerateContentResponse.serializer(),
                            payload
                        )
                    }.getOrNull() ?: continue

                    val text = partial.candidates.firstOrNull()
                        ?.content?.parts?.firstOrNull()?.text
                        ?: continue

                    val accumulated = parser.feed(text)
                    val currentKeys = parser.keysSoFar()
                    if (currentKeys != lastEmittedKeys) {
                        // Log the moment the first field lands — useful
                        // for confirming streaming actually helps.
                        if (firstFieldLoggedAt < 0 && currentKeys.isNotEmpty()) {
                            firstFieldLoggedAt = System.currentTimeMillis() - t0
                            android.util.Log.d(
                                AI_LOG_TAG,
                                "  · LLM stream first field — ${firstFieldLoggedAt}ms · ${currentKeys.first()}"
                            )
                        }
                        lastEmittedKeys = currentKeys
                        emit(accumulated)
                    }
                }
            }
            android.util.Log.d(
                AI_LOG_TAG,
                "✓ LLM stream · $model — ${System.currentTimeMillis() - t0}ms"
            )
        } catch (t: Throwable) {
            android.util.Log.e(
                AI_LOG_TAG,
                "✗ LLM stream · $model — ${System.currentTimeMillis() - t0}ms — ${t.message ?: t::class.simpleName}"
            )
            throw t
        }
    }.flowOn(Dispatchers.IO)

    suspend fun generateCard(word: String): Result<Card> = runCatching {
        val model = textModel()
        val request = GenerateContentRequest(
            contents = listOf(Content(parts = listOf(Part(text = buildCardFieldsPrompt(word))))),
            generationConfig = GenerationConfig(temperature = 0.4f)
        )

        val response = timedHttp(label = "LLM legacy card · $model") {
            runCatching {
                api.generateContent(url = proxyUrlFor(model), request = request)
            }.getOrElse { throw it.unwrapHttp() }
        }

        val text = response.candidates.firstOrNull()
            ?.content?.parts?.firstOrNull()?.text
            ?: error("Empty response from Gemini")

        parseCardJson(word, text)
    }

    /**
     * Free-form Cantonese tutor explanation for the wordlist screen. Returns
     * Markdown (Chinese, jyutping, measure word, 3 example sentences) — the UI
     * just renders it as text. Distinct from [generateCard]: no JSON shape,
     * no card pipeline, just a single text response.
     */
    suspend fun generateExplanation(word: String): Result<String> = runCatching {
        val model = textModel()
        val request = GenerateContentRequest(
            contents = listOf(Content(parts = listOf(Part(text = buildExplanationPrompt(word))))),
            generationConfig = GenerationConfig(temperature = 0.5f)
        )

        val response = timedHttp(label = "LLM explain · $model") {
            runCatching {
                api.generateContent(url = proxyUrlFor(model), request = request)
            }.getOrElse { throw it.unwrapHttp() }
        }

        response.candidates.firstOrNull()
            ?.content?.parts?.firstOrNull()?.text
            ?.trim()
            ?: error("Empty response from Gemini")
    }

    override suspend fun generateImage(word: String): Result<ByteArray> = runCatching {
        // The stored image-model option id may carry a `@<imageSize>`
        // suffix (e.g. `gemini-3.1-flash-image-preview@512`) for the
        // low-res variant. Decode here so the URL gets the bare model id
        // and the size rides on `generationConfig.imageConfig.imageSize`.
        //
        // Field path + value format verified against a live AI Studio
        // request body: `generationConfig.imageConfig.imageSize: "512"`.
        // For the low-res variant we also set `thinkingLevel: "MINIMAL"`
        // — image generation doesn't benefit from deep reasoning, so
        // skipping the thinking budget is a free speedup. Pro and regular
        // Nano Banana 2 keep their default thinking so quality is unchanged.
        // `aspectRatio = "16:9"` asks Gemini to render landscape natively,
        // which lets us skip the on-device decode+crop+resize that used
        // to land here (300-700ms of bitmap work, plus we were *upscaling*
        // the 512×512 low-res output to 1024×545, throwing away the
        // resolution savings).
        val spec = parseImageModelSpec(imageModel())
        val request = GenerateContentRequest(
            contents = listOf(Content(parts = listOf(Part(text = buildImagePrompt(word))))),
            generationConfig = GenerationConfig(
                responseModalities = listOf("TEXT", "IMAGE"),
                imageConfig = ImageConfig(
                    imageSize = spec.resolution,
                    aspectRatio = "16:9"
                ),
                thinkingConfig = spec.resolution?.let { ThinkingConfig(thinkingLevel = "MINIMAL") }
            )
        )

        val sizeTag = spec.resolution?.let { " · $it" } ?: ""
        val response = timedHttp(label = "Image · ${spec.apiModelId}$sizeTag") {
            runCatching {
                api.generateContent(url = proxyUrlFor(spec.apiModelId), request = request)
            }.getOrElse { throw it.unwrapHttp() }
        }

        val parts = response.candidates.firstOrNull()?.content?.parts
        require(!parts.isNullOrEmpty()) {
            "Gemini refused to generate image (likely safety filters)"
        }

        val inline = parts.firstOrNull { it.inlineData != null }?.inlineData
            ?: error("Gemini response contained no image data")

        // Split timing: base64 decode is cheap (~tens of ms) but worth
        // separating from the network leg so a future regression shows up.
        val decodeStart = System.currentTimeMillis()
        val raw = Base64.decode(inline.data, Base64.DEFAULT)
        android.util.Log.d(
            AI_LOG_TAG,
            "  · Image base64 decode — ${System.currentTimeMillis() - decodeStart}ms · ${raw.size}B"
        )
        // Gemini already rendered 16:9 at the requested size — pass the
        // bytes through. If Gemini ever stops honoring `aspectRatio` and
        // starts returning square output again, restore the
        // `cropToLandscape` helper from git history.
        raw
    }

    /** Build the proxy URL for `<base>/api/v1beta/models/<model>:generateContent`. */
    private suspend fun proxyUrlFor(model: String): String =
        composeGeminiProxyUrl(settings.getProxyUrl(), model)

    // `cropToLandscape` was removed: Gemini now returns the right shape
    // directly via `imageConfig.aspectRatio`, so on-device decode + crop +
    // resize (300-700ms of bitmap work, plus an *upscale* of the 512×512
    // low-res output) is dead weight. If a future caller needs to enforce
    // a specific aspect on the device side, restore from git history.

    /** Mirrors desktop `services/gemini.py:generate_card_fields` (no categories variant). */
    private fun buildCardFieldsPrompt(word: String): String =
        "You are a Cantonese language assistant. Given the input '$word' " +
        "(which may be English, Traditional Chinese, or Jyutping), generate the following for a Cantonese flashcard.\n\n" +
        "Return ONLY valid JSON with these exact keys:\n" +
        "{\n" +
        "  \"front\": \"the English word\",\n" +
        "  \"chinese\": \"Traditional Chinese characters for the word\",\n" +
        "  \"jyutping\": \"Jyutping romanization of the Chinese\",\n" +
        "  \"measure_word\": \"the most common Cantonese classifier for this word, in the format <chinese> <jyutping>. Empty string if not applicable.\",\n" +
        "  \"example_english\": \"a natural example sentence in English using this word\",\n" +
        "  \"example_jyutping\": \"Jyutping romanization of the example sentence\",\n" +
        "  \"example_chinese\": \"the example sentence in Traditional Chinese characters\"\n" +
        "}\n\n" +
        "Rules:\n" +
        "- The example sentence must be natural Cantonese, not Mandarin.\n" +
        "- Jyutping must use tone numbers (e.g. sik6, m4).\n" +
        "- measure_word: provide only the single most common Cantonese classifier in the format <chinese> <jyutping> (e.g. \"個 go3\"). No English description. Empty string if the word doesn't take a classifier.\n" +
        "- Output raw JSON only. No markdown, no explanation, no code fences."

    /**
     * Closely mirrors desktop `services/gemini.py:generate_image_for_word` but
     * drops the explicit pixel-resolution instruction (Gemini's image model was
     * literally rendering "1024 × 545" as text onto the image). We crop to the
     * desired landscape ratio client-side after generation instead.
     */
    private fun buildImagePrompt(word: String): String =
        "Create a single, realistic depiction of '$word'. " +
        "Compose the image as a wide landscape (16:9), with the subject centered. " +
        "Show only one clear representation of the concept in one continuous scene. " +
        "Do not create multiple panels, split images, collages, or segmented layouts. " +
        "If the word refers to a physical object, show the object itself in a natural real-world context. " +
        "If it is a body part (e.g., hair), show that body part on a real human. " +
        "If it is an action or verb, show one person performing that action. " +
        "Do not draw icons, symbols, diagrams, or illustrations. " +
        "Do not show printed cards of any kind. " +
        "Do not include any text, writing, numbers, captions, watermarks, or labels anywhere in the image."

    /** Tutor-style prompt for [generateExplanation]. */
    private fun buildExplanationPrompt(word: String): String =
        """
        You are a Cantonese tutor helping me learn to speak. Prefer the spoken/colloquial form when it differs from the written form. The word may be given in English or Cantonese.

        Word: "$word"

        Reply in concise Markdown, in this order:

        **Cantonese:** the characters
        **Jyutping:** the romanization
        **Meaning:** a short English gloss; add the measure word if it's a countable noun
        **Breakdown:** each character/syllable as "字 jyutping — its meaning", then a line for how they combine. Example for 分享 (fan1 hoeng2): 分 fan1 — to divide; 享 hoeng2 — to enjoy; together "to share".
        **Usage:** one or two sentences on nuance or when to use it
        **Related words:** three, each as "中文 (jyutping) — English"
        **Examples:** a numbered list of three sentences; for each give the Chinese, the jyutping, and a brief English translation
        """.trimIndent()

    private fun parseCardJson(originalInput: String, raw: String): Card {
        val cleaned = raw.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```").trim()
        val obj = json.parseToJsonElement(cleaned).jsonObject
        fun field(key: String): String =
            obj[key]?.jsonPrimitive?.content.orEmpty()
        return Card(
            front = field("front").ifBlank { originalInput },
            chinese = field("chinese"),
            jyutping = field("jyutping"),
            measureWord = field("measure_word"),
            exampleEnglish = field("example_english"),
            exampleJyutping = field("example_jyutping"),
            exampleChinese = field("example_chinese")
        )
    }

    /**
     * Retrofit's HttpException stringifies as just "HTTP 400" — the real error
     * (e.g. Gemini's `error.message`) is on the response body. Re-throw with
     * that body inlined so it surfaces in the snackbar.
     */
    private fun Throwable.unwrapHttp(): Throwable {
        if (this !is HttpException) return this
        val raw = response()?.errorBody()?.string().orEmpty()
        val pretty = runCatching {
            json.parseToJsonElement(raw).jsonObject["error"]
                ?.jsonObject?.get("message")?.jsonPrimitive?.content
        }.getOrNull() ?: raw.take(500)
        return RuntimeException("HTTP ${code()}: ${pretty.ifBlank { message() }}", this)
    }

    /**
     * Strip optional markdown code fences from a JSON response. Gemini's
     * structured-output mode returns clean JSON, but the same helper is reused
     * by the legacy `parseCardJson` path which sometimes saw fenced output.
     */
    private fun String.cleanJsonFences(): String =
        trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()

    /**
     * Convert a [JsonElement] response value to its string form. Strings come
     * back unquoted; anything else stringifies to its JSON text.
     */
    private fun kotlinx.serialization.json.JsonElement.jsonPrimitiveOrText(): String =
        (this as? kotlinx.serialization.json.JsonPrimitive)?.content ?: toString()

    /**
     * Clean up the kinds of garbage Gemini sometimes pads field values with:
     *  - literal `\n` / `\r\n` escape sequences (i.e. the two-char strings
     *    `\` `n`, not real newline chars) appearing at the start, end, or
     *    middle of a value
     *  - leading/trailing whitespace
     *  - values that are only whitespace / line breaks (collapse to empty so
     *    the upstream `filterValues { it.isNotBlank() }` drops them)
     */
    private fun String.normaliseLlmString(): String {
        if (isEmpty()) return this
        // First, replace literal backslash-n escape sequences (4-char "\\r\\n",
        // 2-char "\\n") with real line breaks. After that, trim and collapse
        // runs of whitespace-only lines.
        val withRealBreaks = this
            .replace("\\r\\n", "\n")
            .replace("\\n", "\n")
            .replace("\\r", "\n")
        val trimmed = withRealBreaks.trim()
        // Collapse any run of blank lines into a single newline. We keep
        // single newlines inside multi-line values like example sentences.
        return trimmed.replace(Regex("\n\\s*\n+"), "\n")
    }

    /**
     * Resolve the user-selected Gemini text model from settings, falling
     * back to the default if no preference was ever stored. Read fresh per
     * request so a change in Settings takes effect on the next call without
     * rebuilding the repository.
     */
    private suspend fun textModel(): String = settings.getTextModel()

    /** Same for the image model. */
    private suspend fun imageModel(): String = settings.getImageModel()

    /**
     * Build a [ThinkingConfig] for the user's selected Pro thinking level
     * when [modelId] is a Gemini 3.x Pro variant; null otherwise.
     *
     * Why null-on-non-Pro: the Settings UI only exposes the knob for Pro,
     * so for Flash we want to let Gemini use its model-native default
     * thinking rather than silently force the (Pro-targeted) value the
     * user picked while Pro was selected. Returning null leaves the
     * field out of the request body entirely.
     */
    private suspend fun proThinkingConfigFor(modelId: String): ThinkingConfig? {
        if (!GeminiModels.isProTextModel(modelId)) return null
        val level = settings.getProThinkingLevel()
        return ThinkingConfig(thinkingLevel = level.apiValue)
    }

    /**
     * Wrap an HTTP call with start/end logs and a measured duration so
     * `adb logcat -s AnkiCardsAI` shows exactly when each Gemini request
     * fires and how long it takes. Logs the request even when it throws —
     * the user wants to see failed-call timing too. Re-throws unchanged
     * so caller error handling is unaffected.
     */
    private suspend inline fun <T> timedHttp(label: String, block: () -> T): T {
        val t0 = System.currentTimeMillis()
        android.util.Log.d(AI_LOG_TAG, "▶ $label")
        return try {
            val result = block()
            android.util.Log.d(AI_LOG_TAG, "✓ $label — ${System.currentTimeMillis() - t0}ms")
            result
        } catch (t: Throwable) {
            android.util.Log.e(
                AI_LOG_TAG,
                "✗ $label — ${System.currentTimeMillis() - t0}ms — ${t.message ?: t::class.simpleName}"
            )
            throw t
        }
    }

    private companion object {
        /**
         * Single tag for every AI-call log in the app. Filter with
         * `adb logcat -s AnkiCardsAI` to see the pipeline timings cleanly.
         * `CardGenerator` and `TtsRepository` use the same tag.
         */
        const val AI_LOG_TAG = "AnkiCardsAI"
    }
}

/**
 * Pure URL builder. Lives at file scope (not as a `GeminiRepository`
 * method) so it can be unit-tested without instantiating the repository
 * or stubbing settings / OkHttp. Internal so production callers go
 * through [GeminiRepository], not the bare function.
 */
internal fun composeGeminiProxyUrl(baseUrl: String, modelId: String): String {
    val base = baseUrl.trim().trimEnd('/')
    require(base.isNotBlank()) {
        "Proxy URL not set. Open Settings to configure your Vercel proxy."
    }
    require(modelId.isNotBlank()) { "Model id must not be blank." }
    return "$base/api/v1beta/models/$modelId:generateContent"
}
