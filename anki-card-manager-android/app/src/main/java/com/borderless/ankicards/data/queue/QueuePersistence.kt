package com.borderless.ankicards.data.queue

import android.content.Context
import android.util.Log
import com.borderless.ankicards.domain.model.Card
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * On-disk persistence for the inbox queue. Survives app restart / process death.
 *
 * Layout under [Context.getFilesDir]:
 *   queue.json                — JSON list of [PersistedItem] (metadata only)
 *   queue_images/<itemId>.png — image bytes for items that have one
 *   queue_audio/<itemId>.mp3  — TTS audio bytes for items that have one
 *
 * Image bytes are stored as separate files instead of base64 inside the JSON
 * so saves stay fast (rewriting one image doesn't touch the others) and the
 * metadata file stays small enough to parse quickly on cold start.
 */
class QueuePersistence(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
        encodeDefaults = true
    }

    /** Serialize/deserialize through this lock to avoid concurrent rewrites. */
    private val mutex = Mutex()

    private val metadataFile: File get() = File(context.filesDir, METADATA_FILE_NAME)
    private val imagesDir: File get() = File(context.filesDir, IMAGES_DIR).apply { mkdirs() }
    private val audioDir: File get() = File(context.filesDir, AUDIO_DIR).apply { mkdirs() }

    /** Write [items] to disk. Cleans up image files for items no longer in the queue. */
    suspend fun save(items: List<QueueItem>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val persisted = items.map { item ->
                    val imageFilename = item.card?.imageBytes?.let { bytes ->
                        val file = File(imagesDir, "${item.id}.png")
                        file.writeBytes(bytes)
                        file.name
                    }
                    val audioFilename = item.card?.audioBytes?.let { bytes ->
                        val file = File(audioDir, "${item.id}.mp3")
                        file.writeBytes(bytes)
                        file.name
                    }
                    PersistedItem(
                        id = item.id,
                        input = item.input,
                        createdAt = item.createdAt,
                        state = item.state.name,
                        errorMessage = item.errorMessage,
                        card = item.card?.let(::toPersistedCard),
                        imageFilename = imageFilename,
                        audioFilename = audioFilename
                    )
                }

                // Drop image / audio files that no item references anymore.
                val keepImages = persisted.mapNotNull { it.imageFilename }.toSet()
                imagesDir.listFiles()?.forEach { file ->
                    if (file.name !in keepImages) file.delete()
                }
                val keepAudio = persisted.mapNotNull { it.audioFilename }.toSet()
                audioDir.listFiles()?.forEach { file ->
                    if (file.name !in keepAudio) file.delete()
                }

                // Atomic-ish: write to .tmp, rename. Avoids leaving a half-written file
                // if the process dies mid-write.
                val tmp = File(context.filesDir, "$METADATA_FILE_NAME.tmp")
                tmp.writeText(json.encodeToString(persisted))
                if (!tmp.renameTo(metadataFile)) {
                    metadataFile.writeBytes(tmp.readBytes())
                    tmp.delete()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to persist queue", e)
            }
        }
    }

    /** Load the queue from disk. Returns an empty list if nothing was persisted. */
    suspend fun load(): List<QueueItem> = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!metadataFile.exists()) return@withContext emptyList()
            val raw = runCatching { metadataFile.readText() }.getOrNull()
                ?: return@withContext emptyList()
            val persisted = runCatching {
                json.decodeFromString<List<PersistedItem>>(raw)
            }.getOrElse {
                Log.e(TAG, "queue.json was unparseable; ignoring", it)
                return@withContext emptyList()
            }

            persisted.mapNotNull { p ->
                val state = runCatching { QueueItem.State.valueOf(p.state) }.getOrNull()
                    ?: return@mapNotNull null
                val imageBytes = p.imageFilename?.let { name ->
                    runCatching { File(imagesDir, name).readBytes() }.getOrNull()
                }
                val audioBytes = p.audioFilename?.let { name ->
                    runCatching { File(audioDir, name).readBytes() }.getOrNull()
                }
                QueueItem(
                    id = p.id,
                    input = p.input,
                    createdAt = p.createdAt,
                    state = state,
                    card = p.card?.let { fromPersistedCard(it, imageBytes, audioBytes) },
                    errorMessage = p.errorMessage
                )
            }
        }
    }

    private fun toPersistedCard(card: Card): PersistedCard = PersistedCard(
        front = card.front,
        chinese = card.chinese,
        jyutping = card.jyutping,
        measureWord = card.measureWord,
        exampleEnglish = card.exampleEnglish,
        exampleJyutping = card.exampleJyutping,
        exampleChinese = card.exampleChinese
    )

    private fun fromPersistedCard(
        p: PersistedCard,
        imageBytes: ByteArray?,
        audioBytes: ByteArray?
    ): Card = Card(
        front = p.front,
        chinese = p.chinese,
        jyutping = p.jyutping,
        measureWord = p.measureWord,
        exampleEnglish = p.exampleEnglish,
        exampleJyutping = p.exampleJyutping,
        exampleChinese = p.exampleChinese,
        imageBytes = imageBytes,
        audioBytes = audioBytes
    )

    @Serializable
    private data class PersistedItem(
        val id: String,
        val input: String,
        val createdAt: Long,
        val state: String,
        val errorMessage: String? = null,
        val card: PersistedCard? = null,
        val imageFilename: String? = null,
        val audioFilename: String? = null
    )

    @Serializable
    private data class PersistedCard(
        val front: String,
        val chinese: String,
        val jyutping: String,
        val measureWord: String,
        val exampleEnglish: String,
        val exampleJyutping: String,
        val exampleChinese: String
    )

    companion object {
        private const val TAG = "AnkiCards.QueuePersist"
        private const val METADATA_FILE_NAME = "queue.json"
        private const val IMAGES_DIR = "queue_images"
        private const val AUDIO_DIR = "queue_audio"
    }
}
