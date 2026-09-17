package com.borderless.ankicards.data.deck

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DeckLanguageDao {

    @Query("SELECT * FROM deck_languages")
    fun observeAll(): Flow<List<DeckLanguageEntity>>

    @Query("SELECT * FROM deck_languages")
    suspend fun getAll(): List<DeckLanguageEntity>

    @Query("SELECT * FROM deck_languages WHERE deckName = :deckName COLLATE NOCASE LIMIT 1")
    suspend fun getByName(deckName: String): DeckLanguageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: DeckLanguageEntity)

    @Query("DELETE FROM deck_languages WHERE deckName = :deckName COLLATE NOCASE")
    suspend fun delete(deckName: String)
}
