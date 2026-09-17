package com.borderless.ankicards.domain.recipe

import kotlinx.serialization.Serializable

/**
 * One field on a card type — e.g. "Word", "Jyutping", "Example Sentence", "Image".
 *
 * Fields are uniformly typed regardless of whether they came from a built-in
 * preset (see [FieldPresets]) or were authored by the user as a Custom Field.
 * The distinction lives in [description] (which the user can edit either way)
 * and in [FieldGenerator.Llm.promptOverride] for power users who want full
 * control over what gets sent to the LLM.
 *
 * @param key         Stable machine identifier (snake_case). Used in JSON
 *                    schemas sent to the LLM and as the map key in generated
 *                    card data. Must be unique within a [NoteType].
 * @param label       Human-readable name shown in the UI ("Jyutping").
 * @param description Plain-English specification of what this field should
 *                    contain. For LLM fields, this becomes the property
 *                    description in the structured-output JSON schema, so the
 *                    user's wording directly shapes what the model produces.
 * @param generator   Where the field's content comes from.
 * @param required    If true, the card cannot be approved with this field empty.
 */
@Serializable
data class CardField(
    val key: String,
    val label: String,
    val description: String,
    val generator: FieldGenerator,
    val required: Boolean = false
) {
    init {
        require(key.isNotBlank()) { "CardField.key must not be blank" }
        require(key.matches(KEY_PATTERN)) {
            "CardField.key must be snake_case (lowercase letters, digits, underscores): '$key'"
        }
        require(label.isNotBlank()) { "CardField.label must not be blank" }
    }

    companion object {
        private val KEY_PATTERN = Regex("^[a-z][a-z0-9_]*$")
    }
}
