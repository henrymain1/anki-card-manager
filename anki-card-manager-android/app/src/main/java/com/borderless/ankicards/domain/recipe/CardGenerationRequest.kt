package com.borderless.ankicards.domain.recipe

import com.borderless.ankicards.domain.deck.DeckLanguage

/**
 * Everything the generation pipeline needs for one card.
 *
 * The four pieces map onto the four big upstream decisions:
 *  - [query]      what the user typed in (the search term, possibly with
 *                 disambiguation hints — e.g. `"evening je6maan5"`).
 *  - [noteType]   which design / field list / generator config to fill in.
 *  - [deckName]   where the resulting card lands in AnkiDroid.
 *  - [language]   the deck's locked language. Threaded into the LLM prompt
 *                 and used to route dictionaries (Phase 3) and TTS voices.
 *
 * Pure data — no Android, no coroutines. Constructed by the generator
 * view model, consumed by [com.borderless.ankicards.data.gemini.CardGenerator].
 */
data class CardGenerationRequest(
    val query: String,
    val noteType: NoteType,
    val deckName: String,
    val language: DeckLanguage
)
