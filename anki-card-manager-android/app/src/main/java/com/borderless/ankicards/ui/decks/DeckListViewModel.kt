package com.borderless.ankicards.ui.decks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.borderless.ankicards.data.anki.AnkiDroidRepository
import com.borderless.ankicards.data.deck.DeckLanguageRepository
import com.borderless.ankicards.data.settings.SettingsRepository
import com.borderless.ankicards.domain.deck.DeckLanguage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Drives the deck picker.
 *
 * Reads decks from AnkiDroid, reads the currently-selected deck name from
 * settings, and resolves each deck to its locked language (with subdeck
 * inheritance). When the user taps a deck without a resolved language, the
 * picker dialog opens — selection is gated on a language being assigned. The
 * same flow runs after creating a new deck, so every deck this app touches
 * ends up with a language.
 */
class DeckListViewModel(
    private val anki: AnkiDroidRepository,
    private val settings: SettingsRepository,
    private val deckLanguages: DeckLanguageRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(DeckListUiState())
    val uiState: StateFlow<DeckListUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val current = settings.deckName.first()
            anki.listDecks().fold(
                onSuccess = { decks ->
                    val sorted = decks.sortedBy { d -> d.name.lowercase() }
                    val resolved = resolveLanguages(sorted.map { it.name })
                    _uiState.update {
                        it.copy(
                            decks = sorted,
                            selectedDeckName = current,
                            resolvedLanguages = resolved,
                            isLoading = false,
                            errorMessage = null
                        )
                    }
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(isLoading = false, errorMessage = err.message ?: "Failed to load decks.")
                    }
                }
            )
        }
    }

    /**
     * Try to select a deck. If the deck has a resolved language (either its
     * own or an ancestor's), selection completes immediately. Otherwise the
     * language picker opens — call [confirmLanguageAndSelect] once the user
     * picks.
     */
    fun selectDeck(name: String) {
        viewModelScope.launch {
            val resolved = deckLanguages.resolveLanguage(name)
            if (resolved != null) {
                settings.setDeckName(name)
                _uiState.update { it.copy(selectedDeckName = name) }
            } else {
                _uiState.update { it.copy(pendingLanguageForDeck = name) }
            }
        }
    }

    /**
     * Commit a language for the deck currently in [DeckListUiState.pendingLanguageForDeck],
     * then proceed with selecting it.
     */
    fun confirmLanguageAndSelect(language: DeckLanguage) {
        val pending = _uiState.value.pendingLanguageForDeck ?: return
        viewModelScope.launch {
            val deckId = anki.listDecks().getOrNull()
                ?.firstOrNull { it.name.equals(pending, ignoreCase = true) }
                ?.id
            // Assignment happens against the root of the path, not the
            // possibly-deep subdeck the user tapped. That way every sibling
            // under the same root inherits the language too.
            val root = pending.substringBefore("::")
            deckLanguages.assignLanguage(
                deckName = root,
                language = language,
                ankiDeckId = deckId
            )
            settings.setDeckName(pending)
            _uiState.update {
                it.copy(
                    selectedDeckName = pending,
                    pendingLanguageForDeck = null,
                    resolvedLanguages = it.resolvedLanguages + computeNewInheritedEntries(
                        it.decks.map { d -> d.name }, root, language
                    )
                )
            }
        }
    }

    fun dismissLanguagePicker() {
        _uiState.update { it.copy(pendingLanguageForDeck = null) }
    }

    /**
     * Create a deck in AnkiDroid and, in the same step, lock in its language.
     *
     * If the new deck is a subdeck of an already-assigned root (e.g. user
     * creates `cantonese::TEST` while `cantonese` is already yue), the picked
     * language is effectively ignored — the root assignment wins via
     * inheritance. We still call [DeckLanguageRepository.assignLanguage]
     * defensively; it's idempotent and will no-op in that case.
     */
    fun createDeck(name: String, language: DeckLanguage) {
        viewModelScope.launch {
            _uiState.update { it.copy(isCreating = true, createError = null) }
            anki.createDeck(name).fold(
                onSuccess = {
                    val trimmed = name.trim()
                    val root = trimmed.substringBefore("::")
                    val deckId = anki.listDecks().getOrNull()
                        ?.firstOrNull { it.name.equals(trimmed, ignoreCase = true) }
                        ?.id
                    deckLanguages.assignLanguage(
                        deckName = root,
                        language = language,
                        ankiDeckId = deckId
                    )
                    settings.setDeckName(trimmed)
                    _uiState.update { it.copy(isCreating = false, createError = null) }
                    refresh()
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(isCreating = false, createError = err.message ?: "Failed to create deck.")
                    }
                }
            )
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private suspend fun resolveLanguages(deckNames: List<String>): Map<String, DeckLanguage> {
        val out = HashMap<String, DeckLanguage>()
        deckNames.forEach { name ->
            val lang = deckLanguages.resolveLanguage(name)
            if (lang != null) out[name.lowercase()] = lang
        }
        return out
    }

    /**
     * After assigning [language] to [rootName], every other deck whose name
     * starts with `rootName::` (or equals it) should now also resolve to
     * that language. We compute those entries locally to avoid a round-trip
     * through the repository for each one.
     */
    private fun computeNewInheritedEntries(
        allDeckNames: List<String>,
        rootName: String,
        language: DeckLanguage
    ): Map<String, DeckLanguage> {
        val prefixWithSep = "$rootName::"
        return allDeckNames.asSequence()
            .filter { it.equals(rootName, ignoreCase = true) ||
                it.startsWith(prefixWithSep, ignoreCase = true) }
            .associate { it.lowercase() to language }
    }

    companion object {
        fun factory(
            anki: AnkiDroidRepository,
            settings: SettingsRepository,
            deckLanguages: DeckLanguageRepository
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    DeckListViewModel(anki, settings, deckLanguages) as T
            }
    }
}
