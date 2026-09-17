package com.borderless.ankicards.data.deck

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Row remembering which note type (the user-facing "card type") was most
 * recently used when generating into a given deck. When the generate screen
 * opens for an active deck, this seeds the design selection.
 *
 * Keyed by full deck name (the `::`-joined path AnkiDroid uses). One entry
 * per deck the user has generated into; deletion is rare enough that we don't
 * actively prune.
 *
 * NOTE: the table name (`deck_card_type_choices`) and the `cardTypeId` column
 * are kept verbatim from before the CardType→NoteType rename so the on-disk
 * schema is unchanged and no destructive migration is triggered. The Kotlin
 * field is `noteTypeId`; `@ColumnInfo` pins the old column name.
 *
 * [selectedTemplateIds] is a comma-joined list of the design (template) ids the
 * user last had ticked for this deck's note type — so the generator restores
 * the exact selection instead of re-ticking every design on each launch. Empty
 * string means "no explicit selection stored yet" → default to all designs.
 */
@Entity(tableName = "deck_card_type_choices")
data class DeckNoteTypeChoiceEntity(
    @PrimaryKey val deckName: String,
    @ColumnInfo(name = "cardTypeId") val noteTypeId: String,
    @ColumnInfo(name = "selectedTemplateIds", defaultValue = "") val selectedTemplateIds: String = "",
    val updatedAt: Long
)
