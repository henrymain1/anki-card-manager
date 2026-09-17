package com.borderless.ankicards.domain.recipe

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * JSON round-trip for [NoteType]. Used to persist recipes locally and,
 * eventually, to import/export recipes between users.
 *
 * The on-disk format is the same as the over-the-wire format — deliberately,
 * so the "share this recipe" feature can later be a single file send with no
 * conversion step. The format is versioned via the JSON tag on
 * [FieldGenerator] subtypes; if we need to evolve the schema we can add a
 * "version" field at the top of [NoteType] and gate parsing on it.
 */
object RecipeJson {

    /**
     * Configured to be lenient on read (ignore unknown keys, so future
     * versions can add fields without breaking older app versions on
     * shared recipes) and pretty on write (so files are human-editable in
     * a pinch).
     */
    val format: Json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = false
        classDiscriminator = "type"
    }

    fun encode(noteType: NoteType): String = format.encodeToString(noteType)

    fun decode(json: String): NoteType = format.decodeFromString(NoteType.serializer(), json)
}
