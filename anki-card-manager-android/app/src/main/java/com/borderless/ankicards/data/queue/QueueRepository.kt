package com.borderless.ankicards.data.queue

import android.util.Log
import com.borderless.ankicards.data.anki.AnkiDroidRepository
import com.borderless.ankicards.data.gemini.GeminiRepository
import com.borderless.ankicards.data.tts.TtsRepository
import com.borderless.ankicards.notifications.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Single source of truth for queued / staged cards.
 *
 * Items enter via [enqueue] (typically from a SEND intent share). A single
 * background worker processes them sequentially — text gen, then image gen,
 * then transitions the item to [QueueItem.State.Ready] for the user to approve.
 *
 * Sequential processing keeps us friendly to Gemini rate limits and avoids
 * spawning N coroutines if the user shares 20 words in a row.
 *
 * Persistence is delegated to [QueuePersistence] — items survive app restart
 * and process death. Saves are debounced 500ms so a burst of state changes
 * collapses into a single write.
 */
@OptIn(FlowPreview::class)
class QueueRepository(
    private val applicationScope: CoroutineScope,
    private val gemini: GeminiRepository,
    private val tts: TtsRepository,
    private val anki: AnkiDroidRepository,
    private val notifier: Notifier,
    private val persistence: QueuePersistence
) {

    private val _items = MutableStateFlow<List<QueueItem>>(emptyList())
    val items: StateFlow<List<QueueItem>> = _items.asStateFlow()

    /** Internal channel that drives the single-threaded worker loop. */
    private val workChannel = Channel<String>(capacity = Channel.UNLIMITED)

    init {
        applicationScope.launch {
            // Restore persisted state BEFORE starting the worker so any
            // re-queued items don't race against an empty initial list.
            restoreFromDisk()
            workerLoop()
        }
        // Auto-save whenever the queue changes. drop(1) skips the initial
        // empty-state emission so we don't immediately overwrite saved data
        // before restore completes.
        applicationScope.launch {
            _items
                .drop(1)
                .debounce(SAVE_DEBOUNCE_MS)
                .collect { snapshot -> persistence.save(snapshot) }
        }
    }

    private suspend fun restoreFromDisk() {
        val restored = persistence.load()
        if (restored.isEmpty()) return

        // Anything that was Approving when the process died had unknown
        // outcome — demote to Ready so the user can retry. Anything that was
        // Generating gets re-queued so generation actually finishes this run.
        val sanitized = restored.map { item ->
            when (item.state) {
                QueueItem.State.Approving -> item.copy(state = QueueItem.State.Ready)
                else -> item
            }
        }
        _items.value = sanitized
        sanitized
            .filter { it.state == QueueItem.State.Generating }
            .forEach { workChannel.trySend(it.id) }

        Log.d(TAG, "Restored ${sanitized.size} item(s) from disk")
    }

    /**
     * Add a word to the queue. Trims and ignores blank input. Returns the new
     * item's id, or null if [input] was blank.
     */
    fun enqueue(input: String): String? {
        val word = input.trim()
        if (word.isBlank()) return null

        val item = QueueItem(input = word)
        _items.update { it + item }
        workChannel.trySend(item.id)
        return item.id
    }

    fun retry(id: String) {
        val original = _items.value.firstOrNull { it.id == id }
        Log.d(TAG, "Retry requested. id=$id  input=\"${original?.input}\"")

        // Wipe any stale card/error from a previous failed attempt — guarantees
        // the next run starts from the original input only.
        _items.update { list ->
            list.map {
                if (it.id == id) {
                    it.copy(
                        state = QueueItem.State.Generating,
                        errorMessage = null,
                        card = null
                    )
                } else it
            }
        }
        workChannel.trySend(id)
    }

    fun dismiss(id: String) {
        _items.update { it.filterNot { item -> item.id == id } }
        notifier.cancel(id)
    }

    /** User edited the card preview for a Ready item — persist the changes to the queue. */
    fun editCard(id: String, updated: com.borderless.ankicards.domain.model.Card) {
        _items.update { list ->
            list.map { if (it.id == id) it.copy(card = updated) else it }
        }
    }

    /** Drop every queued item. Useful for clearing test artifacts. */
    fun clearAll() {
        val ids = _items.value.map { it.id }
        _items.value = emptyList()
        ids.forEach { notifier.cancel(it) }
    }

    /**
     * Send a [QueueItem.State.Ready] item to AnkiDroid. The item is briefly
     * flipped to [QueueItem.State.Approving] to disable double-taps, then
     * removed on success — or restored to Ready with an error on failure.
     */
    suspend fun approve(id: String): Result<Unit> {
        val item = _items.value.firstOrNull { it.id == id }
            ?: return Result.failure(IllegalStateException("Item $id no longer in queue."))
        val card = item.card
            ?: return Result.failure(IllegalStateException("Item $id has no card to approve."))

        _items.update { list ->
            list.map { if (it.id == id) it.copy(state = QueueItem.State.Approving) else it }
        }

        val result = anki.upsertNote(card)
        return result.fold(
            onSuccess = {
                _items.update { it.filterNot { item -> item.id == id } }
                notifier.cancel(id)
                Result.success(Unit)
            },
            onFailure = { err ->
                Log.e(TAG, "Approve failed for $id", err)
                _items.update { list ->
                    list.map {
                        if (it.id == id) {
                            it.copy(
                                state = QueueItem.State.Ready,
                                errorMessage = err.message ?: "Failed to add card"
                            )
                        } else it
                    }
                }
                Result.failure(err)
            }
        )
    }

    // ── Worker ────────────────────────────────────────────────────────────

    private suspend fun workerLoop() {
        for (id in workChannel) {
            processOne(id)
        }
    }

    private suspend fun processOne(id: String) {
        val current = _items.value.firstOrNull { it.id == id } ?: return
        val word = current.input

        Log.d(TAG, "Processing id=$id  input=\"$word\"")

        val textResult = gemini.generateCard(word)
        val card = textResult.getOrElse { err ->
            Log.e(TAG, "Text gen failed for '$word'", err)
            val msg = humanizeError(err)
            markFailed(id, msg)
            notifier.notifyCardFailed(id, word, msg)
            return
        }

        // Stash the text-only card so the staging UI can show it immediately.
        _items.update { list ->
            list.map { if (it.id == id) it.copy(card = card) else it }
        }

        // Phase 2: image. Soft-fail; we still proceed to TTS.
        val imageResult = gemini.generateImage(card.front.ifBlank { word })
        var warning: String? = null
        imageResult
            .onSuccess { bytes ->
                _items.update { list ->
                    list.map {
                        if (it.id == id) it.copy(card = it.card?.copy(imageBytes = bytes))
                        else it
                    }
                }
            }
            .onFailure { err ->
                Log.w(TAG, "Image gen failed for '$word' — continuing without image", err)
                warning = "Image gen failed: ${humanizeError(err)}"
            }

        // Phase 3: TTS for the Chinese headword. Also soft-fail.
        val chineseText = _items.value.firstOrNull { it.id == id }?.card?.chinese.orEmpty()
        if (chineseText.isNotBlank()) {
            tts.speakCantonese(chineseText)
                .onSuccess { audio ->
                    _items.update { list ->
                        list.map {
                            if (it.id == id) it.copy(card = it.card?.copy(audioBytes = audio))
                            else it
                        }
                    }
                }
                .onFailure { err ->
                    Log.w(TAG, "TTS failed for '$chineseText' — continuing without audio", err)
                    val ttsMsg = "TTS failed: ${humanizeError(err)}"
                    warning = warning?.let { "$it; $ttsMsg" } ?: ttsMsg
                }
        }

        // Final transition to Ready, with whatever soft warnings accumulated.
        _items.update { list ->
            list.map {
                if (it.id == id) it.copy(state = QueueItem.State.Ready, errorMessage = warning)
                else it
            }
        }
        notifier.notifyCardReady(id, word, hasWarning = warning != null)
    }

    /** Translate noisy SDK exception messages into something a user can act on. */
    private fun humanizeError(err: Throwable): String {
        val msg = err.message.orEmpty()
        return when {
            msg.contains("Unable to resolve host", ignoreCase = true) ->
                "No internet connection — can't reach Gemini. Check Wi-Fi or mobile data."
            msg.contains("timeout", ignoreCase = true) ->
                "Gemini took too long to respond. Try again on a faster connection."
            msg.contains("API key not valid", ignoreCase = true) ||
                msg.contains("API_KEY_INVALID", ignoreCase = true) ->
                "Gemini API key is invalid. Open Settings to update it."
            msg.contains("quota", ignoreCase = true) ||
                msg.contains("RESOURCE_EXHAUSTED", ignoreCase = true) ->
                "Gemini quota exceeded. Try again later or check your Google AI Studio usage."
            msg.isBlank() -> "Generation failed (no error message)."
            else -> msg
        }
    }

    private fun markFailed(id: String, message: String) {
        _items.update { list ->
            list.map {
                if (it.id == id) it.copy(state = QueueItem.State.Failed, errorMessage = message) else it
            }
        }
    }

    companion object {
        private const val TAG = "AnkiCards.Queue"
        private const val SAVE_DEBOUNCE_MS = 500L
    }
}
