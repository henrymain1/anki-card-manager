package com.borderless.ankicards.data.recipe

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteTypeDao {

    @Query("SELECT * FROM card_types ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<NoteTypeEntity>>

    @Query("SELECT * FROM card_types ORDER BY name COLLATE NOCASE")
    suspend fun getAll(): List<NoteTypeEntity>

    @Query("SELECT * FROM card_types WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): NoteTypeEntity?

    @Query("SELECT COUNT(*) FROM card_types")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: NoteTypeEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<NoteTypeEntity>)

    @Query("DELETE FROM card_types WHERE id = :id")
    suspend fun delete(id: String)
}
