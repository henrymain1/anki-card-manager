package com.borderless.ankicards.data.recipe

import com.borderless.ankicards.domain.recipe.CardField
import com.borderless.ankicards.domain.recipe.NoteType
import com.borderless.ankicards.domain.recipe.FieldGenerator
import com.borderless.ankicards.domain.recipe.LanguagePresets
import com.borderless.ankicards.domain.recipe.RecipeJson
import com.borderless.ankicards.domain.recipe.Template
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer

/**
 * Single source of truth for [NoteType]s on this device.
 *
 * Backed by Room. Converts between the persistence entity ([NoteTypeEntity])
 * and the domain class ([NoteType]); the rest of the app only ever sees the
 * domain class, so the storage choice can change later without rippling out.
 *
 * On construction it kicks off a first-run seed: if the table is empty we
 * insert every preset from [LanguagePresets] so a freshly-installed app has
 * something to show in the builder and deck-creation flows. The seed runs
 * fire-and-forget on [applicationScope]; callers that need to wait should
 * collect [observeAll] which will emit the seeded rows once they land.
 */
class NoteTypeRepository(
    private val dao: NoteTypeDao,
    applicationScope: CoroutineScope
) {

    private val fieldListSerializer = ListSerializer(CardField.serializer())
    private val templateListSerializer = ListSerializer(Template.serializer())

    init {
        applicationScope.launch {
            seedIfEmpty()
            migrateAudioSourceFieldToWord()
            migrateDefaultDesignName()
        }
    }

    fun observeAll(): Flow<List<NoteType>> =
        dao.observeAll().map { rows -> rows.map(::toDomain) }

    suspend fun getAll(): List<NoteType> = dao.getAll().map(::toDomain)

    suspend fun getById(id: String): NoteType? = dao.getById(id)?.let(::toDomain)

    suspend fun upsert(noteType: NoteType) {
        // Preserve any existing ankiModelId binding on update.
        val existing = dao.getById(noteType.id)
        dao.upsert(toEntity(noteType, ankiModelId = existing?.ankiModelId))
    }

    /** Persist the AnkiDroid note-type id we created/found for this card type. */
    suspend fun setAnkiModelId(noteTypeId: String, ankiModelId: Long) {
        val existing = dao.getById(noteTypeId) ?: return
        dao.upsert(existing.copy(ankiModelId = ankiModelId, updatedAt = System.currentTimeMillis()))
    }

    suspend fun getAnkiModelId(noteTypeId: String): Long? =
        dao.getById(noteTypeId)?.ankiModelId

    suspend fun delete(id: String) {
        dao.delete(id)
    }

    private suspend fun seedIfEmpty() {
        if (dao.count() > 0) return
        val now = System.currentTimeMillis()
        val seeds = LanguagePresets.all.map { factory ->
            toEntity(factory(), ankiModelId = null, updatedAt = now)
        }
        dao.upsertAll(seeds)
    }

    /**
     * One-time data migration: any existing audio (TTS) field that still
     * has `sourceFieldKey = "example"` from the old preset default gets
     * rewritten to `"word"`. Idempotent — running this on a card type
     * that's already on the new default is a no-op (no upsert fires).
     *
     * Why this exists: the audio default flipped from `example` (sentence-
     * level pronunciation) to `word` (word-level pronunciation) in the
     * preset code. But existing card types in the user's DB still have
     * the old value stored in their fields JSON — they were created before
     * the flip. Without this migration, users would have to delete + recreate
     * every card type to pick up the new default, since there's no UI to
     * edit a field's `sourceFieldKey`.
     *
     * Safe assumption: nobody has yet explicitly chosen `example` (the only
     * way to set it was the old preset default), so flipping every match
     * to `word` won't trample a deliberate user choice. If a generator-
     * type editor ever ships, this migration becomes lossy and should be
     * removed.
     */
    private suspend fun migrateAudioSourceFieldToWord() {
        val rows = dao.getAll()
        for (entity in rows) {
            val noteType = toDomain(entity)
            val rewrittenFields = noteType.fields.map { field ->
                val gen = field.generator
                if (gen is FieldGenerator.Tts && gen.sourceFieldKey == "example") {
                    field.copy(generator = FieldGenerator.Tts(sourceFieldKey = "word"))
                } else field
            }
            if (rewrittenFields != noteType.fields) {
                dao.upsert(
                    toEntity(
                        noteType = noteType.copy(fields = rewrittenFields),
                        ankiModelId = entity.ankiModelId,
                        updatedAt = entity.updatedAt
                    )
                )
            }
        }
    }

    /**
     * One-time rename: the old seed named every default design "Card 1", which
     * is meaningless once designs are the user-facing "card type" (the same bare
     * "Card 1" appeared for every language on the Designs tab). New seeds name
     * the starter design after the note type ("Spanish Vocabulary"). This brings
     * existing data in line: any design still literally named "Card 1" is
     * renamed to its note type's name. Custom designs the user named themselves
     * (e.g. "Card 2") are untouched.
     *
     * Idempotent: after the rename no "Card 1" remains, so re-running is a no-op.
     */
    private suspend fun migrateDefaultDesignName() {
        val rows = dao.getAll()
        for (entity in rows) {
            val noteType = toDomain(entity)
            val renamed = noteType.templates.map { t ->
                if (t.name == "Card 1") t.copy(name = noteType.name) else t
            }
            if (renamed != noteType.templates) {
                dao.upsert(
                    toEntity(
                        noteType = noteType.copy(templates = renamed),
                        ankiModelId = entity.ankiModelId,
                        updatedAt = entity.updatedAt
                    )
                )
            }
        }
    }

    private fun toEntity(
        noteType: NoteType,
        ankiModelId: Long?,
        updatedAt: Long = System.currentTimeMillis()
    ): NoteTypeEntity =
        NoteTypeEntity(
            id = noteType.id,
            name = noteType.name,
            language = noteType.language,
            defaultImageStyle = noteType.defaultImageStyle,
            fieldsJson = RecipeJson.format.encodeToString(fieldListSerializer, noteType.fields),
            templatesJson = RecipeJson.format.encodeToString(templateListSerializer, noteType.templates),
            ankiModelId = ankiModelId,
            updatedAt = updatedAt
        )

    private fun toDomain(entity: NoteTypeEntity): NoteType {
        val fields = RecipeJson.format.decodeFromString(fieldListSerializer, entity.fieldsJson)
        val templates = if (entity.templatesJson.isBlank()) listOf(Template())
        else RecipeJson.format.decodeFromString(templateListSerializer, entity.templatesJson)
        return NoteType(
            id = entity.id,
            name = entity.name,
            language = entity.language,
            defaultImageStyle = entity.defaultImageStyle,
            fields = fields,
            templates = templates
        )
    }
}
