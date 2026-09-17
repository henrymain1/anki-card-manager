package com.borderless.ankicards.domain.recipe

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Which side of the card a [FieldPlacement] lives on.
 *
 * The front is what the user sees first during review; the back appears after
 * they reveal the answer. AnkiDroid renders each face from its own HTML template
 * (`qfmt` and `afmt` columns on the note type), and our visual designer edits
 * one face at a time with a flip button between them.
 */
@Serializable
enum class CardFace {
    @SerialName("front") FRONT,
    @SerialName("back") BACK
}
