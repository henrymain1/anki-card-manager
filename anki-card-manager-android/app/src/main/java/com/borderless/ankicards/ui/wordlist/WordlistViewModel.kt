package com.borderless.ankicards.ui.wordlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.borderless.ankicards.data.gemini.GeminiRepository
import com.borderless.ankicards.data.wordlist.WordlistRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class WordlistUiState(
    val input: String = "",
    val explainingIds: Set<String> = emptySet(),
    /**
     * Which rows show their explanation expanded. Authoritative here (not in the
     * row composable) so it survives LazyColumn recomposition/disposal — a
     * row-local flag got force-re-expanded whenever the list changed (e.g. a
     * different word's explanation landing).
     */
    val expandedIds: Set<String> = emptySet(),
    val errorMessage: String? = null
)

class WordlistViewModel(
    private val wordlist: WordlistRepository,
    private val gemini: GeminiRepository
) : ViewModel() {

    val items: StateFlow<List<com.borderless.ankicards.data.wordlist.WordlistItem>> =
        wordlist.items

    private val _uiState = MutableStateFlow(WordlistUiState())
    val uiState: StateFlow<WordlistUiState> = _uiState.asStateFlow()

    fun onInputChanged(value: String) {
        _uiState.value = _uiState.value.copy(input = value)
    }

    fun onAddClicked() {
        val word = _uiState.value.input.trim()
        if (word.isEmpty()) return
        viewModelScope.launch {
            wordlist.add(word)
            _uiState.value = _uiState.value.copy(input = "")
        }
    }

    fun onRemoveClicked(id: String) {
        viewModelScope.launch { wordlist.remove(id) }
        _uiState.value = _uiState.value.copy(
            expandedIds = _uiState.value.expandedIds - id
        )
    }

    /** Toggle whether a row's explanation is expanded. */
    fun onToggleExpanded(id: String) {
        val current = _uiState.value.expandedIds
        _uiState.value = _uiState.value.copy(
            expandedIds = if (id in current) current - id else current + id
        )
    }

    fun onExplainClicked(id: String, word: String) {
        if (id in _uiState.value.explainingIds) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                explainingIds = _uiState.value.explainingIds + id
            )
            gemini.generateExplanation(word)
                .onSuccess { text ->
                    wordlist.setExplanation(id, text)
                    // Auto-expand ONLY this row — the one just generated.
                    _uiState.value = _uiState.value.copy(
                        expandedIds = _uiState.value.expandedIds + id
                    )
                }
                .onFailure { err ->
                    _uiState.value = _uiState.value.copy(
                        errorMessage = "Explanation failed: ${err.message ?: "unknown"}"
                    )
                }
            _uiState.value = _uiState.value.copy(
                explainingIds = _uiState.value.explainingIds - id
            )
        }
    }

    fun onMessageShown() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    companion object {
        fun factory(
            wordlist: WordlistRepository,
            gemini: GeminiRepository
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    WordlistViewModel(wordlist, gemini) as T
            }
    }
}
