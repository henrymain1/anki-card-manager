package com.borderless.ankicards.data.wordlist

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * One English word the user wants to study. The optional [explanation] is a
 * Markdown blob produced by Gemini via the "tutor" prompt — cached so the
 * screen doesn't re-call the API every time the row expands.
 */
@Serializable
data class WordlistItem(
    val id: String = UUID.randomUUID().toString(),
    val word: String,
    val explanation: String? = null
)

/**
 * JSON-file-backed wordlist. Mirrors the persistence pattern used by
 * [com.borderless.ankicards.data.queue.QueuePersistence]: one file, rewritten
 * atomically on every change, with all access serialized through a Mutex.
 */
class WordlistRepository(
    appContext: Context,
    private val applicationScope: CoroutineScope
) {

    private val file: File = File(appContext.filesDir, "wordlist.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val mutex = Mutex()

    private val _items = MutableStateFlow<List<WordlistItem>>(emptyList())
    val items: StateFlow<List<WordlistItem>> = _items.asStateFlow()

    init {
        applicationScope.launch(Dispatchers.IO) { load() }
    }

    private suspend fun load() = mutex.withLock {
        if (!file.exists()) return@withLock
        runCatching {
            val raw = file.readText()
            if (raw.isBlank()) return@withLock
            _items.value = json.decodeFromString(raw)
        }
    }

    private suspend fun persist(items: List<WordlistItem>) = withContext(Dispatchers.IO) {
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(items))
            if (file.exists()) file.delete()
            tmp.renameTo(file)
        }
    }

    suspend fun add(word: String) {
        val trimmed = word.trim()
        if (trimmed.isEmpty()) return
        mutex.withLock {
            // Avoid trivial duplicates (case-insensitive).
            if (_items.value.any { it.word.equals(trimmed, ignoreCase = true) }) return@withLock
            val next = _items.value + WordlistItem(word = trimmed)
            _items.value = next
            persist(next)
        }
    }

    suspend fun remove(id: String) = mutex.withLock {
        val next = _items.value.filterNot { it.id == id }
        if (next.size == _items.value.size) return@withLock
        _items.value = next
        persist(next)
    }

    suspend fun removeByWord(word: String) = mutex.withLock {
        val target = word.trim()
        val next = _items.value.filterNot { it.word.equals(target, ignoreCase = true) }
        if (next.size == _items.value.size) return@withLock
        _items.value = next
        persist(next)
    }

    suspend fun setExplanation(id: String, explanation: String?) = mutex.withLock {
        val next = _items.value.map {
            if (it.id == id) it.copy(explanation = explanation) else it
        }
        _items.value = next
        persist(next)
    }
}
