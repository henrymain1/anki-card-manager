package com.borderless.ankicards.data.gemini

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject

/**
 * Narrow surface of [GeminiRepository] that [CardGenerator] depends on.
 * Exists so tests can supply a fake implementation without standing up
 * Retrofit / OkHttp / the actual proxy.
 *
 * Keep this interface deliberately minimal — only the two methods the
 * orchestrator needs. Everything else (single-field regenerate, image
 * cropping, etc.) lives on [GeminiRepository] itself, not on this
 * interface, because nothing else needs to fake it.
 */
interface GeminiClient {
    /**
     * Structured-output text generation. Returns one string per property
     * in [schema], keyed by the schema property name (a card-type field key).
     */
    suspend fun generateStructured(
        prompt: String,
        schema: JsonObject
    ): Result<Map<String, String>>

    /**
     * Image generation. The implementation already crops to landscape, so
     * the returned bytes are the final PNG that lands on the card.
     */
    suspend fun generateImage(word: String): Result<ByteArray>

    /**
     * Streaming variant of [generateStructured]. Emits the accumulated
     * `(key, value)` map as soon as each top-level string field finishes
     * streaming from Gemini. Caller sees `word` land at ~2s, `pinyin` at
     * ~3s, etc., instead of waiting ~8s for everything at once.
     *
     * Errors are thrown into the Flow; collectors should wrap with
     * `.catch { ... }` if they want partial-completion handling.
     */
    fun streamStructured(prompt: String, schema: JsonObject): Flow<Map<String, String>>
}

/**
 * The slice of settings that [GeminiRepository] needs to build a request.
 * Lives here (not on `SettingsRepository`) so tests can supply a fake
 * without standing up DataStore. SettingsRepository implements this
 * interface — call sites that already had a SettingsRepository in hand
 * keep working unchanged.
 */
interface GeminiSettings {
    suspend fun getProxyUrl(): String
    suspend fun getTextModel(): String
    suspend fun getImageModel(): String

    /**
     * Current Pro thinking level. Defaulted in the interface so existing
     * fakes / tests don't have to override it — production callers go
     * through [SettingsRepository] which provides a persisted value.
     * Only consumed when the selected text model is a Pro variant
     * (see [GeminiModels.isProTextModel]).
     */
    suspend fun getProThinkingLevel(): ProThinkingLevel = ProThinkingLevel.Default
}
