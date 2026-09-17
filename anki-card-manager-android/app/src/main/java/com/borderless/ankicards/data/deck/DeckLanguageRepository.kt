package com.borderless.ankicards.data.deck

import com.borderless.ankicards.domain.deck.DeckLanguage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Single source of truth for "what language is this deck for?"
 *
 * Lookups walk the deck-name hierarchy: a deck named `cantonese::Animals::Birds`
 * inherits its language from `cantonese` if no assignment exists for the more
 * specific paths. This matches the way AnkiDroid treats subdecks as
 * organizational, not conceptual — a Cantonese subdeck of a Cantonese deck
 * doesn't need its own language assignment.
 *
 * Assignments are immutable once written (per the "language can't change
 * later" rule). Callers that try to overwrite are silently rejected unless
 * they call [forceReassign] — a hidden override for tests / data fixes, not
 * exposed in the normal UI.
 */
class DeckLanguageRepository(
    private val dao: DeckLanguageDao
) {

    /**
     * Stream of all known assignments. Used by the deck list screen to render
     * language chips alongside each row.
     */
    fun observeAssignments(): Flow<Map<String, DeckLanguage>> =
        dao.observeAll().map { rows ->
            rows.associate { it.deckName.lowercase() to DeckLanguage.fromCode(it.languageCode) }
        }

    /**
     * Resolve the language of [deckName], walking parents until a match is
     * found. Returns null if no ancestor has an assignment — that's the
     * "orphan deck" state the UI surfaces to the user with the picker.
     *
     * Example: if `cantonese` is assigned `yue` and the caller asks for
     * `cantonese::Animals::Birds`, this returns `yue`.
     */
    suspend fun resolveLanguage(deckName: String): DeckLanguage? {
        // Try the exact name first, then each parent path.
        var path: String? = deckName
        while (path != null && path.isNotBlank()) {
            val hit = dao.getByName(path)
            if (hit != null) return DeckLanguage.fromCode(hit.languageCode)
            val sep = path.lastIndexOf("::")
            path = if (sep < 0) null else path.substring(0, sep)
        }
        return null
    }

    /**
     * Assign a language to a top-level deck. Subdecks under it will inherit
     * via [resolveLanguage] — they don't get their own rows.
     *
     * No-ops if an assignment already exists (immutability). Use
     * [forceReassign] to override.
     */
    suspend fun assignLanguage(
        deckName: String,
        language: DeckLanguage,
        ankiDeckId: Long? = null
    ): AssignmentOutcome {
        val existing = dao.getByName(deckName)
        if (existing != null) return AssignmentOutcome.AlreadyAssigned(
            DeckLanguage.fromCode(existing.languageCode)
        )
        dao.upsert(
            DeckLanguageEntity(
                deckName = deckName,
                languageCode = language.code,
                ankiDeckId = ankiDeckId,
                createdAt = System.currentTimeMillis()
            )
        )
        return AssignmentOutcome.Assigned(language)
    }

    /**
     * Force-overwrite a deck's language. Not surfaced in the normal UI;
     * intended for tests and data-repair flows.
     */
    suspend fun forceReassign(
        deckName: String,
        language: DeckLanguage,
        ankiDeckId: Long? = null
    ) {
        dao.upsert(
            DeckLanguageEntity(
                deckName = deckName,
                languageCode = language.code,
                ankiDeckId = ankiDeckId,
                createdAt = System.currentTimeMillis()
            )
        )
    }

    /**
     * Delete a deck's assignment. Used when the deck is deleted from
     * AnkiDroid; we don't auto-detect that today, but the entry point exists
     * so a future "tidy" pass can wire it up.
     */
    suspend fun forget(deckName: String) = dao.delete(deckName)

    sealed class AssignmentOutcome {
        data class Assigned(val language: DeckLanguage) : AssignmentOutcome()
        data class AlreadyAssigned(val existing: DeckLanguage) : AssignmentOutcome()
    }
}
