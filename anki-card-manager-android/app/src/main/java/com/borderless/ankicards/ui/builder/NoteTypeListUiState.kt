package com.borderless.ankicards.ui.builder

import com.borderless.ankicards.domain.deck.DeckLanguage
import com.borderless.ankicards.domain.recipe.NoteType
import com.borderless.ankicards.domain.recipe.Template

/**
 * State for the Designs tab.
 *
 * @param noteTypes                    All saved card types.
 * @param activeDeckName               The current "writes go here" deck.
 * @param activeDeckLanguage           Resolved language for [activeDeckName],
 *                                     or null if the active deck is an
 *                                     orphan (no assignment yet).
 * @param deckLanguages                Every known deck's resolved language.
 *                                     Used to drive the compatibility map.
 * @param compatibleDeckNamesByNoteType  For each card type id, the list of
 *                                     decks it can be used in (its language
 *                                     matches, or it's generic).
 * @param showAll                      When false (the default), the list shows
 *                                     only card types compatible with the
 *                                     active deck. Flip on to see every card
 *                                     type regardless of language.
 */
data class NoteTypeListUiState(
    val noteTypes: List<NoteType> = emptyList(),
    /**
     * The user-facing unit: every card design (template) across all note types,
     * flattened. This is what the Designs list shows as tiles. The note type a
     * design belongs to is hidden plumbing carried in [DesignRow.noteTypeId].
     */
    val designs: List<DesignRow> = emptyList(),
    /**
     * The note type id for the active deck's language, so "New card type" can
     * add a design to it. Null if no note type exists for that language yet.
     */
    val activeLanguageNoteTypeId: String? = null,
    val activeDeckName: String = "",
    val activeDeckLanguage: DeckLanguage? = null,
    val deckLanguages: Map<String, DeckLanguage> = emptyMap(),
    val compatibleDeckNamesByNoteType: Map<String, List<String>> = emptyMap(),
    val showAll: Boolean = false,
    /**
     * Set when the user taps the delete icon. Drives the confirm dialog.
     * Carries the note count so the dialog can quote it. Null = no dialog.
     */
    val pendingDelete: PendingDelete? = null,
    /** One-shot message surfaced as a snackbar after a delete completes. */
    val transientMessage: String? = null
)

/**
 * One card design (template) presented as a top-level "card type" in the UI.
 *
 * @param noteTypeId      The note type this design lives on (hidden plumbing).
 * @param template        The design itself (name + placements).
 * @param noteType        The owning note type — needed to render the preview
 *                        (shared fields) and to resolve compatibility.
 * @param compatibleDecks Decks this design can be used in (by language).
 */
data class DesignRow(
    val noteTypeId: String,
    val template: Template,
    val noteType: NoteType,
    val compatibleDecks: List<String>
)

/**
 * State for the delete-confirm dialog. Built once the note count has been
 * resolved from AnkiDroid (we don't want to show "0 notes" while still
 * loading, so the dialog waits for the count to come back before opening).
 */
data class PendingDelete(
    val noteTypeId: String,
    val noteTypeName: String,
    val ankiModelId: Long?,
    /** Notes attached to this card type's AnkiDroid model. Null = unknown
     *  (no model yet, never pushed) or count query failed. */
    val ankiNoteCount: Int?,
    val deleting: Boolean = false
)
