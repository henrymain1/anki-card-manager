package com.borderless.ankicards.domain.recipe

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * One card design within a [NoteType] — a single front/back layout.
 *
 * Maps onto **Anki's template** (which Anki's own note-type editor confusingly
 * labels a "Card Type"). A [NoteType] (= Anki *note type*) owns N templates;
 * every note of that type produces one card per template that renders a
 * non-empty front. Two templates on one note type is the classic
 * "Basic (optional reversed card)" pattern — e.g. an English-front
 * "production" card and a target-language-front "recognition" card.
 *
 * The field list and CSS live on the parent [NoteType] and are shared by every
 * template; a template only owns its own [placements] (which field shows where,
 * on which face). The same field can appear in multiple templates at different
 * positions.
 *
 * @param id          Stable local identifier. Lets the designer re-target the
 *                    same template across edits and maps to an AnkiDroid
 *                    template ordinal on push.
 * @param name        Display name shown in the designer's variant switcher and
 *                    used as the AnkiDroid template name ("Recognition",
 *                    "Production", "Card 1").
 * @param placements  Fields positioned on this template's two faces. References
 *                    [CardField.key]s on the parent [NoteType].
 */
@Serializable
data class Template(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Card 1",
    val placements: List<FieldPlacement> = emptyList()
) {
    /** Placements on a single face, in their declared order. */
    fun placementsOn(face: CardFace): List<FieldPlacement> =
        placements.filter { it.face == face }
}
