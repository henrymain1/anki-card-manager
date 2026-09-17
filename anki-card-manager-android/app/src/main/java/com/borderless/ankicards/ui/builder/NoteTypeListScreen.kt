package com.borderless.ankicards.ui.builder

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.borderless.ankicards.domain.deck.DeckLanguage
import com.borderless.ankicards.domain.recipe.NoteType
import com.borderless.ankicards.ui.common.MiniCardPreview

/**
 * Lists every card type the user has on this device with a thumbnail preview
 * of the front face. Each row shows the card type's language and the decks
 * it's compatible with, so the relationship between Designs and Decks is
 * visible without having to dig through the visual designer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteTypeListScreen(
    viewModel: NoteTypeListViewModel,
    onBack: () -> Unit,
    onEditDesign: (noteTypeId: String, templateId: String) -> Unit,
    onAddDesign: (noteTypeId: String) -> Unit,
    onCreateBlank: () -> Unit,
    onCreateFromPreset: (presetName: String) -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var newSheetOpen by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    // Surface delete-completed messages as a snackbar, then clear them so
    // they don't fire again on recomposition.
    LaunchedEffect(state.transientMessage) {
        val msg = state.transientMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        viewModel.clearTransientMessage()
    }

    val visibleDesigns = remember(state) {
        if (state.showAll) state.designs
        else state.designs.filter { d ->
            d.noteTypeId in state.compatibleDeckNamesByNoteType.keys &&
                state.activeDeckName in (state.compatibleDeckNamesByNoteType[d.noteTypeId] ?: emptyList())
        }
    }

    Scaffold(
        // MainScaffold already applied system-bar insets; don't double-apply.
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    // Add a new design to the active deck's language note type.
                    // If that language has no note type yet, fall back to the
                    // create-from-preset sheet to make one.
                    val id = state.activeLanguageNoteTypeId
                    if (id != null) onAddDesign(id) else newSheetOpen = true
                },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("New") }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                // Inline header — matches the generator screen. Bottom-nav
                // page, no back arrow needed.
                Text(
                    text = "Designs",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
            item {
                ActiveDeckBanner(
                    activeDeckName = state.activeDeckName,
                    activeDeckLanguage = state.activeDeckLanguage,
                    showAll = state.showAll,
                    onShowAllChange = viewModel::setShowAll
                )
            }
            if (visibleDesigns.isEmpty()) {
                item {
                    Text(
                        text = if (!state.showAll && state.designs.isNotEmpty())
                            "No card types match the active deck's language. " +
                                "Flip \"Show all\" to see every card type."
                        else "No card types yet. Tap New to create one.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp)
                    )
                }
            } else {
                items(visibleDesigns, key = { it.noteTypeId + "/" + it.template.id }) { design ->
                    DesignTile(
                        design = design,
                        onClick = { onEditDesign(design.noteTypeId, design.template.id) },
                        onDelete = { viewModel.deleteDesign(design.noteTypeId, design.template.id) }
                    )
                }
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
    }

    if (newSheetOpen) {
        NewNoteTypeSheet(
            activeLanguage = state.activeDeckLanguage,
            onDismiss = { newSheetOpen = false },
            onBlank = {
                newSheetOpen = false
                onCreateBlank()
            },
            onPreset = { preset ->
                newSheetOpen = false
                onCreateFromPreset(preset)
            }
        )
    }

    val pending = state.pendingDelete
    if (pending != null) {
        AlertDialog(
            onDismissRequest = { if (!pending.deleting) viewModel.cancelDelete() },
            title = { Text("Delete \"${pending.noteTypeName}\"?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    when {
                        // Card type was never pushed to AnkiDroid — nothing
                        // on that side to clean up.
                        pending.ankiModelId == null -> Text(
                            "This card type was never saved to AnkiDroid, so nothing in AnkiDroid will change."
                        )
                        // We have a model but couldn't read the note count.
                        // Be explicit instead of pretending it's zero.
                        pending.ankiNoteCount == null -> Text(
                            "Couldn't read how many AnkiDroid notes use this card type. " +
                                "Deleting will still try to remove every note of this type — " +
                                "their review history will be lost."
                        )
                        pending.ankiNoteCount == 0 -> Text(
                            "No AnkiDroid notes use this card type, so nothing else will be deleted."
                        )
                        else -> Text(
                            "${pending.ankiNoteCount} note${if (pending.ankiNoteCount == 1) "" else "s"} " +
                                "in AnkiDroid use this card type. " +
                                "They and their review history will be deleted. This can't be undone."
                        )
                    }
                    if (pending.ankiModelId != null) {
                        Text(
                            "Note: AnkiDroid's API doesn't let us delete the empty note type itself. " +
                                "It'll still show in AnkiDroid → Manage Note Types until you remove it there.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.confirmDelete() },
                    enabled = !pending.deleting
                ) {
                    Text(if (pending.deleting) "Deleting…" else "Delete")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { viewModel.cancelDelete() },
                    enabled = !pending.deleting
                ) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun ActiveDeckBanner(
    activeDeckName: String,
    activeDeckLanguage: DeckLanguage?,
    showAll: Boolean,
    onShowAllChange: (Boolean) -> Unit
) {
    Card(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Active deck",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                    )
                    Text(
                        text = if (activeDeckName.isBlank()) "(none selected)" else activeDeckName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Text(
                        text = activeDeckLanguage?.displayName
                            ?: "No language assigned — pick one in the Decks tab.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f)
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        "Show all",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Switch(checked = showAll, onCheckedChange = onShowAllChange)
                }
            }
        }
    }
}

@Composable
private fun DesignTile(
    design: DesignRow,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MiniCardPreview(
                noteType = design.noteType,
                template = design.template,
                widthDp = 56
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = design.template.name.ifBlank { "Untitled card type" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    LanguageChip(code = design.noteType.language)
                }
                Text(
                    text = "${design.noteType.fields.size} fields",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(2.dp))
                CompatibleDecksLine(design.compatibleDecks)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete")
            }
        }
    }
}

@Composable
private fun LanguageChip(code: String?) {
    val resolved = DeckLanguage.fromCode(code)
    val (label, container, content) = when {
        code.isNullOrBlank() -> Triple(
            "Generic",
            MaterialTheme.colorScheme.surfaceContainerHighest,
            MaterialTheme.colorScheme.onSurfaceVariant
        )
        else -> Triple(
            resolved.displayName,
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer
        )
    }
    Surface(shape = RoundedCornerShape(6.dp), color = container, contentColor = content) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

@Composable
private fun CompatibleDecksLine(decks: List<String>) {
    val display = when {
        decks.isEmpty() -> "Compatible with: no decks yet"
        decks.size <= 3 -> "Compatible with: ${decks.joinToString(", ")}"
        else -> "Compatible with: ${decks.take(2).joinToString(", ")}, +${decks.size - 2} more"
    }
    Text(
        text = display,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewNoteTypeSheet(
    activeLanguage: DeckLanguage?,
    onDismiss: () -> Unit,
    onBlank: () -> Unit,
    onPreset: (String) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Show only ONE preset — the one matching the active deck's language —
    // plus the from-scratch escape hatch. Previously this sheet listed all
    // six language presets every time, which made the choice "what
    // language?" again even though the user had already picked a deck.
    // Falling back to Generic when no language is set (no deck selected,
    // or active deck is itself Generic).
    val preset = presetForLanguage(activeLanguage)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                "New card type",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            PresetButton(preset.title, preset.subtitle) { onPreset(preset.key) }
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onBlank, modifier = Modifier.fillMaxWidth()) {
                Text("Or start from scratch")
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/**
 * The one preset the New-Card-Type sheet should offer, given the active
 * deck's language. Keep field-list snippets in the subtitle short (3–4
 * fields) — they're flavor, not exhaustive listings.
 */
private data class PresetOption(val key: String, val title: String, val subtitle: String)

private fun presetForLanguage(language: DeckLanguage?): PresetOption =
    when (language) {
        DeckLanguage.Cantonese -> PresetOption(
            "cantonese", "Cantonese", "yue · English, Word, Jyutping, Image…"
        )
        DeckLanguage.Mandarin -> PresetOption(
            "mandarin", "Mandarin", "cmn · English, Word, Pinyin, Image…"
        )
        DeckLanguage.Japanese -> PresetOption(
            "japanese", "Japanese", "ja · English, Word, Furigana, Image…"
        )
        DeckLanguage.Spanish -> PresetOption(
            "spanish", "Spanish", "es · English, Word, Gender, Image…"
        )
        DeckLanguage.French -> PresetOption(
            "french", "French", "fr · English, Word, Gender, Image…"
        )
        DeckLanguage.German -> PresetOption(
            "german", "German", "de · English, Word, Gender, Image…"
        )
        DeckLanguage.Italian -> PresetOption(
            "italian", "Italian", "it · English, Word, Gender, Image…"
        )
        DeckLanguage.Thai -> PresetOption(
            "thai", "Thai", "th · English, Word, Romanization, Image…"
        )
        DeckLanguage.Generic, null -> PresetOption(
            "generic", "Generic", "English, Word, Example, Image"
        )
    }

@Composable
private fun PresetButton(title: String, subtitle: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
        )
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
