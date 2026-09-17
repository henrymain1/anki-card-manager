package com.borderless.ankicards.data.anki

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.borderless.ankicards.data.images.ImageFormat
import com.borderless.ankicards.data.settings.SettingsRepository
import com.borderless.ankicards.domain.model.Card
import com.borderless.ankicards.domain.recipe.AnkiTemplate
import com.borderless.ankicards.domain.recipe.MAX_DESIGNS
import com.borderless.ankicards.domain.recipe.NoteType
import com.borderless.ankicards.domain.recipe.controlFieldLabel
import com.borderless.ankicards.domain.recipe.designControlValues
import com.borderless.ankicards.domain.recipe.toAnkiTemplate
import com.borderless.ankicards.domain.recipe.toAnkiTemplates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Talks to AnkiDroid via its ContentProvider (FlashCardsContract).
 *
 * Public surface:
 *  - [isAnkiDroidInstalled] — package presence check
 *  - [hasPermission] — runtime permission check
 *  - [upsertNote] — find-or-create deck, look up the named note model, then either
 *    update an existing note (matched by Front) or insert a new one.
 *
 * Image bytes are first staged in our cache, exposed via [FileProvider], then
 * copied into AnkiDroid's media collection through its media ContentProvider.
 * The stored filename is referenced from the "image" field as `<img src="X.png">`
 * (or `.jpg` — the extension is sniffed from the bytes, since searched stock
 * photos are JPEG while generated ones are PNG), matching the desktop app. We can't inline images as base64 data URLs because
 * Android's Binder IPC has a ~1MB transaction limit.
 */
class AnkiDroidRepository(
    private val context: Context,
    private val settings: SettingsRepository
) {

    fun isAnkiDroidInstalled(): Boolean = try {
        context.packageManager.getPackageInfo("com.ichi2.anki", 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, FlashCardsContract.PERMISSION_READ_WRITE) ==
                PackageManager.PERMISSION_GRANTED

    suspend fun upsertNote(card: Card): Result<UpsertOutcome> = withContext(Dispatchers.IO) {
        runCatching {
            check(isAnkiDroidInstalled()) { "AnkiDroid is not installed on this device." }
            check(hasPermission()) { "AnkiDroid permission not granted." }

            val deckName = settings.deckName.first().ifBlank { "Cantonese" }
            val modelName = settings.modelName.first().ifBlank { "Basic" }

            val deckId = findOrCreateDeck(deckName)
                ?: error("Could not find or create deck \"$deckName\".")

            val (modelId, fieldNames) = findModel(modelName)
                ?: error(
                    "Note type \"$modelName\" not found in AnkiDroid. " +
                    "Open AnkiDroid → Manage Note Types and create it first."
                )

            check(fieldNames.isNotEmpty()) {
                "Note type \"$modelName\" reported zero fields. Cannot continue."
            }

            // If the card has media, hand it to AnkiDroid's media collection now
            // (a Binder transaction can't carry multi-MB inline data).
            val imageFilename = card.imageBytes?.let { storeImageInAnkiMedia(it, card.front) }
            val audioFilename = card.audioBytes?.let { storeAudioInAnkiMedia(it) }

            val flds = buildFieldsArray(card, fieldNames, imageFilename, audioFilename)
            val existingId = findNoteIdByFront(modelId, card.front)

            if (existingId != null) {
                updateNote(existingId, flds)
                moveNoteCardsToDeck(existingId, deckId)
                UpsertOutcome.Updated(existingId)
            } else {
                val newId = insertNote(modelId, deckId, flds, tagsCsv = "generated")
                moveNoteCardsToDeck(newId, deckId)
                UpsertOutcome.Created(newId)
            }
        }
    }

    // ── Decks ────────────────────────────────────────────────────────────

    /**
     * Every deck visible to AnkiDroid, in the order the content provider
     * returns them. Read-only; doesn't create anything.
     */
    suspend fun listDecks(): Result<List<DeckInfo>> = withContext(Dispatchers.IO) {
        runCatching {
            check(isAnkiDroidInstalled()) { "AnkiDroid is not installed on this device." }
            check(hasPermission()) { "AnkiDroid permission not granted." }
            val resolver = context.contentResolver
            val out = mutableListOf<DeckInfo>()
            resolver.query(FlashCardsContract.Deck.CONTENT_URI, null, null, null, null)?.use { c ->
                val nameIdx = c.getColumnIndex(FlashCardsContract.Deck.DECK_NAME)
                val idIdx = c.getColumnIndex(FlashCardsContract.Deck.DECK_ID)
                if (nameIdx < 0 || idIdx < 0) return@runCatching emptyList<DeckInfo>()
                while (c.moveToNext()) {
                    out += DeckInfo(id = c.getLong(idIdx), name = c.getString(nameIdx).orEmpty())
                }
            }
            out
        }
    }

    /**
     * Create a deck with the given name (or return the existing one's id if
     * a deck with that name already exists). AnkiDroid's content provider
     * does the "find or create" internally; we just call it.
     */
    suspend fun createDeck(name: String): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            check(isAnkiDroidInstalled()) { "AnkiDroid is not installed on this device." }
            check(hasPermission()) { "AnkiDroid permission not granted." }
            val trimmed = name.trim()
            require(trimmed.isNotEmpty()) { "Deck name must not be blank." }
            findOrCreateDeck(trimmed)
                ?: error("AnkiDroid did not return an id for new deck \"$trimmed\".")
        }
    }

    data class DeckInfo(val id: Long, val name: String)

    private fun findOrCreateDeck(deckName: String): Long? {
        val resolver = context.contentResolver
        resolver.query(FlashCardsContract.Deck.CONTENT_URI, null, null, null, null)?.use { c ->
            val nameIdx = c.getColumnIndex(FlashCardsContract.Deck.DECK_NAME)
            val idIdx = c.getColumnIndex(FlashCardsContract.Deck.DECK_ID)
            if (nameIdx < 0 || idIdx < 0) return null
            while (c.moveToNext()) {
                if (c.getString(nameIdx).equals(deckName, ignoreCase = true)) {
                    return c.getLong(idIdx)
                }
            }
        }
        // Not found: create it.
        val values = ContentValues().apply {
            put(FlashCardsContract.Deck.DECK_NAME, deckName)
        }
        val uri = resolver.insert(FlashCardsContract.Deck.CONTENT_URI, values) ?: return null
        return uri.lastPathSegment?.toLongOrNull()
    }

    // ── Models ───────────────────────────────────────────────────────────

    private fun findModel(modelName: String): Pair<Long, List<String>>? {
        val resolver = context.contentResolver
        resolver.query(FlashCardsContract.Model.CONTENT_URI, null, null, null, null)?.use { c ->
            val idIdx = c.getColumnIndex(FlashCardsContract.Model.ID)
            val nameIdx = c.getColumnIndex(FlashCardsContract.Model.NAME)
            val fieldsIdx = c.getColumnIndex(FlashCardsContract.Model.FIELD_NAMES)
            if (idIdx < 0 || nameIdx < 0) return null
            while (c.moveToNext()) {
                if (c.getString(nameIdx).equals(modelName, ignoreCase = true)) {
                    val id = c.getLong(idIdx)
                    val fieldsRaw = if (fieldsIdx >= 0) c.getString(fieldsIdx).orEmpty() else ""
                    // AnkiDroid uses  (unit separator). Older builds / forks
                    // sometimes use newline — accept either.
                    val fields = fieldsRaw
                        .split(FlashCardsContract.FIELD_SEPARATOR, '\n')
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                    return id to fields
                }
            }
        }
        return null
    }

    // ── Notes ────────────────────────────────────────────────────────────

    /**
     * Count notes in AnkiDroid that belong to the given note type (model).
     * Used by the card-type delete flow to tell the user how many notes will
     * be deleted before they confirm. Cheap query — selection is on the
     * indexed MID column.
     */
    suspend fun countNotesForModel(modelId: Long): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            check(isAnkiDroidInstalled()) { "AnkiDroid is not installed on this device." }
            check(hasPermission()) { "AnkiDroid permission not granted." }
            val selection = "${FlashCardsContract.Note.MID} = ?"
            val args = arrayOf(modelId.toString())
            var count = 0
            context.contentResolver.query(
                FlashCardsContract.Note.CONTENT_URI,
                arrayOf(FlashCardsContract.Note.ID),
                selection,
                args,
                null
            )?.use { c ->
                count = c.count
            }
            count
        }
    }

    /**
     * Delete every note that belongs to the given note type.
     *
     * AnkiDroid's public ContentProvider does not expose a way to delete a
     * note type itself — only individual notes (which cascade to the cards
     * derived from them and their review history). So calling this leaves
     * the note type definition behind as an empty entry in
     * AnkiDroid → Manage Note Types. The caller should tell the user to
     * remove it there if they want a fully clean state.
     *
     * Returns the number of notes actually deleted (which can be less than
     * the count we showed if AnkiDroid rejected some — partial success is
     * possible, so we report what we got).
     */
    suspend fun deleteAllNotesForModel(modelId: Long): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            check(isAnkiDroidInstalled()) { "AnkiDroid is not installed on this device." }
            check(hasPermission()) { "AnkiDroid permission not granted." }
            val resolver = context.contentResolver
            val selection = "${FlashCardsContract.Note.MID} = ?"
            val args = arrayOf(modelId.toString())
            val noteIds = mutableListOf<Long>()
            resolver.query(
                FlashCardsContract.Note.CONTENT_URI,
                arrayOf(FlashCardsContract.Note.ID),
                selection,
                args,
                null
            )?.use { c ->
                val idIdx = c.getColumnIndex(FlashCardsContract.Note.ID)
                if (idIdx >= 0) {
                    while (c.moveToNext()) noteIds += c.getLong(idIdx)
                }
            }
            var deleted = 0
            for (id in noteIds) {
                val noteUri = FlashCardsContract.Note.CONTENT_URI.buildUpon()
                    .appendPath(id.toString())
                    .build()
                // resolver.delete returns the number of rows the provider
                // reports as removed. AnkiDroid returns 1 on success.
                deleted += runCatching { resolver.delete(noteUri, null, null) }
                    .getOrDefault(0)
            }
            deleted
        }
    }

    private fun findNoteIdByFront(modelId: Long, front: String): Long? {
        if (front.isBlank()) return null
        val resolver = context.contentResolver
        // AnkiDroid supports a deck-scoped query via URI param, but for matching by Front
        // we filter client-side after asking for notes of this model.
        val selection = "${FlashCardsContract.Note.MID} = ?"
        val args = arrayOf(modelId.toString())
        resolver.query(FlashCardsContract.Note.CONTENT_URI, null, selection, args, null)?.use { c ->
            val idIdx = c.getColumnIndex(FlashCardsContract.Note.ID)
            val fldsIdx = c.getColumnIndex(FlashCardsContract.Note.FLDS)
            if (idIdx < 0 || fldsIdx < 0) return null
            while (c.moveToNext()) {
                val firstField = c.getString(fldsIdx)
                    ?.split(FlashCardsContract.FIELD_SEPARATOR)
                    ?.firstOrNull()
                    .orEmpty()
                if (firstField.equals(front, ignoreCase = true)) {
                    return c.getLong(idIdx)
                }
            }
        }
        return null
    }

    private fun insertNote(modelId: Long, deckId: Long, flds: String, tagsCsv: String): Long {
        val values = ContentValues().apply {
            put(FlashCardsContract.Note.MID, modelId)
            put(FlashCardsContract.Note.FLDS, flds)
            put(FlashCardsContract.Note.TAGS, tagsCsv)
        }

        // Primary path: pass deckId as a URI query param so the new note's cards
        // land in the requested deck.
        val withDeck = FlashCardsContract.Note.CONTENT_URI.buildUpon()
            .appendQueryParameter(FlashCardsContract.Note.DECK_ID_QUERY_PARAM, deckId.toString())
            .build()

        val resolver = context.contentResolver
        val firstAttempt = runCatching { resolver.insert(withDeck, values) }
        firstAttempt.getOrNull()?.lastPathSegment?.toLongOrNull()?.let { return it }

        // Fallback: some AnkiDroid builds reject the deckId query param.
        // Insert without it; cards will land in the model's default deck.
        val secondAttempt = runCatching { resolver.insert(FlashCardsContract.Note.CONTENT_URI, values) }
        secondAttempt.getOrNull()?.lastPathSegment?.toLongOrNull()?.let { return it }

        // Both attempts failed — surface the most useful diagnostic we can build.
        val firstErr = firstAttempt.exceptionOrNull()?.message
        val secondErr = secondAttempt.exceptionOrNull()?.message
        val fieldCount = flds.count { it == FlashCardsContract.FIELD_SEPARATOR } + 1
        error(
            "AnkiDroid rejected the note insert.\n" +
            "  modelId=$modelId  deckId=$deckId  field count sent=$fieldCount\n" +
            "  primary attempt: ${firstErr ?: "returned null URI"}\n" +
            "  fallback attempt: ${secondErr ?: "returned null URI"}\n" +
            "Common causes: model field count mismatch, model not assigned to any deck, " +
            "or AnkiDroid sync conflict. Check AnkiDroid → Manage Note Types → field list."
        )
    }

    /**
     * After inserting/updating a note, AnkiDroid places its cards in the model's
     * default deck. To honor the user's chosen deck we walk every card belonging
     * to [noteId] and update its DECK_ID column.
     */
    private fun moveNoteCardsToDeck(noteId: Long, deckId: Long) {
        val cardsUri = FlashCardsContract.Note.CONTENT_URI.buildUpon()
            .appendPath(noteId.toString())
            .appendPath("cards")
            .build()

        val resolver = context.contentResolver
        resolver.query(cardsUri, null, null, null, null)?.use { c ->
            val ordIdx = c.getColumnIndex(FlashCardsContract.Card.CARD_ORD)
            if (ordIdx < 0) return
            while (c.moveToNext()) {
                val ord = c.getString(ordIdx) ?: continue
                val cardUri = cardsUri.buildUpon().appendPath(ord).build()
                val values = ContentValues().apply {
                    put(FlashCardsContract.Card.DECK_ID, deckId)
                }
                resolver.update(cardUri, values, null, null)
            }
        }
    }

    private fun updateNote(noteId: Long, flds: String) {
        val rowUri = FlashCardsContract.Note.CONTENT_URI.buildUpon()
            .appendPath(noteId.toString())
            .build()
        val values = ContentValues().apply {
            put(FlashCardsContract.Note.FLDS, flds)
        }
        context.contentResolver.update(rowUri, values, null, null)
    }

    // ── Field assembly ───────────────────────────────────────────────────

    /**
     * Maps our [Card] onto the model's named fields. Anything the model has but we don't
     * know how to fill is left blank. Order matches the model's field order exactly,
     * and we join with AnkiDroid's separator.
     */
    private fun buildFieldsArray(
        card: Card,
        fieldNames: List<String>,
        imageFilename: String?,
        audioFilename: String?
    ): String {
        val imageHtml = imageFilename?.let { "<img src=\"$it\">" }.orEmpty()
        val soundTag = audioFilename?.let { "[sound:$it]" }.orEmpty()

        // Each value can be matched by any of several aliases — so renaming a
        // field in AnkiDroid (e.g. "Front" → "English") doesn't break the mapping.
        val aliasGroups: List<Pair<List<String>, String>> = listOf(
            listOf("front", "english", "word") to card.front,
            listOf("chinese", "characters", "hanzi", "traditional") to card.chinese,
            listOf("jyutping", "romanization", "pinyin") to card.jyutping,
            listOf("measure word", "classifier", "measureword") to card.measureWord.replace("\n", "<br>"),
            listOf("example", "sentence", "examples") to card.exampleHtml,
            listOf("image", "picture", "img") to imageHtml,
            listOf("sound", "audio", "tts") to soundTag
        )
        val byFieldName: Map<String, String> = buildMap {
            for ((aliases, value) in aliasGroups) {
                for (alias in aliases) put(alias, value)
            }
        }

        return fieldNames.joinToString(FlashCardsContract.FIELD_SEPARATOR.toString()) { name ->
            byFieldName[name.lowercase().trim()] ?: ""
        }
    }

    // ── Media ────────────────────────────────────────────────────────────

    /**
     * Stages [bytes] in our app's cache, exposes it as a content:// URI via
     * FileProvider, and asks AnkiDroid to copy it into its media collection.
     * Returns the filename AnkiDroid stored it under (e.g.
     * "card_apple_1714612345.jpg"), which can then be referenced from a field
     * as <img src="filename.jpg">.
     *
     * The extension is sniffed from the bytes rather than assumed. It used to
     * be hardcoded `.png`, which was correct while Gemini was the only source;
     * searched stock photos are JPEG, and a JPEG named `.png` renders only
     * because viewers sniff content anyway.
     */
    private fun storeImageInAnkiMedia(bytes: ByteArray, frontWord: String): String {
        // 1. Write to cache. The path here must match res/xml/file_paths.xml.
        val dir = File(context.cacheDir, "generated").apply { mkdirs() }
        val safeName = frontWord.lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .ifBlank { "card" }
        val extension = ImageFormat.detect(bytes).extension
        val file = File(dir, "${safeName}_${System.currentTimeMillis()}.$extension")
        file.writeBytes(bytes)

        // 2. Expose to AnkiDroid via FileProvider.
        val authority = "${context.packageName}.fileprovider"
        val sharedUri: Uri = FileProvider.getUriForFile(context, authority, file)
        context.grantUriPermission(
            "com.ichi2.anki",
            sharedUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION
        )

        // 3. Ask AnkiDroid to copy it into the media collection.
        val values = ContentValues().apply {
            put(FlashCardsContract.AnkiMedia.FILE_URI, sharedUri.toString())
            put(FlashCardsContract.AnkiMedia.PREFERRED_NAME, file.nameWithoutExtension)
        }
        val resultUri = context.contentResolver.insert(FlashCardsContract.AnkiMedia.CONTENT_URI, values)
            ?: error("AnkiDroid rejected the media insert (returned null URI).")

        // The last path segment of the returned URI is the actual stored filename.
        return resultUri.lastPathSegment
            ?: error("AnkiDroid returned a media URI with no filename: $resultUri")
    }

    /**
     * Stages [bytes] as an MP3, exposes it via FileProvider, and asks AnkiDroid
     * to copy it into its media collection. The filename is content-hashed so
     * the same audio bytes always resolve to the same file in AnkiDroid's
     * media folder — re-saving a regenerated card with identical audio won't
     * leave orphan files behind.
     */
    private fun storeAudioInAnkiMedia(bytes: ByteArray): String {
        val sha1 = java.security.MessageDigest.getInstance("SHA-1")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
        val preferredName = "tts-$sha1"

        val dir = File(context.cacheDir, "generated").apply { mkdirs() }
        val file = File(dir, "$preferredName.mp3")
        file.writeBytes(bytes)

        val authority = "${context.packageName}.fileprovider"
        val sharedUri: Uri = FileProvider.getUriForFile(context, authority, file)
        context.grantUriPermission(
            "com.ichi2.anki",
            sharedUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION
        )

        val values = ContentValues().apply {
            put(FlashCardsContract.AnkiMedia.FILE_URI, sharedUri.toString())
            put(FlashCardsContract.AnkiMedia.PREFERRED_NAME, preferredName)
        }
        val resultUri = context.contentResolver.insert(FlashCardsContract.AnkiMedia.CONTENT_URI, values)
            ?: error("AnkiDroid rejected the audio insert (returned null URI).")
        return resultUri.lastPathSegment
            ?: error("AnkiDroid returned a media URI with no filename: $resultUri")
    }

    sealed class UpsertOutcome(val noteId: Long) {
        class Created(noteId: Long) : UpsertOutcome(noteId)
        class Updated(noteId: Long) : UpsertOutcome(noteId)
    }

    // ── Generated-card upsert (Phase 2 pipeline) ─────────────────────────

    /**
     * Phase 2 entry point. Writes a card whose shape was decided by a
     * [NoteType] designed in our visual builder.
     *
     * Resolves the AnkiDroid model id for [noteType]; if none exists yet
     * (the card type hasn't been pushed to AnkiDroid), pushes the template
     * first via [pushNoteType]. Then stages any media into AnkiDroid's media
     * collection and inserts/updates a note with field values keyed by name
     * — matching the canonical field order the exporter committed to.
     *
     * Match-or-create: an existing note is detected by comparing the value of
     * the *first* declared field (typically `word`) against the corresponding
     * value in [textByFieldKey]. The legacy "Front" alias path is gone.
     */
    suspend fun upsertGeneratedNote(
        noteType: com.borderless.ankicards.domain.recipe.NoteType,
        textByFieldKey: Map<String, String>,
        imageBytesByFieldKey: Map<String, ByteArray>,
        audioBytesByFieldKey: Map<String, ByteArray>,
        deckName: String,
        existingAnkiModelId: Long?,
        /**
         * Which designs (template ids) to produce cards for on this note. Empty
         * = all designs. For 2+-design note types this drives the hidden
         * control fields (an un-selected design's card is suppressed). Ignored
         * for single-design note types.
         */
        selectedTemplateIds: Set<String> = emptySet()
    ): Result<UpsertOutcome> = withContext(Dispatchers.IO) {
        runCatching {
            check(isAnkiDroidInstalled()) { "AnkiDroid is not installed on this device." }
            check(hasPermission()) { "AnkiDroid permission not granted." }
            require(deckName.isNotBlank()) { "Deck name is blank — pick a deck first." }

            // Ensure the model exists in AnkiDroid and we have its id.
            val modelId = existingAnkiModelId ?: pushNoteType(noteType, null).getOrThrow()

            val deckId = findOrCreateDeck(deckName)
                ?: error("Could not find or create deck \"$deckName\".")

            // Stage media first — content provider transactions can't carry
            // multi-MB blobs, so we copy into AnkiDroid's media collection and
            // reference the resulting filenames in the field payload.
            val template = noteType.toAnkiTemplate()
            val resolvedWord = textByFieldKey["word"]
                ?: textByFieldKey.values.firstOrNull().orEmpty()

            val imageFilenamesByKey = imageBytesByFieldKey.mapValues { (_, bytes) ->
                storeImageInAnkiMedia(bytes, resolvedWord.ifBlank { "card" })
            }
            val audioFilenamesByKey = audioBytesByFieldKey.mapValues { (_, bytes) ->
                storeAudioInAnkiMedia(bytes)
            }

            val flds = buildGeneratedFieldsArray(
                noteType = noteType,
                template = template,
                textByFieldKey = textByFieldKey,
                imageFilenamesByKey = imageFilenamesByKey,
                audioFilenamesByKey = audioFilenamesByKey,
                controlValuesByLabel = noteType.designControlValues(selectedTemplateIds)
            )

            val existingId = findExistingGeneratedNoteId(noteType, modelId, template, textByFieldKey)
            if (existingId != null) {
                updateNote(existingId, flds)
                moveNoteCardsToDeck(existingId, deckId)
                UpsertOutcome.Updated(existingId)
            } else {
                val newId = insertNote(modelId, deckId, flds, tagsCsv = "generated")
                moveNoteCardsToDeck(newId, deckId)
                UpsertOutcome.Created(newId)
            }
        }
    }

    /**
     * Assemble the FLDS payload in the canonical field order the template was
     * exported with. Each field's value comes from:
     *
     *  - the matching image filename wrapped in `<img>` if this is an image field
     *  - the matching audio filename wrapped in `[sound:...]` if this is an audio field
     *  - the matching text value otherwise
     *  - empty string if nothing was generated for this field
     */
    private fun buildGeneratedFieldsArray(
        noteType: com.borderless.ankicards.domain.recipe.NoteType,
        template: com.borderless.ankicards.domain.recipe.AnkiTemplate,
        textByFieldKey: Map<String, String>,
        imageFilenamesByKey: Map<String, String>,
        audioFilenamesByKey: Map<String, String>,
        controlValuesByLabel: Map<String, String>
    ): String {
        // template.fieldOrder is by label (what AnkiDroid stores). To map label
        // → value we need the field's key, so build a label→key map. Control
        // fields (per-design card selection) aren't real CardFields — their
        // values come from controlValuesByLabel, keyed by label directly.
        val keyByLabel = noteType.fields.associate { it.label to it.key }
        return template.fieldOrder.joinToString(FlashCardsContract.FIELD_SEPARATOR.toString()) { label ->
            controlValuesByLabel[label]?.let { return@joinToString it }
            val key = keyByLabel[label] ?: return@joinToString ""
            when {
                imageFilenamesByKey[key] != null ->
                    "<img src=\"${imageFilenamesByKey[key]}\">"
                audioFilenamesByKey[key] != null ->
                    "[sound:${audioFilenamesByKey[key]}]"
                else -> textByFieldKey[key].orEmpty().toAnkiHtml()
            }
        }
    }

    /**
     * Convert plain-text field values into the lightly-HTML form AnkiDroid
     * expects.
     *
     * Order of operations matters:
     *  1. Convert literal `\n` / `\r\n` escape sequences (occasionally
     *     produced by the LLM despite the prompt forbidding them, or by a
     *     user pasting escape sequences into the editor sheet) into real
     *     newline characters first.
     *  2. Trim leading/trailing whitespace so values don't render with
     *     stray blank lines at the top or bottom.
     *  3. Collapse runs of blank lines so we don't emit `<br><br><br>`
     *     chains.
     *  4. Convert remaining real newlines to `<br>` for HTML rendering.
     */
    private fun String.toAnkiHtml(): String {
        if (isEmpty()) return this
        return this
            .replace("\\r\\n", "\n")
            .replace("\\n", "\n")
            .replace("\\r", "\n")
            .replace("\r\n", "\n")
            .trim()
            .replace(Regex("\n\\s*\n+"), "\n")
            .replace("\n", "<br>")
    }

    /**
     * Look for an existing note of the given model where the first declared
     * field (canonical "primary" — typically `word`) equals the value we're
     * about to write. Used so re-generating the same word updates the
     * existing note instead of creating a duplicate.
     */
    private fun findExistingGeneratedNoteId(
        noteType: com.borderless.ankicards.domain.recipe.NoteType,
        modelId: Long,
        template: com.borderless.ankicards.domain.recipe.AnkiTemplate,
        textByFieldKey: Map<String, String>
    ): Long? {
        // The match key is the value of the *first* field in canonical order.
        // That's the field whose label is template.fieldOrder[0]. We need its
        // key to look the value up out of textByFieldKey.
        val primaryLabel = template.fieldOrder.firstOrNull() ?: return null
        val primaryKey = noteType.fields.firstOrNull { it.label == primaryLabel }?.key
            ?: return null
        val primaryValue = textByFieldKey[primaryKey].orEmpty()
        if (primaryValue.isBlank()) return null

        val resolver = context.contentResolver
        val selection = "${FlashCardsContract.Note.MID} = ?"
        val args = arrayOf(modelId.toString())
        resolver.query(FlashCardsContract.Note.CONTENT_URI, null, selection, args, null)?.use { c ->
            val idIdx = c.getColumnIndex(FlashCardsContract.Note.ID)
            val fldsIdx = c.getColumnIndex(FlashCardsContract.Note.FLDS)
            if (idIdx < 0 || fldsIdx < 0) return null
            while (c.moveToNext()) {
                val first = c.getString(fldsIdx)
                    ?.split(FlashCardsContract.FIELD_SEPARATOR)
                    ?.firstOrNull()
                    .orEmpty()
                if (first.equals(primaryValue, ignoreCase = true)) {
                    return c.getLong(idIdx)
                }
            }
        }
        return null
    }

    // ── Note-type push (visual designer → AnkiDroid model) ──────────────

    /**
     * Push a [NoteType] to AnkiDroid as a note type (a.k.a. "model").
     *
     * If [existingAnkiModelId] is null, creates a new model — the caller should
     * persist the returned id locally so subsequent saves update in place
     * instead of duplicating. If non-null, updates that model's templates and
     * CSS; the field list is left alone to avoid breaking existing notes.
     *
     * Returns the AnkiDroid model id on success.
     */
    suspend fun pushNoteType(
        noteType: NoteType,
        existingAnkiModelId: Long?
    ): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            check(isAnkiDroidInstalled()) { "AnkiDroid is not installed on this device." }
            check(hasPermission()) { "AnkiDroid permission not granted." }

            val templates = noteType.toAnkiTemplates()
            if (existingAnkiModelId != null) {
                updateAnkiModel(existingAnkiModelId, templates)
                existingAnkiModelId
            } else {
                insertAnkiModel(noteType.name, templates)
            }
        }
    }

    private fun insertAnkiModel(name: String, templates: List<AnkiTemplate>): Long {
        require(name.isNotBlank()) { "AnkiDroid model name cannot be empty." }
        require(templates.isNotEmpty()) { "AnkiDroid model needs at least one template." }
        require(templates.size <= MAX_DESIGNS) {
            "This card type has ${templates.size} designs, but the maximum is $MAX_DESIGNS."
        }
        // All templates share one field array + CSS (see AnkiTemplateExporter).
        val shared = templates.first()
        require(shared.fieldOrder.isNotEmpty()) {
            "AnkiDroid model needs at least one field — the card type has none."
        }
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(FlashCardsContract.Model.NAME, name)
            // AnkiDroid expects the field list joined with the unit-separator
            // it uses internally for FLDS payloads. We pass field NAMES (not
            // keys) because AnkiDroid surfaces them in its UI.
            put(
                FlashCardsContract.Model.FIELD_NAMES_INSERT,
                shared.fieldOrder.joinToString(FlashCardsContract.FIELD_SEPARATOR.toString())
            )
            // Pre-allocate MAX_DESIGNS template slots so adding designs later
            // doesn't require model recreation (AnkiDroid's ContentProvider
            // can't add templates to an existing model). Unused slots are
            // gated by empty control fields → front renders empty → Anki
            // creates no card. See AnkiTemplateExporter.MAX_DESIGNS.
            put(FlashCardsContract.Model.NUM_CARDS, MAX_DESIGNS)
            put(FlashCardsContract.Model.CSS, shared.css)
        }
        val uri = resolver.insert(FlashCardsContract.Model.CONTENT_URI, values)
            ?: error("AnkiDroid rejected the model insert (returned null URI).")
        val modelId = uri.lastPathSegment?.toLongOrNull()
            ?: error("AnkiDroid returned a model URI with no id: $uri")

        // Fill all MAX_DESIGNS template slots. Ords 0..N-1 get the real
        // templates; ords N..MAX_DESIGNS-1 get placeholder templates whose
        // front is gated on a control field that's always empty → Anki's
        // empty-card rule suppresses them.
        for (ord in 0 until MAX_DESIGNS) {
            if (ord < templates.size) {
                updateTemplateInPlace(
                    modelId, ord = ord,
                    template = templates[ord],
                    name = templates[ord].name
                )
            } else {
                updateTemplateInPlace(
                    modelId, ord = ord,
                    template = placeholderTemplate(ord, shared.css, shared.fieldOrder),
                    name = "— (unused ${ord + 1})"
                )
            }
        }
        // Belt-and-suspenders: CSS is passed in the insert values above, but it
        // is NOT confirmed that AnkiDroid's model-insert handler persists the
        // CSS column (only the model-row PATCH in setModelCss is known to work).
        // Without this, a freshly-inserted note type renders with AnkiDroid's
        // default CSS — i.e. unstyled/generic, ignoring our designed template.
        // Re-applying via the PATCH path guarantees the styling lands.
        setModelCss(modelId, shared.css)
        return modelId
    }

    /**
     * Build a placeholder [AnkiTemplate] for a pre-allocated but unused slot.
     * Its front is gated on `_cardN` (which is always empty for unused slots),
     * so Anki's empty-card rule suppresses it — no card is generated.
     */
    private fun placeholderTemplate(
        ord: Int,
        css: String,
        fieldOrder: List<String>
    ): AnkiTemplate {
        val label = controlFieldLabel(ord)
        return AnkiTemplate(
            name = "— (unused ${ord + 1})",
            frontHtml = "{{#$label}}(unused){{/$label}}",
            backHtml = "",
            css = css,
            fieldOrder = fieldOrder
        )
    }

    private fun updateAnkiModel(modelId: Long, templates: List<AnkiTemplate>) {
        require(templates.isNotEmpty()) { "AnkiDroid model needs at least one template." }
        require(templates.size <= MAX_DESIGNS) {
            "This card type has ${templates.size} designs, but the maximum is $MAX_DESIGNS."
        }
        val shared = templates.first()
        // Update CSS on the model row (shared across all templates).
        setModelCss(modelId, shared.css)

        // Update all MAX_DESIGNS template slots. Because we pre-allocate
        // MAX_DESIGNS ords on insert, every ord 0..MAX_DESIGNS-1 exists and
        // can be updated. Real designs get their actual HTML; unused slots
        // get a placeholder that's always suppressed by the control field.
        for (ord in 0 until MAX_DESIGNS) {
            if (ord < templates.size) {
                updateTemplateInPlace(
                    modelId, ord = ord,
                    template = templates[ord],
                    name = templates[ord].name
                )
            } else {
                updateTemplateInPlace(
                    modelId, ord = ord,
                    template = placeholderTemplate(ord, shared.css, shared.fieldOrder),
                    name = "— (unused ${ord + 1})"
                )
            }
        }
    }

    /**
     * Set the shared CSS on a note-type by PATCHing the model row. This is the
     * only path confirmed to persist CSS (see ankidroid_integration.md). Used by
     * both the insert and update flows so a freshly-created note type styles the
     * same as an updated one.
     */
    private fun setModelCss(modelId: Long, css: String) {
        val modelUri = FlashCardsContract.Model.CONTENT_URI.buildUpon()
            .appendPath(modelId.toString())
            .build()
        val cssValues = ContentValues().apply {
            put(FlashCardsContract.Model.CSS, css)
        }
        context.contentResolver.update(modelUri, cssValues, null, null)
    }

    private fun updateTemplateInPlace(
        modelId: Long,
        ord: Int,
        template: AnkiTemplate,
        name: String? = null
    ) {
        val resolver = context.contentResolver
        val templateUri = FlashCardsContract.Model.CONTENT_URI.buildUpon()
            .appendPath(modelId.toString())
            .appendPath(FlashCardsContract.CardTemplate.URI_PATH)
            .appendPath(ord.toString())
            .build()
        // AnkiDroid's provider identifies the target template entirely from
        // the URI path (modelId from segment 1, ord from the last segment).
        // It also reads MODEL_ID and ORD from values — but only to *reject*
        // them: their presence means "you're trying to reassign this template
        // to a different note type / ord" and the provider throws "Updates to
        // mid or ord are not allowed". So we must NOT include them.
        //
        // The actual original bug was the column keys: we were sending the
        // internal SQLite column names ("qfmt" / "afmt") instead of the
        // provider's ContentValues keys ("question_format" / "answer_format").
        // The provider read null for both and silently updated nothing.
        val values = ContentValues().apply {
            put(FlashCardsContract.CardTemplate.QUESTION_FORMAT, template.frontHtml)
            put(FlashCardsContract.CardTemplate.ANSWER_FORMAT, template.backHtml)
            if (name != null) put(FlashCardsContract.CardTemplate.NAME, name)
        }
        val rows = resolver.update(templateUri, values, null, null)
        // Verify the update actually applied. The provider returns the number
        // of fields it accepted; 0 means the column names didn't match anything
        // it knows (the kind of failure that hid the original bug for weeks).
        check(rows > 0) {
            "AnkiDroid silently rejected the template update " +
                "(modelId=$modelId ord=$ord rowsUpdated=0). " +
                "Likely causes: a contract-key mismatch with the installed AnkiDroid " +
                "version, or template ord=$ord doesn't exist on this model (NUM_CARDS " +
                "on insert may not have created it, or the model was created with fewer " +
                "templates than this card type now has)."
        }
    }
}
