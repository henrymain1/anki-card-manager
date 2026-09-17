package com.borderless.ankicards.data.deck

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Row mapping one AnkiDroid deck (by its full `::`-joined name) to the
 * language it was locked to at creation.
 *
 * We key by name rather than AnkiDroid's numeric deck id because:
 *  - AnkiDroid syncs by name; ids can change across sync conflicts.
 *  - Users may rename decks inside AnkiDroid. A future "rebind by id" pass
 *    can move rows over if needed.
 *  - Name is the only stable identifier our generator already uses to route
 *    cards.
 *
 * [ankiDeckId] is denormalized purely for diagnostics — if a deck gets
 * renamed in AnkiDroid we can spot the orphan by id.
 */
@Entity(tableName = "deck_languages")
data class DeckLanguageEntity(
    @PrimaryKey val deckName: String,
    val languageCode: String,
    val ankiDeckId: Long?,
    val createdAt: Long
)
