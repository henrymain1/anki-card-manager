package com.borderless.ankicards.ui.builder

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.borderless.ankicards.data.anki.AnkiDroidRepository
import com.borderless.ankicards.data.deck.DeckLanguageRepository
import com.borderless.ankicards.data.recipe.NoteTypeRepository
import com.borderless.ankicards.data.settings.SettingsRepository
import com.borderless.ankicards.domain.deck.DeckLanguage
import com.borderless.ankicards.domain.recipe.NoteType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Drives the Designs tab.
 *
 * Beyond listing card types from the repository, this view model resolves
 * cross-cutting context the rows need: the active deck (so we can show a
 * banner and offer a "compatible only" filter), and a compatibility map
 * `noteTypeId -> [deck names]` derived from each card type's language vs.
 * each deck's resolved language.
 *
 * Compatibility rule: a card type and a deck are compatible when their
 * languages match exactly, OR the card type is generic (null/empty
 * language — usable everywhere). Generic *decks* match only generic card
 * types — they explicitly opt out of language smarts, so we don't surface
 * language-specific designs there.
 */
class NoteTypeListViewModel(
    private val noteTypes: NoteTypeRepository,
    private val anki: AnkiDroidRepository,
    private val deckLanguages: DeckLanguageRepository,
    private val settings: SettingsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(NoteTypeListUiState())
    val uiState: StateFlow<NoteTypeListUiState> = _uiState.asStateFlow()

    init {
        // Observe card types continuously so adds/edits/deletes flow through.
        viewModelScope.launch {
            noteTypes.observeAll().collect { types ->
                _uiState.update { it.copy(noteTypes = types) }
                recomputeCompatibility()
            }
        }
        // One-shot load of decks + active deck + their languages. The deck
        // list refreshes manually via refreshDecks(); we don't subscribe to
        // it because AnkiDroid's provider isn't a Flow source.
        viewModelScope.launch { refreshDecks() }
    }

    fun refresh() {
        viewModelScope.launch { refreshDecks() }
    }

    fun setShowAll(value: Boolean) {
        _uiState.update { it.copy(showAll = value) }
    }

    /**
     * Step 1 of delete: user tapped the trash icon. Look up the AnkiDroid
     * model id (if any) and count notes attached to it, then open the
     * confirm dialog with that context. We do this in the VM (not the
     * composable) so the dialog never opens with stale or missing counts.
     */
    fun requestDelete(noteType: NoteType) {
        viewModelScope.launch {
            val ankiModelId = noteTypes.getAnkiModelId(noteType.id)
            val noteCount = if (ankiModelId != null) {
                anki.countNotesForModel(ankiModelId).getOrNull()
            } else 0
            _uiState.update {
                it.copy(
                    pendingDelete = PendingDelete(
                        noteTypeId = noteType.id,
                        noteTypeName = noteType.name,
                        ankiModelId = ankiModelId,
                        ankiNoteCount = noteCount
                    )
                )
            }
        }
    }

    /** Dismiss the confirm dialog without deleting anything. */
    fun cancelDelete() {
        _uiState.update { it.copy(pendingDelete = null) }
    }

    /**
     * Step 2 of delete: user confirmed. Best-effort delete every note of
     * this card type from AnkiDroid (their cards + review history go with
     * them), then delete the local card type row.
     *
     * The AnkiDroid note-type definition itself is NOT removable via the
     * public ContentProvider — only individual notes are. So the empty note
     * type will remain visible in AnkiDroid → Manage Note Types after this
     * runs; the transient message tells the user how to clean it up.
     */
    fun confirmDelete() {
        val pending = _uiState.value.pendingDelete ?: return
        _uiState.update { it.copy(pendingDelete = pending.copy(deleting = true)) }
        viewModelScope.launch {
            val notesDeleted = pending.ankiModelId?.let { mid ->
                anki.deleteAllNotesForModel(mid).getOrNull()
            } ?: 0
            noteTypes.delete(pending.noteTypeId)
            val msg = buildString {
                append("Deleted \"${pending.noteTypeName}\"")
                if (notesDeleted > 0) {
                    append(" and $notesDeleted note${if (notesDeleted == 1) "" else "s"} from AnkiDroid")
                }
                if (pending.ankiModelId != null) {
                    append(". The empty note type still shows in AnkiDroid → Manage Note Types — remove it there if you want.")
                } else {
                    append(".")
                }
            }
            _uiState.update {
                it.copy(pendingDelete = null, transientMessage = msg)
            }
        }
    }

    /** Snackbar dismiss callback — clears the one-shot message. */
    fun clearTransientMessage() {
        _uiState.update { it.copy(transientMessage = null) }
    }

    // ── internals ────────────────────────────────────────────────────────

    private suspend fun refreshDecks() {
        val activeDeckName = settings.deckName.first()
        val deckResult = anki.listDecks()
        val deckNames = deckResult.getOrNull()?.map { it.name }.orEmpty()
        val deckLangs = deckNames.associate { name ->
            name to (deckLanguages.resolveLanguage(name) ?: DeckLanguage.Generic)
        }
        val activeLanguage = deckLanguages.resolveLanguage(activeDeckName)
        _uiState.update {
            it.copy(
                activeDeckName = activeDeckName,
                activeDeckLanguage = activeLanguage,
                deckLanguages = deckLangs
            )
        }
        recomputeCompatibility()
    }

    /**
     * Recompute `noteTypeId -> compatible deck names`, the flattened
     * [DesignRow] list (the user-facing card types), and which note type the
     * active deck's language maps to. Called whenever the card types or the
     * deck/language pool changes.
     */
    private fun recomputeCompatibility() {
        val state = _uiState.value
        val map = state.noteTypes.associate { ct ->
            ct.id to state.deckLanguages
                .filter { (_, deckLang) -> compatible(ct.language, deckLang) }
                .keys
                .sortedBy { it.lowercase() }
                .toList()
        }
        // Flatten every note type's designs into the user-facing list.
        val designs = state.noteTypes.flatMap { ct ->
            ct.templates.map { template ->
                DesignRow(
                    noteTypeId = ct.id,
                    template = template,
                    noteType = ct,
                    compatibleDecks = map[ct.id].orEmpty()
                )
            }
        }
        // The note type for the active deck's language — where "New" adds a design.
        val activeCode = state.activeDeckLanguage?.code?.takeIf { it.isNotBlank() }
        val activeLanguageNoteTypeId = state.noteTypes.firstOrNull { ct ->
            val ctCode = ct.language?.takeIf { it.isNotBlank() }
            ctCode == activeCode
        }?.id
        _uiState.update {
            it.copy(
                compatibleDeckNamesByNoteType = map,
                designs = designs,
                activeLanguageNoteTypeId = activeLanguageNoteTypeId
            )
        }
    }

    /**
     * Delete one design (template). If it's the note type's only design, fall
     * through to the full note-type delete (with its confirm dialog + AnkiDroid
     * note cleanup). Otherwise remove just that design locally.
     *
     * Caveat: AnkiDroid's API can't remove a single template from an existing
     * model, so for the multi-design case the old card template lingers in
     * AnkiDroid until the note type is rebuilt. The message says so.
     */
    fun deleteDesign(noteTypeId: String, templateId: String) {
        viewModelScope.launch {
            val ct = noteTypes.getById(noteTypeId) ?: return@launch
            if (ct.templates.size <= 1) {
                requestDelete(ct)
            } else {
                noteTypes.upsert(ct.copy(templates = ct.templates.filterNot { it.id == templateId }))
                _uiState.update {
                    it.copy(
                        transientMessage = "Removed the design. AnkiDroid keeps the old card " +
                            "template until this note type is rebuilt."
                    )
                }
            }
        }
    }

    private fun compatible(noteTypeLanguage: String?, deckLanguage: DeckLanguage): Boolean {
        val cardCode = noteTypeLanguage?.takeIf { it.isNotBlank() }
        return when {
            // Generic card type: matches every deck.
            cardCode == null -> true
            // Card type has a language; deck must match.
            else -> cardCode == deckLanguage.code
        }
    }

    companion object {
        fun factory(
            noteTypes: NoteTypeRepository,
            anki: AnkiDroidRepository,
            deckLanguages: DeckLanguageRepository,
            settings: SettingsRepository
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                NoteTypeListViewModel(noteTypes, anki, deckLanguages, settings) as T
        }
    }
}
