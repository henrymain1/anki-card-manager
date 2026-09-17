package com.borderless.ankicards.data.tts

/**
 * Narrow surface of [TtsRepository] that [com.borderless.ankicards.data.gemini.CardGenerator]
 * depends on. Lets tests inject a fake without needing the real proxy
 * URL / OkHttp client / DataStore settings.
 */
interface TtsClient {
    /**
     * Speak [text] in the language identified by [languageCode] (BCP-47
     * subtag — `yue`, `cmn`, `ja`, `es`, etc.). Returns MP3 bytes.
     */
    suspend fun speak(text: String, languageCode: String?): Result<ByteArray>
}
