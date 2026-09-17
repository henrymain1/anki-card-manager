package com.borderless.ankicards.data.recipe

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room row for one [com.borderless.ankicards.domain.recipe.NoteType].
 *
 * The field list and template list are stored as JSON blobs (via
 * `RecipeJson`) because both are polymorphic / nested and we never query
 * *into* them. If that ever changes, shred them into child tables.
 *
 * [templatesJson] holds the list of [com.borderless.ankicards.domain.recipe.Template]s
 * (each with its own placements). This replaced the old single `placementsJson`
 * column when the data model grew from one-template-per-type to N (see the DB
 * version bump in [AnkiCardsDatabase]). The change is handled by destructive
 * migration — there is no production data yet, and the seed reinstalls fresh
 * two-template presets.
 *
 * [ankiModelId] is the AnkiDroid note-type id this note type is bound to
 * after its first push to AnkiDroid. Subsequent saves update that note type
 * in place instead of creating a duplicate. Null until first push.
 *
 * The table name (`card_types`) is kept verbatim from before the
 * CardType→NoteType rename so the on-disk schema is unchanged and the rename
 * triggers no destructive migration. Renaming the table would be a separate,
 * migration-bearing change.
 */
@Entity(tableName = "card_types")
data class NoteTypeEntity(
    @PrimaryKey val id: String,
    val name: String,
    val language: String?,
    val defaultImageStyle: String,
    val fieldsJson: String,
    val templatesJson: String,
    val ankiModelId: Long?,
    /** Wall-clock ms of last write. Useful later for sync conflict resolution. */
    val updatedAt: Long
)
