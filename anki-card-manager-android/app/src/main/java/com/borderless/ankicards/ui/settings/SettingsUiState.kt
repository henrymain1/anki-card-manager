package com.borderless.ankicards.ui.settings

import com.borderless.ankicards.data.gemini.GeminiModels
import com.borderless.ankicards.data.gemini.ProThinkingLevel
import com.borderless.ankicards.data.settings.ImageSourcePreference
import com.borderless.ankicards.data.settings.LoaderStylePreference
import com.borderless.ankicards.data.settings.ThemeMode

data class SettingsUiState(
    val proxyUrl: String = "",
    val proxyToken: String = "",
    val deckName: String = "",
    val modelName: String = "",
    val themeMode: ThemeMode = ThemeMode.System,
    /** Which card-loading animation to show while generating (or Random). */
    val loaderStyle: LoaderStylePreference = LoaderStylePreference.Random,
    /** Currently-selected Gemini text model id (matches a [GeminiModels.textOptions] entry). */
    val textModel: String = GeminiModels.DEFAULT_TEXT_MODEL,
    /** Currently-selected Gemini image model id (matches a [GeminiModels.imageOptions] entry). */
    val imageModel: String = GeminiModels.DEFAULT_IMAGE_MODEL,
    val imageSource: ImageSourcePreference = ImageSourcePreference.SearchThenGenerate,
    /**
     * Reasoning depth for Gemini 3.x Pro text-model calls. Only surfaced in
     * the UI (and only applied on the request) when the selected text model
     * is a Pro variant — see [GeminiModels.isProTextModel].
     */
    val proThinkingLevel: ProThinkingLevel = ProThinkingLevel.Default,
    val isSaving: Boolean = false,
    val justSaved: Boolean = false
)
