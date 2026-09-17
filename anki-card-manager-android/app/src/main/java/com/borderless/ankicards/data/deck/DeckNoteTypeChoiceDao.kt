package com.borderless.ankicards.data.deck

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface DeckNoteTypeChoiceDao {

    @Query("SELECT * FROM deck_card_type_choices WHERE deckName = :deckName COLLATE NOCASE LIMIT 1")
    suspend fun getByDeck(deckName: String): DeckNoteTypeChoiceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: DeckNoteTypeChoiceEntity)

    @Query("DELETE FROM deck_card_type_choices WHERE deckName = :deckName COLLATE NOCASE")
    suspend fun delete(deckName: String)
}
