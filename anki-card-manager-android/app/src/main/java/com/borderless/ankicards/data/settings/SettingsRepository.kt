package com.borderless.ankicards.data.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.borderless.ankicards.data.gemini.GeminiModels
import com.borderless.ankicards.data.images.ImageSearchSettings
import com.borderless.ankicards.data.gemini.GeminiSettings
import com.borderless.ankicards.data.gemini.ProThinkingLevel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

/**
 * Persists user-configurable settings.
 *
 * The app no longer stores a Gemini API key directly — instead it routes all
 * Gemini and TTS calls through the user's Vercel proxy. Settings hold:
 *  - proxyUrl   : base URL of the deployed proxy (e.g. https://x.vercel.app)
 *  - proxyToken : shared bearer token sent on every proxied request
 *  - deckName   : AnkiDroid deck to write to (default "Cantonese")
 *  - modelName  : AnkiDroid note type / model (default "Basic")
 */
class SettingsRepository(private val context: Context) : GeminiSettings, ImageSearchSettings {

    private object Keys {
        val ProxyUrl = stringPreferencesKey("proxy_url")
        val ProxyToken = stringPreferencesKey("proxy_token")
        val DeckName = stringPreferencesKey("deck_name")
        val ModelName = stringPreferencesKey("model_name")
        val ThemeMode = stringPreferencesKey("theme_mode")
        val TextModel = stringPreferencesKey("gemini_text_model")
        val ImageModel = stringPreferencesKey("gemini_image_model")
        val ProThinkingLevel = stringPreferencesKey("gemini_pro_thinking_level")
        val LoaderStyle = stringPreferencesKey("loader_style")
        val ImageSource = stringPreferencesKey("image_source")
    }

    val proxyUrl: Flow<String> = context.dataStore.data.map {
        it[Keys.ProxyUrl].orEmpty()
    }

    val proxyToken: Flow<String> = context.dataStore.data.map {
        it[Keys.ProxyToken].orEmpty()
    }

    val deckName: Flow<String> = context.dataStore.data.map {
        it[Keys.DeckName] ?: "Cantonese"
    }

    val modelName: Flow<String> = context.dataStore.data.map {
        it[Keys.ModelName] ?: "Basic"
    }

    val themeMode: Flow<ThemeMode> = context.dataStore.data.map {
        ThemeMode.fromCode(it[Keys.ThemeMode])
    }

    /** Which card-generation loading animation to show (or Random). */
    val loaderStyle: Flow<LoaderStylePreference> = context.dataStore.data.map {
        LoaderStylePreference.fromCode(it[Keys.LoaderStyle])
    }

    /**
     * Which Gemini text model to use for the structured-output card
     * generation call. Default and full catalog live in [GeminiModels].
     */
    val textModel: Flow<String> = context.dataStore.data.map {
        it[Keys.TextModel] ?: GeminiModels.DEFAULT_TEXT_MODEL
    }

    /**
     * Where card images come from: stock-photo search, generation, or search
     * with generation as the fallback. See [ImageSourcePreference] — the
     * default is search-then-generate, because generation is the slowest and
     * most expensive leg of the pipeline by a wide margin.
     */
    val imageSource: Flow<ImageSourcePreference> = context.dataStore.data.map {
        ImageSourcePreference.fromCode(it[Keys.ImageSource])
    }

    /** Which Gemini image model to use for card image generation. */
    val imageModel: Flow<String> = context.dataStore.data.map {
        it[Keys.ImageModel] ?: GeminiModels.DEFAULT_IMAGE_MODEL
    }

    /**
     * User-selected reasoning depth applied to Gemini 3.x Pro text-model
     * requests. Stored as the enum's `name` (HIGH / MEDIUM / LOW).
     * Honored by [GeminiRepository] only when the active text model is a
     * Pro variant — Flash requests ignore the setting.
     */
    val proThinkingLevel: Flow<ProThinkingLevel> = context.dataStore.data.map {
        ProThinkingLevel.fromCode(it[Keys.ProThinkingLevel])
    }

    suspend fun setProxyUrl(value: String) {
        val normalized = value.trim().trimEnd('/').let { v ->
            // If the user dropped the scheme (or pasted just a hostname), force
            // https. Android blocks cleartext by default, so http would fail anyway.
            when {
                v.isEmpty() -> v
                v.startsWith("http://", ignoreCase = true) -> "https://" + v.removePrefix("http://").removePrefix("HTTP://")
                v.startsWith("https://", ignoreCase = true) -> v
                else -> "https://$v"
            }
        }
        context.dataStore.edit { it[Keys.ProxyUrl] = normalized }
    }

    suspend fun setProxyToken(value: String) {
        context.dataStore.edit { it[Keys.ProxyToken] = value.trim() }
    }

    suspend fun setDeckName(value: String) {
        context.dataStore.edit { it[Keys.DeckName] = value }
    }

    suspend fun setModelName(value: String) {
        context.dataStore.edit { it[Keys.ModelName] = value }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[Keys.ThemeMode] = mode.code }
    }

    suspend fun setLoaderStyle(style: LoaderStylePreference) {
        context.dataStore.edit { it[Keys.LoaderStyle] = style.code }
    }

    suspend fun setTextModel(value: String) {
        context.dataStore.edit { it[Keys.TextModel] = value }
    }

    suspend fun setImageModel(value: String) {
        context.dataStore.edit { it[Keys.ImageModel] = value }
    }

    suspend fun setImageSource(value: ImageSourcePreference) {
        context.dataStore.edit { it[Keys.ImageSource] = value.code }
    }

    suspend fun setProThinkingLevel(level: ProThinkingLevel) {
        context.dataStore.edit { it[Keys.ProThinkingLevel] = level.name }
    }

    // ── GeminiSettings (suspend "give me the current value") ───────────
    // Same data the Flows above expose, but as one-shot suspend getters
    // so GeminiRepository (and tests) can consume them without depending
    // on Flow plumbing or DataStore.

    override suspend fun getProxyUrl(): String = proxyUrl.first()
    override suspend fun getTextModel(): String = textModel.first()
    override suspend fun getImageModel(): String = imageModel.first()
    override suspend fun getProThinkingLevel(): ProThinkingLevel = proThinkingLevel.first()

    // ── ImageSearchSettings ────────────────────────────────────────────
    // `getProxyUrl()` above satisfies both interfaces — same signature.

    override suspend fun getImageSource(): ImageSourcePreference = imageSource.first()
}
