package com.borderless.ankicards.ui.decks

import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.borderless.ankicards.domain.deck.DeckLanguage

/**
 * Deck picker / creator with hierarchical tree view and deck-language locking.
 *
 * AnkiDroid represents nested decks with `::`. We render a collapsible tree:
 * top-level decks first, expand to reveal children. Tapping a deck row
 * selects it for card generation — but if the deck has no assigned language
 * yet (and no ancestor with one), a picker dialog opens first so the
 * deck's language is set before it becomes the active target.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeckListScreen(
    viewModel: DeckListViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var createDialogOpen by remember { mutableStateOf(false) }

    val tree = remember(state.decks) { buildDeckTree(state.decks) }
    var expanded by remember { mutableStateOf(setOf<String>()) }
    val visibleRows = remember(tree, expanded) { flattenVisible(tree, expanded) }

    Scaffold(
        // MainScaffold already applied system-bar insets; don't double-apply.
        contentWindowInsets = WindowInsets(0),
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { createDialogOpen = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("New deck") }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Inline header — matches the generator screen. Bottom-nav
            // page, no back arrow needed.
            Text(
                text = "Decks",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp)
            )

            when {
                state.isLoading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }

                state.errorMessage != null -> Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            state.errorMessage!!,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = viewModel::refresh) { Text("Retry") }
                    }
                }

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                item {
                    Text(
                        "Tap a deck to point card generation at it. Each deck is locked " +
                            "to one language at first use. Tap the chevron to see subdecks.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                }
                items(visibleRows, key = { it.fullName }) { node ->
                    DeckRow(
                        node = node,
                        isSelected = node.fullName.equals(state.selectedDeckName, ignoreCase = true),
                        isExpanded = node.fullName in expanded,
                        language = state.resolvedLanguages[node.fullName.lowercase()],
                        onSelect = {
                            if (!node.isSynthetic) viewModel.selectDeck(node.fullName)
                        },
                        onToggleExpand = {
                            expanded = if (node.fullName in expanded) {
                                expanded - node.fullName
                            } else {
                                expanded + node.fullName
                            }
                        }
                    )
                }
                    item { Spacer(Modifier.height(80.dp)) }
                }
            }
        }
    }

    if (createDialogOpen) {
        CreateDeckDialog(
            isCreating = state.isCreating,
            errorMessage = state.createError,
            onDismiss = { createDialogOpen = false },
            onConfirm = { name, language ->
                viewModel.createDeck(name, language)
                createDialogOpen = false
            }
        )
    }

    state.pendingLanguageForDeck?.let { pendingDeck ->
        LanguagePickerDialog(
            deckName = pendingDeck,
            onDismiss = viewModel::dismissLanguagePicker,
            onPick = viewModel::confirmLanguageAndSelect
        )
    }
}

@Composable
private fun DeckRow(
    node: DeckNode,
    isSelected: Boolean,
    isExpanded: Boolean,
    language: DeckLanguage?,
    onSelect: () -> Unit,
    onToggleExpand: () -> Unit
) {
    val chevronRotation by animateFloatAsState(
        targetValue = if (isExpanded) 90f else 0f,
        label = "deck-chevron"
    )
    val container = when {
        isSelected -> MaterialTheme.colorScheme.primaryContainer
        node.isSynthetic -> MaterialTheme.colorScheme.surfaceContainerLow
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val labelColor = when {
        node.isSynthetic -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onSurface
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (node.depth * 20).dp)
            .clickable(enabled = !node.isSynthetic, onClick = onSelect),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = container)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (node.children.isNotEmpty()) {
                IconButton(
                    onClick = onToggleExpand,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.ChevronRight,
                        contentDescription = if (isExpanded) "Collapse" else "Expand",
                        modifier = Modifier.rotate(chevronRotation)
                    )
                }
            } else {
                Spacer(Modifier.size(32.dp))
            }

            Text(
                text = node.displayName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                color = labelColor,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 4.dp)
            )

            LanguageChip(language = language, isSynthetic = node.isSynthetic)

            if (isSelected) {
                Spacer(Modifier.size(6.dp))
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

/**
 * Compact pill on the right of a deck row that shows its language, or
 * "Set language" if the deck is orphan. Hidden entirely on synthetic parent
 * rows because those can't be selected (and therefore can't be assigned).
 */
@Composable
private fun LanguageChip(language: DeckLanguage?, isSynthetic: Boolean) {
    if (isSynthetic) return
    val (label, container, content) = when {
        language == null -> Triple(
            "Set language",
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer
        )
        language == DeckLanguage.Generic -> Triple(
            "Generic",
            MaterialTheme.colorScheme.surfaceContainerHighest,
            MaterialTheme.colorScheme.onSurfaceVariant
        )
        else -> Triple(
            language.displayName,
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer
        )
    }
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = container,
        contentColor = content
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateDeckDialog(
    isCreating: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onConfirm: (String, DeckLanguage) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var language by remember { mutableStateOf<DeckLanguage?>(null) }
    var dropdownExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!isCreating) onDismiss() },
        title = { Text("Create deck") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Deck name") },
                    placeholder = { Text("TEST::cantonese") },
                    singleLine = true,
                    enabled = !isCreating,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                ExposedDropdownMenuBox(
                    expanded = dropdownExpanded,
                    onExpandedChange = { if (!isCreating) dropdownExpanded = it }
                ) {
                    OutlinedTextField(
                        value = language?.displayName.orEmpty(),
                        onValueChange = { /* read-only: chosen via the menu */ },
                        readOnly = true,
                        label = { Text("Language") },
                        placeholder = { Text("Pick one — this can't be changed later") },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = dropdownExpanded)
                        },
                        enabled = !isCreating,
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = dropdownExpanded,
                        onDismissRequest = { dropdownExpanded = false }
                    ) {
                        DeckLanguage.all.forEach { option ->
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            option.displayName,
                                            modifier = Modifier.weight(1f)
                                        )
                                        if (option.code.isNotEmpty()) {
                                            Text(
                                                option.code,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                },
                                onClick = {
                                    language = option
                                    dropdownExpanded = false
                                }
                            )
                        }
                    }
                }
                if (errorMessage != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        errorMessage,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Tip: use \"::\" for nested decks (e.g. TEST::cantonese). " +
                        "Subdecks inherit their root deck's language.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val lang = language ?: return@TextButton
                    if (name.isNotBlank()) onConfirm(name, lang)
                },
                enabled = !isCreating && name.isNotBlank() && language != null
            ) {
                if (isCreating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                } else Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isCreating) { Text("Cancel") }
        }
    )
}

/**
 * Picker that appears when the user taps an existing (AnkiDroid-owned) deck
 * that has no language assigned in our local table — i.e. a deck this app
 * didn't create. New decks created through the app go through the inline
 * dropdown in [CreateDeckDialog] instead and never hit this dialog.
 */
@Composable
private fun LanguagePickerDialog(
    deckName: String,
    onDismiss: () -> Unit,
    onPick: (DeckLanguage) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("\"$deckName\" needs a language") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "This is the language the deck is for. It can't be changed later, " +
                        "and every subdeck under it inherits the same language.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                DeckLanguage.all.forEach { lang ->
                    Surface(
                        onClick = { onPick(lang) },
                        shape = RoundedCornerShape(8.dp),
                        color = if (lang == DeckLanguage.Generic)
                            MaterialTheme.colorScheme.surfaceContainerLow
                        else MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                lang.displayName,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f)
                            )
                            if (lang.code.isNotEmpty()) {
                                Text(
                                    lang.code,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
