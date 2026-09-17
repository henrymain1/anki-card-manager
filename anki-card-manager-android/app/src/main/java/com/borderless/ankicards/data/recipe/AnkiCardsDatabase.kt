package com.borderless.ankicards.data.recipe

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.borderless.ankicards.data.deck.DeckNoteTypeChoiceDao
import com.borderless.ankicards.data.deck.DeckNoteTypeChoiceEntity
import com.borderless.ankicards.data.deck.DeckLanguageDao
import com.borderless.ankicards.data.deck.DeckLanguageEntity

/**
 * Single Room database for app-local data the user owns (recipes today,
 * dictionary cache and LLM-result cache in later phases). AnkiDroid's own
 * database is accessed via the content provider and is not part of this.
 */
@Database(
    entities = [
        NoteTypeEntity::class,
        DeckLanguageEntity::class,
        DeckNoteTypeChoiceEntity::class
    ],
    version = 7,
    exportSchema = false
)
abstract class AnkiCardsDatabase : RoomDatabase() {

    abstract fun noteTypeDao(): NoteTypeDao
    abstract fun deckLanguageDao(): DeckLanguageDao
    abstract fun deckNoteTypeChoiceDao(): DeckNoteTypeChoiceDao

    companion object {
        fun build(context: Context): AnkiCardsDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                AnkiCardsDatabase::class.java,
                "anki-cards.db"
            )
                // No production data yet — wipe on schema change rather than write
                // migrations for an evolving model. Revisit before first release.
                .fallbackToDestructiveMigration()
                .build()
    }
}
