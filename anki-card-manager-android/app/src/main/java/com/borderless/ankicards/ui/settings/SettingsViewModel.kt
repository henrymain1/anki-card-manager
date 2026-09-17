package com.borderless.ankicards.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.borderless.ankicards.data.gemini.ProThinkingLevel
import com.borderless.ankicards.data.settings.ImageSourcePreference
import com.borderless.ankicards.data.settings.LoaderStylePreference
import com.borderless.ankicards.data.settings.SettingsRepository
import com.borderless.ankicards.data.settings.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val repo: SettingsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    proxyUrl = repo.proxyUrl.first(),
                    proxyToken = repo.proxyToken.first(),
                    deckName = repo.deckName.first(),
                    modelName = repo.modelName.first(),
                    themeMode = repo.themeMode.first(),
                    loaderStyle = repo.loaderStyle.first(),
                    textModel = repo.textModel.first(),
                    imageModel = repo.imageModel.first(),
                    imageSource = repo.imageSource.first(),
                    proThinkingLevel = repo.proThinkingLevel.first()
                )
            }
        }
    }

    fun onProxyUrlChanged(value: String) = _uiState.update { it.copy(proxyUrl = value) }
    fun onProxyTokenChanged(value: String) = _uiState.update { it.copy(proxyToken = value) }
    fun onDeckNameChanged(value: String) = _uiState.update { it.copy(deckName = value) }
    fun onModelNameChanged(value: String) = _uiState.update { it.copy(modelName = value) }

    /**
     * Model picks write through immediately (same pattern as theme) — the
     * dropdown is a direct choice, no "Save" needed for it. Tracking it in
     * state too so the dropdown's selected row updates without waiting for
     * the next Flow emission.
     */
    fun onTextModelChanged(id: String) {
        _uiState.update { it.copy(textModel = id) }
        viewModelScope.launch { repo.setTextModel(id) }
    }

    fun onImageSourceChanged(source: ImageSourcePreference) {
        _uiState.update { it.copy(imageSource = source) }
        viewModelScope.launch { repo.setImageSource(source) }
    }

    fun onImageModelChanged(id: String) {
        _uiState.update { it.copy(imageModel = id) }
        viewModelScope.launch { repo.setImageModel(id) }
    }

    /**
     * Pro thinking level changes apply immediately, same pattern as the
     * model dropdowns. The next streaming/structured call reads the new
     * value from settings.
     */
    fun onProThinkingLevelChanged(level: ProThinkingLevel) {
        _uiState.update { it.copy(proThinkingLevel = level) }
        viewModelScope.launch { repo.setProThinkingLevel(level) }
    }

    /**
     * Theme changes apply immediately (write-through to the repository) so the
     * user sees the effect without having to tap Save. The Save button still
     * commits the other text fields.
     */
    fun onThemeModeChanged(mode: ThemeMode) {
        _uiState.update { it.copy(themeMode = mode) }
        viewModelScope.launch { repo.setThemeMode(mode) }
    }

    /** Loader-animation choice. Write-through, same pattern as theme. The
     *  generator reads it on the next generation. */
    fun onLoaderStyleChanged(style: LoaderStylePreference) {
        _uiState.update { it.copy(loaderStyle = style) }
        viewModelScope.launch { repo.setLoaderStyle(style) }
    }

    fun onSaveClicked() {
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, justSaved = false) }
            val s = _uiState.value
            repo.setProxyUrl(s.proxyUrl)
            repo.setProxyToken(s.proxyToken)
            repo.setDeckName(s.deckName)
            repo.setModelName(s.modelName)
            _uiState.update { it.copy(isSaving = false, justSaved = true) }
        }
    }

    companion object {
        fun factory(repo: SettingsRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    SettingsViewModel(repo) as T
            }
    }
}
