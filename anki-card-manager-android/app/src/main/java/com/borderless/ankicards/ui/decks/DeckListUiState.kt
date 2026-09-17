package com.borderless.ankicards.ui.decks

import com.borderless.ankicards.data.anki.AnkiDroidRepository.DeckInfo
import com.borderless.ankicards.domain.deck.DeckLanguage

/**
 * State for the deck picker screen.
 *
 * @param decks                     All decks AnkiDroid reports, sorted by name.
 * @param selectedDeckName          Currently-active deck (also reflected in
 *                                  [com.borderless.ankicards.data.settings.SettingsRepository.deckName]).
 * @param resolvedLanguages         For each deck name (lowercased), the
 *                                  language inherited from the deck itself or
 *                                  any of its ancestors. Missing entry = orphan
 *                                  (no language anywhere up the chain).
 * @param pendingLanguageForDeck    Full name of the deck the user just tapped
 *                                  (an orphan with no language anywhere up its
 *                                  ancestry) that needs a language picker
 *                                  before selection can complete. Null when no
 *                                  picker is open.
 */
data class DeckListUiState(
    val decks: List<DeckInfo> = emptyList(),
    val selectedDeckName: String = "",
    val resolvedLanguages: Map<String, DeckLanguage> = emptyMap(),
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val isCreating: Boolean = false,
    val createError: String? = null,
    val pendingLanguageForDeck: String? = null
)
