package com.borderless.ankicards.ui.generator

import com.borderless.ankicards.data.gemini.GeneratedMedia
import com.borderless.ankicards.data.settings.LoaderStylePreference
import com.borderless.ankicards.domain.deck.DeckLanguage
import com.borderless.ankicards.domain.recipe.NoteType
import com.borderless.ankicards.domain.recipe.Template

/**
 * State for the Phase 2 card generator.
 *
 * The deck-language and card-type pickers up top are independent state from
 * the generation pipeline below them — picking a different card type doesn't
 * clear an already-generated card preview unless the user re-runs Generate.
 */
data class GeneratorUiState(
    val query: String = "",
    val activeDeckName: String = "",
    val activeDeckLanguage: DeckLanguage? = null,

    /** Card types whose language matches the active deck. */
    val availableNoteTypes: List<NoteType> = emptyList(),

    /** Selected card type — the note type Generate will fill in. */
    val selectedNoteTypeId: String? = null,

    /**
     * Which designs (template ids) of the selected note type to produce cards
     * for. The user ticks these on the generator. One word → one note → one
     * card per selected design. Defaults to all designs of the selected type.
     */
    val selectedTemplateIds: Set<String> = emptySet(),

    val isGenerating: Boolean = false,
    val generatedTextByFieldKey: Map<String, String> = emptyMap(),
    val generatedMediaByFieldKey: Map<String, GeneratedMedia> = emptyMap(),
    /** Per-field error messages from the most recent generation. */
    val fieldErrors: Map<String, String> = emptyMap(),
    /** Field currently being regenerated (drives spinner state on that row). */
    val regeneratingFieldKey: String? = null,

    val isSaving: Boolean = false,
    val needsAnkiPermission: Boolean = false,
    val saveResult: SaveResult? = null,

    /** Top-level error (snackbar). Cleared by [onMessageShown]. */
    val errorMessage: String? = null,

    /**
     * Has the VM finished its first read of deck + card types? Stays false
     * until the initial Flow combine emits. The screen uses this to reserve
     * picker height during the first frame, so card types arriving doesn't
     * push the input bar down with a visible jump.
     */
    val isContextLoaded: Boolean = false,

    /**
     * Every deck the user currently has in AnkiDroid, with its resolved
     * language. Drives the dropdown attached to the "Writing to" banner —
     * tap the banner to quick-switch active decks without going to the
     * Decks tab. Refreshed eagerly on VM init and on every dropdown-open
     * tap (handles the case where the user just created a deck elsewhere).
     */
    val availableDecks: List<DeckOption> = emptyList(),

    /** User's chosen loading animation (or Random). Read by the screen to pick
     *  which `CardLoaderStyle` to show while generating. */
    val loaderStyle: LoaderStylePreference = LoaderStylePreference.Random
) {
    val selectedNoteType: NoteType?
        get() = availableNoteTypes.firstOrNull { it.id == selectedNoteTypeId }

    /** The designs of the selected note type that are ticked for generation. */
    val selectedTemplates: List<Template>
        get() = selectedNoteType?.templates?.filter { it.id in selectedTemplateIds }.orEmpty()

    /**
     * Every design (template) the user can pick for this deck, flattened across
     * all compatible note types — the deck's language note type AND any generic
     * note type. This is what the generator's picker shows, matching the
     * Designs tab. A design carries its owning note type so we can preview it.
     *
     * Generation still targets ONE note type at a time (so a word becomes one
     * note with one card per ticked design): ticking a design from a *different*
     * note type switches the target to it (see `onDesignToggled`). That keeps
     * the "one note, several cards" model while making generic designs reachable.
     */
    val availableDesigns: List<DesignOption>
        get() = availableNoteTypes.flatMap { nt -> nt.templates.map { DesignOption(nt, it) } }

    val hasGenerated: Boolean
        get() = generatedTextByFieldKey.isNotEmpty() || generatedMediaByFieldKey.isNotEmpty()
}

/** A design (template) together with the note type it belongs to. */
data class DesignOption(val noteType: NoteType, val template: Template)

/**
 * One deck in the quick-switch dropdown on the generator screen.
 * Languages are resolved via `DeckLanguageRepository.resolveLanguage`, so
 * subdecks reflect their parent's language; orphans (no assignment yet)
 * surface as null and render without a chip.
 */
data class DeckOption(
    val name: String,
    val language: DeckLanguage?
)

sealed class SaveResult {
    data class Created(val noteId: Long, val cardLabel: String) : SaveResult()
    data class Updated(val noteId: Long, val cardLabel: String) : SaveResult()
    data class Failed(val message: String) : SaveResult()
}
