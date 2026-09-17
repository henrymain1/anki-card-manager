package com.borderless.ankicards.domain.recipe

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * The user-designed schema for one kind of Anki note — what fields it has,
 * how each one gets its content, and what language it's tuned for.
 *
 * A [NoteType] is created in the card builder UI (drag-and-drop palette,
 * Phase 1 of the project plan), persisted locally, and consumed by the card
 * editor when generating or manually building cards.
 *
 * Maps onto Anki's "note type" / "model" concept. AnkiDroid owns the actual
 * note-type definition (field list, templates, CSS); this class owns the
 * extra AI-generation metadata Anki itself does not know about.
 *
 * Generation flow note: the *user's query* and the *Word field's value* are
 * distinct concepts. The query is what the user types into the search bar
 * (which may include disambiguation hints like "evening je6maan5"); the Word
 * field holds the canonical resolved form. The query is consumed by the LLM
 * call as context; the resolved Word feeds the dictionary lookups for other
 * fields. Card types must therefore include a Word field (or equivalent) so
 * the resolved value has somewhere to live.
 *
 * @param id              Stable identifier we generate locally. Used to
 *                        cross-reference recipes from cards we created.
 * @param name            Display name ("Cantonese Vocabulary").
 * @param language        BCP-47-ish tag used to pick default dictionaries and
 *                        TTS voice. "yue" for Cantonese, "cmn" for Mandarin,
 *                        "ja" for Japanese, etc. Null = language-agnostic.
 * @param defaultImageStyle  Image generation style applied to every
 *                           [FieldGenerator.ImageGen] field on this card type
 *                           that does not override it. See PROJECT_PLAN.md
 *                           for the goal of user-customizable styles.
 * @param fields          Ordered list of fields on this card. Order matters
 *                        because it determines display order in the editor
 *                        and the default front/back split for Anki templates.
 *                        Field keys must be unique. Shared by every template.
 * @param templates       The card designs this note type produces. One note of
 *                        this type yields one card per template (Anki's
 *                        N-templates-per-note-type model). Defaults to a single
 *                        blank template. Every template shares [fields] and the
 *                        exporter's CSS; they differ only in their placements.
 */
@Serializable
data class NoteType(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val language: String? = null,
    val defaultImageStyle: String = "realistic photo",
    val fields: List<CardField>,
    val templates: List<Template> = listOf(Template())
) {
    init {
        require(name.isNotBlank()) { "NoteType.name must not be blank" }
        require(fields.isNotEmpty()) { "NoteType.fields must not be empty" }
        val duplicateKeys = fields.groupingBy { it.key }.eachCount().filter { it.value > 1 }
        require(duplicateKeys.isEmpty()) {
            "NoteType.fields contains duplicate keys: ${duplicateKeys.keys}"
        }
        // Cross-field references (TTS source, Dictionary lookup key) must point at
        // fields that actually exist on this card type. Otherwise generation will
        // silently produce no audio or look up the wrong value.
        val keys = fields.map { it.key }.toSet()
        fields.forEach { field ->
            when (val gen = field.generator) {
                is FieldGenerator.Tts -> require(gen.sourceFieldKey in keys) {
                    "Field '${field.key}' has TTS source '${gen.sourceFieldKey}' " +
                        "which does not exist on this card type"
                }
                is FieldGenerator.Dictionary -> require(gen.lookupFieldKey in keys) {
                    "Field '${field.key}' has Dictionary lookup key '${gen.lookupFieldKey}' " +
                        "which does not exist on this card type"
                }
                else -> { /* no cross-field refs to validate */ }
            }
        }
        // Within each template: placements must reference real fields and must
        // not overlap within a single face. Overlap is per-template-per-face —
        // two templates can place the same field at the same spot independently.
        templates.forEach { template ->
            template.placements.forEach { p ->
                require(p.fieldKey in keys) {
                    "Template '${template.name}' placement references unknown field '${p.fieldKey}'"
                }
            }
            CardFace.values().forEach { face ->
                val onFace = template.placementsOn(face)
                for (i in onFace.indices) {
                    for (j in i + 1 until onFace.size) {
                        require(!onFace[i].layout.overlaps(onFace[j].layout)) {
                            "Placements for '${onFace[i].fieldKey}' and '${onFace[j].fieldKey}' " +
                                "overlap on the ${face.name.lowercase()} face of template " +
                                "'${template.name}'"
                        }
                    }
                }
            }
        }
    }

    /** Convenience: the field with the given key, or null if absent. */
    fun field(key: String): CardField? = fields.firstOrNull { it.key == key }

    /** The primary (first) template — the one single-card previews and the
     *  single-template export path render. Null only if [templates] is empty. */
    val primaryTemplate: Template? get() = templates.firstOrNull()

    /**
     * Every placement across every template, de-duplicated by field key is NOT
     * applied here — callers that want "is this field used by any card" should
     * map to [FieldPlacement.fieldKey] and `toSet()`. Used by the generation
     * pipeline to decide which fields to fill (a field placed on *any* template
     * needs generating).
     */
    fun allPlacements(): List<FieldPlacement> = templates.flatMap { it.placements }

    /** Placements on a single face of the [primaryTemplate], in declared order.
     *  Single-card preview / single-template export convenience. */
    fun placementsOn(face: CardFace): List<FieldPlacement> =
        primaryTemplate?.placementsOn(face) ?: emptyList()
}
