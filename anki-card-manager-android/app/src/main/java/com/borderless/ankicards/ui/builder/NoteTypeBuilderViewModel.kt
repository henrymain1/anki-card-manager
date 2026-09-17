package com.borderless.ankicards.ui.builder

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.borderless.ankicards.data.anki.AnkiDroidRepository
import com.borderless.ankicards.data.recipe.NoteTypeRepository
import com.borderless.ankicards.domain.recipe.CardFace
import com.borderless.ankicards.domain.recipe.CardField
import com.borderless.ankicards.domain.recipe.NoteType
import com.borderless.ankicards.domain.recipe.FieldLayout
import com.borderless.ankicards.domain.recipe.FieldPlacement
import com.borderless.ankicards.domain.recipe.LanguagePresets
import com.borderless.ankicards.domain.recipe.Template
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Drives the visual card designer.
 *
 * Owns the in-progress [NoteTypeBuilderUiState] and exposes every mutation the
 * UI needs: place / move / resize / remove a field on the grid, switch which
 * face is visible, edit per-field metadata, save (which both persists locally
 * and best-effort pushes to AnkiDroid).
 *
 * The view model is the *only* place that knows about grid bounds, overlap
 * rules, and AnkiDroid round-tripping. The UI just renders state and reports
 * gestures.
 */
class NoteTypeBuilderViewModel(
    private val repo: NoteTypeRepository,
    private val anki: AnkiDroidRepository,
    noteTypeId: String?,
    templateId: String?,
    presetName: String?
) : ViewModel() {

    private val _uiState = MutableStateFlow(NoteTypeBuilderUiState(isLoading = noteTypeId != null))
    val uiState: StateFlow<NoteTypeBuilderUiState> = _uiState.asStateFlow()

    init {
        when {
            noteTypeId != null -> loadExisting(noteTypeId, templateId)
            presetName != null -> startFromPreset(presetName)
            else -> startBlank()
        }
    }

    /**
     * Load the note type [id] and focus a single design (template) for editing:
     *  - [templateId] non-null → edit that design.
     *  - [templateId] null → the user is adding a NEW design to this note type,
     *    so append an empty one and edit it.
     * The other designs are kept in state and preserved verbatim on save.
     */
    private fun loadExisting(id: String, templateId: String?) {
        viewModelScope.launch {
            val existing = repo.getById(id)
            if (existing == null) {
                startBlank()
            } else {
                val baseTemplates = existing.templates.ifEmpty { listOf(Template()) }
                val templates: List<Template>
                val editIndex: Int
                if (templateId == null) {
                    val newDesign = Template(name = nextTemplateName(baseTemplates))
                    templates = baseTemplates + newDesign
                    editIndex = templates.lastIndex
                } else {
                    templates = baseTemplates
                    editIndex = baseTemplates.indexOfFirst { it.id == templateId }.coerceAtLeast(0)
                }
                _uiState.update {
                    it.copy(
                        id = existing.id,
                        name = existing.name,
                        language = existing.language,
                        defaultImageStyle = existing.defaultImageStyle,
                        fields = existing.fields,
                        templates = templates,
                        editingTemplateIndex = editIndex,
                        isLoading = false
                    )
                }
            }
        }
    }

    private fun startFromPreset(presetName: String) {
        val factory = PRESETS_BY_NAME[presetName] ?: { LanguagePresets.generic() }
        val seed = factory()
        _uiState.update {
            it.copy(
                id = null,
                name = seed.name,
                language = seed.language,
                defaultImageStyle = seed.defaultImageStyle,
                fields = seed.fields,
                templates = seed.templates.ifEmpty { listOf(Template()) },
                editingTemplateIndex = 0,
                isLoading = false
            )
        }
    }

    private fun startBlank() {
        _uiState.update { it.copy(isLoading = false) }
    }

    // ── header mutations ─────────────────────────────────────────────────

    fun onNameChanged(value: String) = _uiState.update { it.copy(name = value) }

    /**
     * Rename the design (template) currently being edited — this is the
     * user-facing "card type name". Allows transient blank values while typing;
     * the exporter falls back to "Card N" if a design is saved nameless.
     */
    fun onDesignNameChanged(value: String) {
        _uiState.update { state ->
            val idx = state.editingTemplateIndex
            val current = state.templates.getOrNull(idx) ?: return@update state
            val newTemplates = state.templates.toMutableList().apply { this[idx] = current.copy(name = value) }
            state.copy(templates = newTemplates)
        }
    }

    fun onLanguageChanged(value: String?) = _uiState.update { it.copy(language = value) }
    fun onImageStyleChanged(value: String) = _uiState.update { it.copy(defaultImageStyle = value) }
    fun setVisibleFace(face: CardFace) = _uiState.update { it.copy(visibleFace = face) }

    // ── field-list mutations ─────────────────────────────────────────────

    fun addField(field: CardField) {
        _uiState.update { state ->
            val key = ensureUniqueKey(field.key, state.fields)
            val resolved = if (key == field.key) field else field.copy(key = key)
            state.copy(fields = state.fields + resolved)
        }
    }

    fun removeField(key: String) {
        _uiState.update {
            it.copy(
                fields = it.fields.filterNot { f -> f.key == key },
                // Purge the field from EVERY design's placements, otherwise a
                // design that still references it would dangle and fail NoteType
                // validation on save.
                templates = it.templates.map { t ->
                    t.copy(placements = t.placements.filterNot { p -> p.fieldKey == key })
                }
            )
        }
    }

    fun updateField(key: String, transform: (CardField) -> CardField) {
        _uiState.update { state ->
            state.copy(fields = state.fields.map { if (it.key == key) transform(it) else it })
        }
    }

    fun openFieldEditor(key: String) = _uiState.update { it.copy(editingFieldKey = key) }
    fun closeFieldEditor() = _uiState.update { it.copy(editingFieldKey = null) }

    // ── placement mutations ──────────────────────────────────────────────

    /**
     * Try to place [fieldKey] at grid cell ([col], [row]) on the currently
     * visible face. Width and height default to a reasonable size for the
     * field type. If the proposed rectangle would overlap an existing
     * placement on the same face, the request is ignored.
     *
     * The field must already exist in [NoteTypeBuilderUiState.fields]; this
     * method only adds a *placement*. Adding a brand-new field from the
     * palette is a two-step gesture in the UI: [addField] then [placeField].
     */
    fun placeField(fieldKey: String, col: Int, row: Int): Boolean {
        var placed = false
        updateCurrentTemplate { state, current ->
            val field = state.fields.firstOrNull { it.key == fieldKey } ?: return@updateCurrentTemplate null
            val (w, h) = defaultSizeFor(field)
            val layout = clampedLayout(col, row, w, h) ?: return@updateCurrentTemplate null
            val face = state.visibleFace
            val others = current.placements.filter { it.face == face }
            if (others.any { it.layout.overlaps(layout) }) return@updateCurrentTemplate null
            placed = true
            current.copy(placements = current.placements + FieldPlacement(fieldKey, face, layout))
        }
        return placed
    }

    /**
     * Move an existing placement to a new top-left grid cell on the same face
     * of the currently-edited design. The target is clamped in-bounds; if it
     * would overlap another placement on the same face the move is rejected
     * (the box stays put) — overlap is a hard invariant (see [NoteType.init]).
     */
    fun moveField(fieldKey: String, newCol: Int, newRow: Int): Boolean {
        var moved = false
        updateCurrentTemplate { state, current ->
            val idx = current.placements.indexOfFirst {
                it.fieldKey == fieldKey && it.face == state.visibleFace
            }
            if (idx < 0) return@updateCurrentTemplate null
            val placement = current.placements[idx]
            val layout = clampedLayout(newCol, newRow, placement.layout.w, placement.layout.h)
                ?: return@updateCurrentTemplate null
            val others = current.placements.filterIndexed { i, p -> i != idx && p.face == placement.face }
            if (others.any { it.layout.overlaps(layout) }) return@updateCurrentTemplate null
            moved = true
            val updated = current.placements.toMutableList()
            updated[idx] = placement.copy(layout = layout)
            current.copy(placements = updated)
        }
        return moved
    }

    /**
     * Resize the placement of [fieldKey] on the visible face of the
     * currently-edited design to ([w], [h]) grid cells, top-left anchored.
     */
    fun resizeField(fieldKey: String, w: Int, h: Int): Boolean {
        var resized = false
        updateCurrentTemplate { state, current ->
            val idx = current.placements.indexOfFirst {
                it.fieldKey == fieldKey && it.face == state.visibleFace
            }
            if (idx < 0) return@updateCurrentTemplate null
            val placement = current.placements[idx]
            val layout = clampedLayout(placement.layout.col, placement.layout.row, w, h)
                ?: return@updateCurrentTemplate null
            val others = current.placements.filterIndexed { i, p -> i != idx && p.face == placement.face }
            if (others.any { it.layout.overlaps(layout) }) return@updateCurrentTemplate null
            resized = true
            val updated = current.placements.toMutableList()
            updated[idx] = placement.copy(layout = layout)
            current.copy(placements = updated)
        }
        return resized
    }

    fun removePlacement(fieldKey: String) {
        updateCurrentTemplate { state, current ->
            current.copy(
                placements = current.placements.filterNot {
                    it.fieldKey == fieldKey && it.face == state.visibleFace
                }
            )
        }
    }

    /**
     * Apply [transform] to the currently-edited design. The transform receives
     * the current [state] and the design being edited, and returns the new
     * design — or null to make no change (e.g. an overlap was rejected).
     */
    private fun updateCurrentTemplate(
        transform: (state: NoteTypeBuilderUiState, current: Template) -> Template?
    ) {
        _uiState.update { state ->
            val idx = state.editingTemplateIndex
            val current = state.templates.getOrNull(idx) ?: return@update state
            val updated = transform(state, current) ?: return@update state
            val newTemplates = state.templates.toMutableList().apply { this[idx] = updated }
            state.copy(templates = newTemplates)
        }
    }

    // ── design (template) helpers ────────────────────────────────────────
    //
    // Switching / adding / duplicating / deleting designs is done from the
    // Designs list (a design = a top-level "card type" there), not in the
    // designer — the designer edits exactly one design. So those actions live
    // in NoteTypeListViewModel, not here.

    private fun nextTemplateName(existing: List<Template>): String {
        val taken = existing.map { it.name }.toSet()
        var n = existing.size + 1
        while ("Card $n" in taken) n++
        return "Card $n"
    }

    // ── save ─────────────────────────────────────────────────────────────

    fun save() {
        viewModelScope.launch {
            val state = _uiState.value
            _uiState.update {
                it.copy(isSaving = true, saveError = null, ankiPushStatus = AnkiPushStatus.Idle)
            }
            val noteType = try {
                // state.templates is the canonical set of designs the user has
                // been editing; persist it directly.
                NoteType(
                    id = state.id ?: UUID.randomUUID().toString(),
                    name = state.name.trim().ifBlank { "Untitled card" },
                    language = state.language?.takeIf { it.isNotBlank() },
                    defaultImageStyle = state.defaultImageStyle,
                    fields = state.fields,
                    templates = state.templates.ifEmpty { listOf(Template()) }
                )
            } catch (e: IllegalArgumentException) {
                _uiState.update { it.copy(isSaving = false, saveError = e.message) }
                return@launch
            }
            repo.upsert(noteType)
            _uiState.update {
                it.copy(id = noteType.id, isSaving = false, justSaved = true)
            }

            // Best-effort AnkiDroid push: errors are surfaced inline but don't
            // roll back the local save.
            pushToAnki(noteType)
        }
    }

    private suspend fun pushToAnki(noteType: NoteType) {
        if (!anki.isAnkiDroidInstalled()) {
            _uiState.update {
                it.copy(ankiPushStatus = AnkiPushStatus.Skipped("AnkiDroid is not installed."))
            }
            return
        }
        if (!anki.hasPermission()) {
            _uiState.update {
                it.copy(ankiPushStatus = AnkiPushStatus.Skipped("AnkiDroid permission not granted."))
            }
            return
        }
        _uiState.update { it.copy(ankiPushStatus = AnkiPushStatus.InProgress) }
        val existing = repo.getAnkiModelId(noteType.id)
        anki.pushNoteType(noteType, existing).fold(
            onSuccess = { modelId ->
                if (existing == null) repo.setAnkiModelId(noteType.id, modelId)
                _uiState.update { it.copy(ankiPushStatus = AnkiPushStatus.Success(modelId)) }
            },
            onFailure = { err ->
                _uiState.update {
                    it.copy(ankiPushStatus = AnkiPushStatus.Failed(err.message ?: "AnkiDroid push failed"))
                }
            }
        )
    }

    fun consumeJustSaved() {
        _uiState.update { it.copy(justSaved = false) }
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private fun ensureUniqueKey(desired: String, existing: List<CardField>): String {
        val taken = existing.map { it.key }.toSet()
        if (desired !in taken) return desired
        var n = 2
        while ("${desired}_$n" in taken) n++
        return "${desired}_$n"
    }

    private fun clampedLayout(col: Int, row: Int, w: Int, h: Int): FieldLayout? {
        val cols = FieldLayout.GRID_COLS
        val rows = FieldLayout.GRID_ROWS
        val clampedW = w.coerceIn(MIN_W, cols)
        val clampedH = h.coerceIn(MIN_H, rows)
        val clampedCol = col.coerceIn(0, cols - clampedW)
        val clampedRow = row.coerceIn(0, rows - clampedH)
        return runCatching { FieldLayout(clampedCol, clampedRow, clampedW, clampedH) }.getOrNull()
    }

    /** Reasonable default rectangle for a freshly-dropped field, by type. */
    private fun defaultSizeFor(field: CardField): Pair<Int, Int> = when (field.generator) {
        is com.borderless.ankicards.domain.recipe.FieldGenerator.ImageGen -> 8 to 8
        is com.borderless.ankicards.domain.recipe.FieldGenerator.Tts -> 4 to 2
        else -> 10 to 2
    }

    companion object {
        private const val MIN_W = 2
        private const val MIN_H = 1

        private val PRESETS_BY_NAME: Map<String, () -> NoteType> = mapOf(
            "cantonese" to LanguagePresets::cantonese,
            "mandarin" to LanguagePresets::mandarin,
            "japanese" to LanguagePresets::japanese,
            "thai" to LanguagePresets::thai,
            "spanish" to LanguagePresets::spanish,
            "french" to LanguagePresets::french,
            "german" to LanguagePresets::german,
            "italian" to LanguagePresets::italian,
            "generic" to LanguagePresets::generic
        )

        fun factory(
            repo: NoteTypeRepository,
            anki: AnkiDroidRepository,
            noteTypeId: String?,
            templateId: String?,
            presetName: String?
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                NoteTypeBuilderViewModel(repo, anki, noteTypeId, templateId, presetName) as T
        }
    }
}
