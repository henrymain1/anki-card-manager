package com.borderless.ankicards.ui.wordlist

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.borderless.ankicards.data.wordlist.WordlistItem
import com.borderless.ankicards.ui.common.MarkdownText
import com.borderless.ankicards.ui.decoration.TriangleMeshBackground

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WordlistScreen(
    viewModel: WordlistViewModel,
    onBack: () -> Unit,
    onGenerateCard: (word: String) -> Unit
) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(ui.errorMessage) {
        ui.errorMessage?.let {
            snackbar.showSnackbar(it)
            viewModel.onMessageShown()
        }
    }

    TriangleMeshBackground {
        Scaffold(
            containerColor = Color.Transparent,
            // Outer MainScaffold already applied the system-bar insets; zero
            // them here so the top inset isn't added twice (the extra-top-margin
            // bug). Matches the generator screen.
            contentWindowInsets = WindowInsets(0),
            snackbarHost = { SnackbarHost(snackbar) }
        ) { padding ->
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
            ) {
                // Inline header — back arrow on the left, title next to it.
                // Same vertical-positioning as the generator screen so the
                // wordlist title no longer sits ~88dp below the status bar
                // (the old TopAppBar's height).
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp, end = 16.dp, top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                    Text(
                        text = "Wordlist",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                // Reapply the horizontal page-edge padding here so the body
                // content stays where it used to be; the header above has
                // its own narrower start padding to align with the back arrow.
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = ui.input,
                        onValueChange = viewModel::onInputChanged,
                        label = { Text("Add a word") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        // Intentionally do NOT hide the keyboard here — keep it
                        // open so the user can add several words in a row. The
                        // input clears and focus stays in the field.
                        keyboardActions = KeyboardActions(onDone = {
                            viewModel.onAddClicked()
                        }),
                        modifier = Modifier.weight(1f)
                    )
                    FilledTonalIconButton(
                        onClick = {
                            keyboard?.hide()
                            viewModel.onAddClicked()
                        },
                        enabled = ui.input.isNotBlank()
                    ) {
                        Icon(Icons.Filled.Add, "Add word")
                    }
                }

                Spacer(Modifier.height(12.dp))

                if (items.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "No words yet. Add one above to get started.",
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 4.dp)
                    ) {
                        items(items, key = { it.id }) { item ->
                            WordRow(
                                item = item,
                                isExplaining = item.id in ui.explainingIds,
                                expanded = item.id in ui.expandedIds,
                                onToggleExpanded = { viewModel.onToggleExpanded(item.id) },
                                onExplain = { viewModel.onExplainClicked(item.id, item.word) },
                                onGenerate = { onGenerateCard(item.word) },
                                onRemove = { viewModel.onRemoveClicked(item.id) }
                            )
                        }
                    }
                }
                } // end inner Column (page-edge padding)
            } // end outer Column
        } // end Scaffold content lambda
    } // end TriangleMeshBackground
} // end WordlistScreen

@Composable
private fun WordRow(
    item: WordlistItem,
    isExplaining: Boolean,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onExplain: () -> Unit,
    onGenerate: () -> Unit,
    onRemove: () -> Unit
) {
    val borderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
    val hasExplanation = !item.explanation.isNullOrBlank()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f))
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = item.word,
                modifier = Modifier
                    .weight(1f)
                    .clickable(enabled = hasExplanation) { onToggleExpanded() },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            if (hasExplanation) {
                IconButton(onClick = onToggleExpanded) {
                    Icon(
                        imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (expanded) "Collapse explanation" else "Expand explanation"
                    )
                }
            }
            IconButton(onClick = onExplain, enabled = !isExplaining) {
                if (isExplaining) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(Icons.Filled.Bolt, contentDescription = "Explain")
                }
            }
            IconButton(onClick = onGenerate) {
                Icon(Icons.Filled.PlayArrow, contentDescription = "Generate card")
            }
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Remove",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }

        AnimatedVisibility(visible = hasExplanation && expanded) {
            MarkdownText(
                markdown = item.explanation.orEmpty(),
                modifier = Modifier.padding(top = 8.dp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f)
            )
        }
    }
}

