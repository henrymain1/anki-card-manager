package com.borderless.ankicards.ui.generator

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.borderless.ankicards.data.anki.AnkiDroidRepository
import com.borderless.ankicards.data.anki.AnkiDroidRepository.UpsertOutcome
import com.borderless.ankicards.data.deck.DeckNoteTypeChoiceRepository
import com.borderless.ankicards.data.deck.DeckLanguageRepository
import com.borderless.ankicards.data.gemini.CardGenerator
import com.borderless.ankicards.data.gemini.GeneratedMedia
import com.borderless.ankicards.data.gemini.GenerationProgress
import com.borderless.ankicards.data.gemini.RegenerationResult
import com.borderless.ankicards.data.recipe.NoteTypeRepository
import com.borderless.ankicards.data.settings.SettingsRepository
import com.borderless.ankicards.data.wordlist.WordlistRepository
import com.borderless.ankicards.domain.deck.DeckLanguage
import com.borderless.ankicards.domain.recipe.CardField
import com.borderless.ankicards.domain.recipe.CardGenerationRequest
import com.borderless.ankicards.domain.recipe.NoteType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Phase 2 generator view model.
 *
 * Orchestrates four data sources:
 *  - active deck (from settings) + its locked language (from deck-language repo)
 *  - card types (from card-type repo, filtered by language compatibility)
 *  - last-used card type per deck (small persistence convenience)
 *  - the [CardGenerator] pipeline
 *
 * The legacy hardcoded-Cantonese flow that previously lived here has been
 * removed; the queue/staging subsystem still uses the old GeminiRepository
 * path for share-intent background generation and will be migrated in a
 * follow-up.
 */
class CardGeneratorViewModel(
    private val cardGenerator: CardGenerator,
    private val noteTypes: NoteTypeRepository,
    private val anki: AnkiDroidRepository,
    private val settings: SettingsRepository,
    private val deckLanguages: DeckLanguageRepository,
    private val deckNoteTypeChoice: DeckNoteTypeChoiceRepository,
    private val wordlist: WordlistRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(GeneratorUiState())
    val uiState: StateFlow<GeneratorUiState> = _uiState.asStateFlow()

    /**
     * Source word from a wordlist deep-link; removed on successful save.
     * Mutable because the VM is now activity-scoped and [prefillWord] can
     * be called after construction when navigating from the wordlist.
     */
    private var sourceWordlistWord: String? = null

    /**
     * The currently-running generation coroutine, if any. Tracked so that
     * Dismiss can cancel it mid-flight instead of waiting for it to finish
     * and then clobbering the cleared state.
     */
    private var generationJob: Job? = null

    init {
        // Subscribe to the deck name + card-type list flows so the screen
        // stays current without needing a per-visit refresh. Previously we
        // had `refreshContext()` re-running on every navigation back to this
        // tab via `LaunchedEffect(Unit)` — that was both unnecessary (the
        // sources are observable) and visually disruptive: each re-entry
        // briefly re-asserted state that was already correct, which the user
        // perceived as the screen "refreshing" when re-tapping the Generate
        // tab while already on it.
        viewModelScope.launch {
            combine(settings.deckName, noteTypes.observeAll()) { deck, all -> deck to all }
                .collect { (deckName, allNoteTypes) ->
                    val language = deckLanguages.resolveLanguage(deckName)
                    val compatible = allNoteTypes.filter { it.compatibleWith(language) }
                    val storedChoice = deckNoteTypeChoice.getChoice(deckName)
                    _uiState.update { current ->
                        // The generation target is the deck's LANGUAGE note type
                        // (one per language). The user picks which of its designs
                        // to create — not which note type. Prefer the language
                        // match; fall back to last-used, then the first
                        // compatible (e.g. a generic deck → the generic type).
                        val langCode = language?.code?.takeIf { it.isNotBlank() }
                        val selectedType =
                            compatible.firstOrNull { it.language?.takeIf { c -> c.isNotBlank() } == langCode }
                                ?: compatible.firstOrNull { it.id == storedChoice?.noteTypeId }
                                ?: compatible.firstOrNull()
                        val selectedId = selectedType?.id
                        // Designs (templates) of that note type, ticked for
                        // generation. Keep the user's ticks if the note type is
                        // unchanged; otherwise restore the remembered selection
                        // for this deck (persisted on every toggle), falling back
                        // to all designs when nothing's stored yet.
                        val validIds = selectedType?.templates?.map { it.id }?.toSet().orEmpty()
                        val sameType = selectedId != null && selectedId == current.selectedNoteTypeId
                        val storedSelection = storedChoice
                            ?.takeIf { it.noteTypeId == selectedId }
                            ?.selectedTemplateIds
                            ?.intersect(validIds)
                            ?.takeIf { it.isNotEmpty() }
                        val selectedTemplateIds = if (sameType) {
                            current.selectedTemplateIds.intersect(validIds).ifEmpty { validIds }
                        } else storedSelection ?: validIds
                        current.copy(
                            activeDeckName = deckName,
                            activeDeckLanguage = language,
                            availableNoteTypes = compatible,
                            selectedNoteTypeId = selectedId,
                            selectedTemplateIds = selectedTemplateIds,
                            isContextLoaded = true
                        )
                    }
                }
        }
        // Eager initial fetch of all decks for the quick-switch dropdown.
        // We don't observe this continuously because AnkiDroid's content
        // provider isn't a Flow source — the list refreshes again on every
        // dropdown-open tap (see `onDeckPickerOpened`).
        viewModelScope.launch { refreshAvailableDecks() }
        // Loader-animation preference — observed so changing it in Settings
        // takes effect on the next generation without a restart.
        viewModelScope.launch {
            settings.loaderStyle.collect { pref ->
                _uiState.update { it.copy(loaderStyle = pref) }
            }
        }
    }

    /**
     * Quick-switch the active deck. Writes to settings; the existing combine
     * collector above re-derives `activeDeckName` / `activeDeckLanguage` /
     * `availableNoteTypes` from the new deck name automatically, so nothing
     * else here needs touching.
     */
    fun selectDeck(deckName: String) {
        viewModelScope.launch { settings.setDeckName(deckName) }
    }

    /**
     * Called when the user taps the "Writing to" banner to open the deck
     * dropdown. Refreshes the deck list so newly-created decks (made in
     * AnkiDroid itself, or in our Decks tab) show up without forcing a
     * full screen revisit.
     */
    fun onDeckPickerOpened() {
        viewModelScope.launch { refreshAvailableDecks() }
    }

    private suspend fun refreshAvailableDecks() {
        val decks = anki.listDecks().getOrNull() ?: return
        val options = decks
            .map { info ->
                DeckOption(
                    name = info.name,
                    language = deckLanguages.resolveLanguage(info.name)
                )
            }
            .sortedBy { it.name.lowercase() }
        _uiState.update { it.copy(availableDecks = options) }
    }

    private fun NoteType.compatibleWith(deckLanguage: DeckLanguage?): Boolean {
        val cardCode = language?.takeIf { it.isNotBlank() }
        // Card type with no language → matches any deck.
        if (cardCode == null) return true
        // Otherwise the language codes must match. A deck with no resolved
        // language is treated as "no constraint applied yet" — we let
        // generic card types through but not language-specific ones.
        return cardCode == deckLanguage?.code
    }

    fun onQueryChanged(value: String) {
        _uiState.update { it.copy(query = value, errorMessage = null) }
    }

    /**
     * Pre-fill the query from a wordlist deep-link. Called by the navigation
     * layer when the generator route is opened with a `?word=` argument.
     * Also records the word as the [sourceWordlistWord] so it's removed from
     * the wordlist on successful save.
     */
    fun prefillWord(word: String) {
        sourceWordlistWord = word
        _uiState.update { it.copy(query = word, errorMessage = null) }
    }

    /**
     * Toggle whether a design produces a card. Generation targets one note type
     * at a time, so ticking a design from a *different* note type switches the
     * target to that note type (selecting just that design); ticking within the
     * current note type toggles, always keeping ≥1 design ticked.
     */
    fun onDesignToggled(templateId: String) {
        _uiState.update { state ->
            val design = state.availableDesigns.firstOrNull { it.template.id == templateId }
                ?: return@update state
            if (design.noteType.id != state.selectedNoteTypeId) {
                return@update state.copy(
                    selectedNoteTypeId = design.noteType.id,
                    selectedTemplateIds = setOf(templateId)
                )
            }
            val next = if (templateId in state.selectedTemplateIds) {
                state.selectedTemplateIds - templateId
            } else {
                state.selectedTemplateIds + templateId
            }
            if (next.isEmpty()) state else state.copy(selectedTemplateIds = next)
        }
        persistDesignSelection()
    }

    /**
     * Remember the current design selection for the active deck so the next
     * app launch restores it instead of re-ticking every design. Reads the
     * already-updated state, so call after the `_uiState.update` that changes
     * the selection. A soft preference — best-effort, no user-facing failure.
     */
    private fun persistDesignSelection() {
        val state = _uiState.value
        val noteTypeId = state.selectedNoteTypeId ?: return
        val deckName = state.activeDeckName.takeIf { it.isNotBlank() } ?: return
        viewModelScope.launch {
            deckNoteTypeChoice.setChoice(deckName, noteTypeId, state.selectedTemplateIds)
        }
    }

    /**
     * Free-text edit of a generated text field. Lets the user touch up AI
     * output before saving without re-running generation.
     */
    fun onFieldEdited(key: String, value: String) {
        _uiState.update {
            it.copy(generatedTextByFieldKey = it.generatedTextByFieldKey + (key to value))
        }
    }

    fun onGenerateClicked() {
        val state = _uiState.value
        val query = state.query.trim()
        val noteType = state.selectedNoteType
        val deckLanguage = state.activeDeckLanguage
        val selectedTemplates = state.selectedTemplates
        if (query.isBlank() || noteType == null || deckLanguage == null || selectedTemplates.isEmpty()) return

        // Tapping Generate while a previous generation is in flight cancels
        // the previous one — same semantics as the user tapping Dismiss
        // immediately followed by Generate.
        generationJob?.cancel()
        generationJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isGenerating = true,
                    errorMessage = null,
                    fieldErrors = emptyMap(),
                    generatedTextByFieldKey = emptyMap(),
                    generatedMediaByFieldKey = emptyMap()
                )
            }
            val request = CardGenerationRequest(
                query = query,
                // Only the selected designs' fields are generated — a deselected
                // audio card means no TTS call, etc. (one AI call per needed
                // field, reused across the selected cards).
                noteType = noteType.copy(templates = selectedTemplates),
                deckName = state.activeDeckName,
                language = deckLanguage
            )
            try {
                // Stream events into state. Each TextField/AudioReady/
                // ImageReady event updates the corresponding map; the
                // terminal Done event marks generation complete.
                cardGenerator.generate(request).collect { event ->
                    when (event) {
                        is GenerationProgress.PipelineStarted -> { /* no-op; isGenerating is already true */ }
                        is GenerationProgress.TextField -> {
                            _uiState.update {
                                it.copy(
                                    generatedTextByFieldKey =
                                        it.generatedTextByFieldKey + (event.key to event.value)
                                )
                            }
                        }
                        is GenerationProgress.ImageReady -> {
                            _uiState.update {
                                it.copy(
                                    generatedMediaByFieldKey =
                                        it.generatedMediaByFieldKey + (event.fieldKey to
                                            GeneratedMedia.Image(event.bytes, event.sourceWord))
                                )
                            }
                        }
                        is GenerationProgress.AudioReady -> {
                            _uiState.update {
                                it.copy(
                                    generatedMediaByFieldKey =
                                        it.generatedMediaByFieldKey + (event.fieldKey to
                                            GeneratedMedia.Audio(event.bytes))
                                )
                            }
                        }
                        is GenerationProgress.FieldError -> {
                            // A failed leg (image / TTS / LLM) used to only tint
                            // that field's border red — and the WebView surface
                            // ignores `fieldErrors` entirely, so image failures
                            // were completely invisible. Push it to the snackbar
                            // channel too so a dead proxy/quota/504 is visible
                            // instead of silently producing an imageless card.
                            Log.w(TAG, "Field '${event.fieldKey}' failed: ${event.message}")
                            _uiState.update {
                                it.copy(
                                    fieldErrors = it.fieldErrors + (event.fieldKey to event.message),
                                    errorMessage = describeFieldError(
                                        event.fieldKey, event.message, it.selectedNoteType
                                    )
                                )
                            }
                        }
                        is GenerationProgress.Done -> {
                            _uiState.update {
                                it.copy(
                                    isGenerating = false,
                                    // Final composite is already in state from
                                    // the streamed events above; keep the
                                    // canonical version here in case any field
                                    // was edited mid-stream.
                                    generatedTextByFieldKey = event.card.textByFieldKey
                                        .ifEmpty { it.generatedTextByFieldKey },
                                    generatedMediaByFieldKey = event.card.media
                                        .ifEmpty { it.generatedMediaByFieldKey },
                                    fieldErrors = event.card.errors
                                )
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                // Cancellation is the normal exit when the user hits Dismiss
                // mid-generation. The dismiss handler has already cleared the
                // state we'd otherwise touch here — just rethrow per the
                // structured-concurrency convention.
                throw e
            } catch (err: Throwable) {
                Log.e(TAG, "Generation failed", err)
                _uiState.update {
                    it.copy(
                        isGenerating = false,
                        errorMessage = err.message ?: "Generation failed"
                    )
                }
            }
        }
    }

    fun onRegenerateField(field: CardField) {
        val state = _uiState.value
        val noteType = state.selectedNoteType ?: return
        val language = state.activeDeckLanguage ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(regeneratingFieldKey = field.key) }
            val request = CardGenerationRequest(
                query = state.query,
                noteType = noteType,
                deckName = state.activeDeckName,
                language = language
            )
            cardGenerator.regenerateField(field, state.generatedTextByFieldKey, request).fold(
                onSuccess = { result ->
                    _uiState.update { current ->
                        when (result) {
                            is RegenerationResult.Text -> current.copy(
                                regeneratingFieldKey = null,
                                generatedTextByFieldKey = current.generatedTextByFieldKey + (field.key to result.value),
                                fieldErrors = current.fieldErrors - field.key
                            )
                            is RegenerationResult.Audio -> current.copy(
                                regeneratingFieldKey = null,
                                generatedMediaByFieldKey = current.generatedMediaByFieldKey + (field.key to GeneratedMedia.Audio(result.bytes)),
                                fieldErrors = current.fieldErrors - field.key
                            )
                            is RegenerationResult.Image -> current.copy(
                                regeneratingFieldKey = null,
                                generatedMediaByFieldKey = current.generatedMediaByFieldKey + (field.key to GeneratedMedia.Image(result.bytes, result.sourceWord)),
                                fieldErrors = current.fieldErrors - field.key
                            )
                            RegenerationResult.NoOp -> current.copy(regeneratingFieldKey = null)
                        }
                    }
                },
                onFailure = { err ->
                    Log.e(TAG, "Field regenerate failed: ${field.key}", err)
                    val message = err.message ?: "Regeneration failed"
                    _uiState.update {
                        it.copy(
                            regeneratingFieldKey = null,
                            fieldErrors = it.fieldErrors + (field.key to message),
                            errorMessage = "${field.label} — regeneration failed: $message"
                        )
                    }
                }
            )
        }
    }

    fun onSaveClicked() {
        val state = _uiState.value
        val noteType = state.selectedNoteType ?: return
        if (!state.hasGenerated) return

        if (!anki.isAnkiDroidInstalled()) {
            _uiState.update { it.copy(errorMessage = "AnkiDroid is not installed on this device.") }
            return
        }
        if (!anki.hasPermission()) {
            _uiState.update { it.copy(needsAnkiPermission = true) }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, errorMessage = null) }
            val imageBytes = state.generatedMediaByFieldKey
                .mapNotNull { (k, v) -> if (v is GeneratedMedia.Image) k to v.bytes else null }
                .toMap()
            val audioBytes = state.generatedMediaByFieldKey
                .mapNotNull { (k, v) -> if (v is GeneratedMedia.Audio) k to v.bytes else null }
                .toMap()
            // The repository's domain NoteType doesn't carry the AnkiDroid
            // model id binding (storage-only) — look it up explicitly.
            val existingModelId = noteTypes.getAnkiModelId(noteType.id)
            anki.upsertGeneratedNote(
                noteType = noteType,
                textByFieldKey = state.generatedTextByFieldKey,
                imageBytesByFieldKey = imageBytes,
                audioBytesByFieldKey = audioBytes,
                deckName = state.activeDeckName,
                existingAnkiModelId = existingModelId,
                // Produce a card only for the ticked designs (control fields
                // suppress the rest on multi-design note types).
                selectedTemplateIds = state.selectedTemplateIds
            ).onSuccess { outcome ->
                val primary = state.generatedTextByFieldKey.values.firstOrNull().orEmpty()
                deckNoteTypeChoice.setChoice(
                    state.activeDeckName, noteType.id, state.selectedTemplateIds
                )
                // Mirror the legacy behavior: if this generation was sourced
                // from a wordlist entry, drop it now that the card is safely
                // in AnkiDroid.
                sourceWordlistWord?.let { wordlist.removeByWord(it) }
                val result = when (outcome) {
                    is UpsertOutcome.Created -> SaveResult.Created(outcome.noteId, primary)
                    is UpsertOutcome.Updated -> SaveResult.Updated(outcome.noteId, primary)
                }
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        saveResult = result,
                        // Clear the card so a successful save returns the
                        // screen to a "ready for next word" state.
                        query = "",
                        generatedTextByFieldKey = emptyMap(),
                        generatedMediaByFieldKey = emptyMap(),
                        fieldErrors = emptyMap()
                    )
                }
            }.onFailure { err ->
                Log.e(TAG, "Save failed", err)
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        saveResult = SaveResult.Failed(err.message ?: "Save failed")
                    )
                }
            }
        }
    }

    fun onDismissGenerated() {
        // Cancel any in-flight generation. The launch block in
        // onGenerateClicked catches the resulting CancellationException
        // without touching state, so what the user sees is exactly the
        // state we set below.
        generationJob?.cancel()
        generationJob = null
        _uiState.update {
            it.copy(
                isGenerating = false,
                generatedTextByFieldKey = emptyMap(),
                generatedMediaByFieldKey = emptyMap(),
                fieldErrors = emptyMap(),
                regeneratingFieldKey = null
            )
        }
    }

    fun onPermissionRequestConsumed() {
        _uiState.update { it.copy(needsAnkiPermission = false) }
    }

    fun onPermissionDenied() {
        _uiState.update {
            it.copy(
                needsAnkiPermission = false,
                errorMessage = "AnkiDroid permission was denied. Grant it in Android settings."
            )
        }
    }

    fun onMessageShown() {
        _uiState.update { it.copy(errorMessage = null, saveResult = null) }
    }

    companion object {
        private const val TAG = "AnkiCardsGen"

        /**
         * Turn a pipeline [GenerationProgress.FieldError] into something a
         * human can act on. The raw message is already reasonably good —
         * `GeminiRepository.unwrapHttp` surfaces `HTTP 504: <gemini message>`
         * — it just needs to say *which* leg died. `__llm__` is the
         * pipeline's synthetic key for the whole structured-output call.
         */
        internal fun describeFieldError(
            fieldKey: String,
            message: String,
            noteType: NoteType?
        ): String {
            val label = if (fieldKey == "__llm__") {
                "Card text"
            } else {
                noteType?.fields?.firstOrNull { it.key == fieldKey }?.label ?: fieldKey
            }
            return "$label failed: $message"
        }

        fun factory(
            cardGenerator: CardGenerator,
            noteTypes: NoteTypeRepository,
            anki: AnkiDroidRepository,
            settings: SettingsRepository,
            deckLanguages: DeckLanguageRepository,
            deckNoteTypeChoice: DeckNoteTypeChoiceRepository,
            wordlist: WordlistRepository,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                CardGeneratorViewModel(
                    cardGenerator, noteTypes, anki, settings,
                    deckLanguages, deckNoteTypeChoice, wordlist
                ) as T
        }
    }
}
