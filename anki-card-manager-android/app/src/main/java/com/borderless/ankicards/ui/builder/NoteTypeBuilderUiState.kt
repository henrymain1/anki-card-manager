package com.borderless.ankicards.ui.builder

import com.borderless.ankicards.domain.recipe.CardFace
import com.borderless.ankicards.domain.recipe.CardField
import com.borderless.ankicards.domain.recipe.FieldPlacement
import com.borderless.ankicards.domain.recipe.Template

/**
 * Edit state for one in-progress card type, consumed by the visual designer.
 *
 * The designer mutates this freely while editing — only [save] runs the full
 * [com.borderless.ankicards.domain.recipe.NoteType] validation. This lets the
 * UI pass through transiently invalid states (e.g. a half-completed rename)
 * without ripping rows out from under the user.
 *
 * @param visibleFace  Which face the designer is currently editing.
 * @param ankiPushStatus  Outcome of the most recent attempt to push this card
 *                        type to AnkiDroid. Null = never tried.
 */
data class NoteTypeBuilderUiState(
    val id: String? = null,
    val name: String = "",
    val language: String? = null,
    val defaultImageStyle: String = "realistic photo",
    val fields: List<CardField> = emptyList(),
    /**
     * Every card design (template) on this note type. The designer edits one at
     * a time ([editingTemplateIndex]); all of them are saved. Always has at
     * least one element. Each shares [fields]; they differ only in placements.
     */
    val templates: List<Template> = listOf(Template()),
    /** Index into [templates] of the design currently being edited. */
    val editingTemplateIndex: Int = 0,
    val visibleFace: CardFace = CardFace.FRONT,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val saveError: String? = null,
    val justSaved: Boolean = false,
    /** When non-null, the per-field settings sheet is open for this field key. */
    val editingFieldKey: String? = null,
    val ankiPushStatus: AnkiPushStatus = AnkiPushStatus.Idle
) {
    /** The design currently being edited (null only if [templates] is empty). */
    val currentTemplate: Template? get() = templates.getOrNull(editingTemplateIndex)

    /** Placements of the design currently being edited. The card surface and
     *  the place/move/resize/remove actions all operate on these. */
    val placements: List<FieldPlacement> get() = currentTemplate?.placements ?: emptyList()
}

sealed class AnkiPushStatus {
    data object Idle : AnkiPushStatus()
    data object InProgress : AnkiPushStatus()
    data class Success(val modelId: Long) : AnkiPushStatus()
    data class Skipped(val reason: String) : AnkiPushStatus()
    data class Failed(val message: String) : AnkiPushStatus()
}
