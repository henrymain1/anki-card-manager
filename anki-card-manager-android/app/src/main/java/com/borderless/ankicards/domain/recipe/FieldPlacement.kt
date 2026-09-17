package com.borderless.ankicards.domain.recipe

import kotlinx.serialization.Serializable

/**
 * One field positioned on one face of a card.
 *
 * A placement is the visual-designer atom: it ties a [CardField] (identified by
 * [fieldKey]) to a specific spot ([layout]) on a specific face ([face]). The
 * same field can appear on both faces by having two placements — one per face
 * — though in practice most fields show only on one side.
 *
 * @param fieldKey  References a [CardField.key] on the parent [NoteType].
 * @param face      Which face this placement renders on.
 * @param layout    Position and size on the grid.
 */
@Serializable
data class FieldPlacement(
    val fieldKey: String,
    val face: CardFace,
    val layout: FieldLayout
)
