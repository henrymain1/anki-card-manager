package com.borderless.ankicards.ui.generator

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.Crossfade
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.animateBounds
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.borderless.ankicards.data.anki.FlashCardsContract
import com.borderless.ankicards.data.queue.QueueRepository
import com.borderless.ankicards.domain.deck.DeckLanguage
import com.borderless.ankicards.domain.recipe.CardField
import com.borderless.ankicards.domain.recipe.NoteType
import com.borderless.ankicards.domain.recipe.Template
import com.borderless.ankicards.domain.recipe.FieldGenerator
import com.borderless.ankicards.data.settings.LoaderStylePreference
import com.borderless.ankicards.ui.common.CardGenerationLoader
import com.borderless.ankicards.ui.common.CardLoaderStyle
import com.borderless.ankicards.ui.common.GameButton
import com.borderless.ankicards.ui.common.GeneratedCardWebSurface
import com.borderless.ankicards.ui.common.GeneratedFieldEditorSheet
import com.borderless.ankicards.ui.common.MiniCardPreview
// `TriangleMeshBackground` (ui/decoration/TriangleMeshBackground.kt) is kept
// around but no longer wraps this screen — clean UI request. Re-import and
// wrap the Scaffold to bring the purple mesh back.

/**
 * Phase 2 generator screen.
 *
 * Three sections stacked vertically:
 *  1. Header — active deck + language chip + card-type picker (horizontal scroll
 *     of MiniCardPreview thumbnails).
 *  2. Query row — text field + Generate button.
 *  3. Output panel — one row per field in the selected card type, populated
 *     with the generated values. Each row has a regenerate button; the
 *     final row has Save / Dismiss buttons.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardGeneratorScreen(
    viewModel: CardGeneratorViewModel,
    queueRepository: QueueRepository,
    onNavigateToStaging: () -> Unit,
    onNavigateToWordlist: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val queueItems by queueRepository.items.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    // The VM now subscribes to settings.deckName + noteTypes.observeAll()
    // continuously, so adds/edits/deck-switches in other tabs propagate
    // automatically. No per-visit refresh needed — and tapping the Generate
    // tab while already on it no longer causes a visible re-assert of state
    // (the old `LaunchedEffect(Unit) { refreshContext() }` was responsible
    // for that "refresh" appearance).

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.onSaveClicked() else viewModel.onPermissionDenied()
    }

    LaunchedEffect(state.needsAnkiPermission) {
        if (state.needsAnkiPermission) {
            viewModel.onPermissionRequestConsumed()
            permissionLauncher.launch(FlashCardsContract.PERMISSION_READ_WRITE)
        }
    }

    // Surface error + save-result messages as snackbars.
    LaunchedEffect(state.errorMessage, state.saveResult) {
        state.errorMessage?.let {
            // Indefinite + explicitly dismissable: generation errors carry a
            // real diagnostic payload ("Image failed: HTTP 404: Publisher
            // Model … was not found") that's worth reading properly, so it
            // stays until the user taps the ✕ rather than timing out.
            snackbarHostState.showSnackbar(
                message = it,
                withDismissAction = true,
                duration = SnackbarDuration.Indefinite
            )
            viewModel.onMessageShown()
        }
        when (val r = state.saveResult) {
            is SaveResult.Created -> {
                snackbarHostState.showSnackbar("Added \"${r.cardLabel}\"")
                viewModel.onMessageShown()
            }
            is SaveResult.Updated -> {
                snackbarHostState.showSnackbar("Updated \"${r.cardLabel}\"")
                viewModel.onMessageShown()
            }
            is SaveResult.Failed -> {
                snackbarHostState.showSnackbar("Save failed: ${r.message}")
                viewModel.onMessageShown()
            }
            null -> Unit
        }
    }

    // Which field's editor sheet is currently open (local UI state — value
    // edits and regenerate calls flow back to the VM normally).
    var editingFieldKey by remember { mutableStateOf<String?>(null) }

    // Decorative TriangleMeshBackground was wrapping this scope; removed for
    // a cleaner UI. The component itself still lives at
    // `ui/decoration/TriangleMeshBackground.kt` — wrap this Scaffold in it
    // again any time you want the purple mesh back.
    //
    // `contentWindowInsets = WindowInsets(0)` is critical: the OUTER
    // MainScaffold has already applied the status-bar inset to the area we
    // render into. If this nested Scaffold kept its default
    // `WindowInsets.systemBars`, the inset would be added a second time,
    // pushing content down by another status-bar height.
    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                // Inline header: title on the left, wordlist + inbox icons
                // on the right. Lightweight replacement for a full TopAppBar.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Card Generator",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onNavigateToWordlist) {
                            Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Wordlist")
                        }
                        IconButton(onClick = onNavigateToStaging) {
                            BadgedBox(
                                badge = {
                                    if (queueItems.isNotEmpty()) {
                                        Badge { Text(queueItems.size.toString()) }
                                    }
                                }
                            ) {
                                Icon(Icons.Filled.Inbox, contentDescription = "Inbox")
                            }
                        }
                    }
                }
                GeneratorBody(
                    state = state,
                    onQueryChanged = viewModel::onQueryChanged,
                    onSubmit = {
                        keyboard?.hide()
                        focusManager.clearFocus()
                        viewModel.onGenerateClicked()
                    },
                    onDesignToggled = viewModel::onDesignToggled,
                    onFieldClicked = { field -> editingFieldKey = field.key },
                    onRegenerateField = viewModel::onRegenerateField,
                    onSave = viewModel::onSaveClicked,
                    onDismiss = viewModel::onDismissGenerated,
                    onSelectDeck = viewModel::selectDeck,
                    onDeckPickerOpened = viewModel::onDeckPickerOpened,
                    padding = PaddingValues(0.dp)
                )
            }
        }

    val editingKey = editingFieldKey
    if (editingKey != null) {
        val field = state.selectedNoteType?.fields?.firstOrNull { it.key == editingKey }
        if (field != null) {
            GeneratedFieldEditorSheet(
                field = field,
                textValue = state.generatedTextByFieldKey[editingKey].orEmpty(),
                media = state.generatedMediaByFieldKey[editingKey],
                errorMessage = state.fieldErrors[editingKey],
                isRegenerating = state.regeneratingFieldKey == editingKey,
                onTextChanged = { v -> viewModel.onFieldEdited(editingKey, v) },
                onRegenerate = { viewModel.onRegenerateField(field) },
                onDismiss = { editingFieldKey = null }
            )
        }
    }
}

@Composable
private fun GeneratorBody(
    state: GeneratorUiState,
    onQueryChanged: (String) -> Unit,
    onSubmit: () -> Unit,
    onDesignToggled: (String) -> Unit,
    onFieldClicked: (CardField) -> Unit,
    onRegenerateField: (CardField) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
    onSelectDeck: (String) -> Unit,
    onDeckPickerOpened: () -> Unit,
    padding: PaddingValues
) {
    // Header (deck banner + picker/input morph area) lives ABOVE the LazyColumn
    // so it never gets disposed by lazy-list virtualization. This is critical
    // for two reasons:
    //   1. The LookaheadScope + animateBounds + movableContentOf state inside
    //      PickerAndInputArea would otherwise reset every time the item scrolls
    //      offscreen and back, re-triggering the morph animation.
    //   2. Putting the morph inside a LazyColumn item meant the item's measured
    //      height changed instantly when the layout flipped from Column to
    //      Row, while the bar's drawn position was still spring-interpolating.
    //      The preview below would snap up while the bar trailed mid-air,
    //      visually overlapping. Pulling the header out lets the bar own its
    //      own bounds without interfering with the scroll content.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            DeckHeader(
                deckName = state.activeDeckName,
                language = state.activeDeckLanguage,
                availableDecks = state.availableDecks,
                onSelectDeck = onSelectDeck,
                onPickerOpened = onDeckPickerOpened
            )
            PickerAndInputArea(
                state = state,
                onDesignToggled = onDesignToggled,
                onQueryChanged = onQueryChanged,
                onSubmit = onSubmit
            )
        }

        // Result area scrolls independently below the fixed header.
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (state.hasGenerated || state.isGenerating) {
                val noteType = state.selectedNoteType
                if (noteType != null) {
                    item("preview") {
                        // 450ms delay matches the ~400ms bar morph + a bit of
                        // breathing room, so the preview only starts landing
                        // after the bar has settled at its compact position.
                        //
                        // initialState = state.hasGenerated: on the FIRST
                        // generation, hasGenerated is false at the moment
                        // this item enters the LazyColumn (state.isGenerating
                        // just became true), so the animation plays. On
                        // navigation back to this tab after a generation has
                        // completed, hasGenerated is already true — initialize
                        // visible=true so the preview is on screen immediately
                        // instead of replaying the 750ms entry while the user
                        // wonders where their card went.
                        val previewVisible = remember {
                            MutableTransitionState(state.hasGenerated).apply {
                                targetState = true
                            }
                        }
                        AnimatedVisibility(
                            visibleState = previewVisible,
                            enter = fadeIn(tween(durationMillis = 320, delayMillis = 450)) +
                                slideInVertically(tween(durationMillis = 380, delayMillis = 450)) {
                                    it / 6
                                }
                        ) {
                            // Crossfade smooths the loader → surface swap. A
                            // hard if/else was visibly jarring: the loader
                            // would vanish, then a beat of nothing while the
                            // WebView instantiated and laid out the first
                            // page, then the card appeared. With Crossfade,
                            // both children are alive during the 600ms
                            // transition — the loader's exit fade overlaps
                            // the WebView's first-paint work, so by the time
                            // the loader is gone the card has already drawn.
                            // 600ms is deliberately a touch longer than a
                            // cold WebView first paint typically takes
                            // (~300-450ms) on a mid-range phone.
                            Crossfade(
                                targetState = state.hasGenerated && !state.isGenerating,
                                animationSpec = tween(durationMillis = 600),
                                label = "preview-content"
                            ) { showSurface ->
                                if (showSurface) {
                                    GeneratedCardWebSurface(
                                        noteType = noteType,
                                        textByFieldKey = state.generatedTextByFieldKey,
                                        mediaByFieldKey = state.generatedMediaByFieldKey,
                                        fieldErrors = state.fieldErrors,
                                        regeneratingFieldKey = state.regeneratingFieldKey,
                                        onFieldClicked = onFieldClicked,
                                        onFieldRegenerate = onRegenerateField
                                    )
                                } else {
                                    Column(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        // Resolve the user's Settings preference
                                        // into a concrete style. "Surprise me"
                                        // (Random) re-rolls per generation: the
                                        // remembered pick is stable for the life
                                        // of this loader, then a new generation
                                        // re-enters this branch and re-rolls.
                                        val randomStyle = remember { CardLoaderStyle.entries.random() }
                                        val loaderStyle = when (state.loaderStyle) {
                                            LoaderStylePreference.Orb -> CardLoaderStyle.Orb
                                            LoaderStylePreference.Sparkles -> CardLoaderStyle.Sparkles
                                            LoaderStylePreference.Random -> randomStyle
                                        }
                                        CardGenerationLoader(style = loaderStyle)
                                        // Progressive view of streaming LLM
                                        // output. Each row fades in the moment
                                        // its value lands in state.
                                        StreamingFieldsPreview(
                                            noteType = noteType,
                                            textByFieldKey = state.generatedTextByFieldKey,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(top = 12.dp, start = 24.dp, end = 24.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                item("actions") {
                    // Render the Save/Cancel row immediately whenever the
                    // outer condition has put this item in the list (i.e.,
                    // generation is in flight or finished). The previous
                    // delayed AnimatedVisibility made the buttons appear ~half
                    // a second after Generate was tapped, which felt like a
                    // missing affordance during the loader phase — the user
                    // had nothing to tap to cancel until the buttons faded
                    // in. Now Cancel is available the instant the loader is.
                    SaveDismissActions(
                        isSaving = state.isSaving,
                        isGenerating = state.isGenerating,
                        canSave = state.hasGenerated && !state.isGenerating,
                        onSave = onSave,
                        onDismiss = onDismiss
                    )
                }
            } else {
                item("empty-state") {
                    Text(
                        text = "Pick a card type, type a word, and tap Generate.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
            item { Spacer(Modifier.height(48.dp)) }
        }
    }
}

@Composable
private fun DeckHeader(
    deckName: String,
    language: DeckLanguage?,
    availableDecks: List<DeckOption>,
    onSelectDeck: (String) -> Unit,
    onPickerOpened: () -> Unit
) {
    // Wrap the banner in a Box so the DropdownMenu anchors below it (the
    // menu uses its parent's bounds for placement). The Card itself stays
    // visually identical, just gains a click + caret indicator.
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth()) {
        Card(
            shape = RoundedCornerShape(10.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
            ),
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    onPickerOpened()
                    expanded = true
                }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Writing to",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = if (deckName.isBlank()) "(no deck selected)" else deckName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                if (language != null) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    ) {
                        Text(
                            text = language.displayName,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                }
                // Caret affordance — telegraphs that the banner is tappable.
                Icon(
                    imageVector = Icons.Filled.ArrowDropDown,
                    contentDescription = "Change deck",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            if (availableDecks.isEmpty()) {
                DropdownMenuItem(
                    text = { Text("No decks yet — create one in Decks tab") },
                    onClick = { expanded = false },
                    enabled = false
                )
            } else {
                availableDecks.forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = option.name,
                                    modifier = Modifier.weight(1f),
                                    fontWeight = if (option.name == deckName) FontWeight.SemiBold
                                                 else FontWeight.Normal
                                )
                                if (option.language != null && option.language != DeckLanguage.Generic) {
                                    Spacer(Modifier.width(8.dp))
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = MaterialTheme.colorScheme.secondaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                                    ) {
                                        Text(
                                            text = option.language.displayName,
                                            style = MaterialTheme.typography.labelSmall,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                        },
                        leadingIcon = if (option.name == deckName) {
                            {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        } else null,
                        onClick = {
                            if (option.name != deckName) onSelectDeck(option.name)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun NoteTypePicker(
    designs: List<DesignOption>,
    selectedIds: Set<String>,
    isContextLoaded: Boolean,
    onToggle: (String) -> Unit
) {
    Column {
        Text(
            text = "Card types",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        // Reserve the natural height of a populated thumbnail row so the input
        // bar below doesn't jump down when designs finish loading. Tile ≈ 172dp;
        // 180dp gives ~8dp buffer for font scaling.
        Box(modifier = Modifier.heightIn(min = 180.dp)) {
            when {
                // Initial frame before the VM's Flow combine has emitted —
                // render nothing so we don't flash an empty-state message
                // before the real list arrives.
                !isContextLoaded -> Unit
                designs.isEmpty() -> Text(
                    text = "No card types yet. Make one in the Designs tab.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                else -> LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(designs, key = { it.template.id }) { option ->
                        val template = option.template
                        val isSelected = template.id in selectedIds
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .width(72.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                    else Color.Transparent
                                )
                                .clickable { onToggle(template.id) }
                                .padding(6.dp)
                        ) {
                            MiniCardPreview(
                                noteType = option.noteType,
                                template = template,
                                widthDp = 60,
                                modifier = Modifier.clip(RoundedCornerShape(6.dp))
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = template.name.ifBlank { "Card" },
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 2,
                                modifier = Modifier.fillMaxWidth(),
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                            )
                            Spacer(Modifier.height(4.dp))
                            OutlinedButton(
                                onClick = { onToggle(template.id) },
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    if (isSelected) "Selected" else "Add",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                } // end LazyRow lambda
            } // end when
        } // end Box
    } // end Column
}

/**
 * Card-type picker + query input area that *physically morphs* between two
 * layouts as `isCompact` flips:
 *
 *  - **Expanded** (before any generation): horizontal scroll of card-type
 *    thumbnails on top, full-width word input + Generate button below.
 *  - **Compact** (during/after generation): the selected card-type collapses
 *    to a small thumbnail on the left, with the input + Generate stacked to
 *    its right.
 *
 * The morph uses **LookaheadScope + animateBounds**. Each animating element
 * tracks its position and size between the two configurations and interpolates
 * smoothly with a spring. The input column is wrapped in `movableContentOf`
 * so its composition identity (and the TextField's state) is preserved as it
 * moves from "below picker" to "right of thumbnail" — without that, Compose
 * would unmount and remount it, breaking the bounds animation.
 *
 * The picker → thumbnail swap can't be a single morph (the two are entirely
 * different composables). They get a fade transition instead, but the visible
 * "motion" — the input bar resizing and moving — uses a real layout
 * interpolation, which is what makes it feel active rather than refreshed.
 */
/**
 * Card-type picker + query input area that physically morphs between two
 * layouts as `isCompact` flips:
 *
 *  - **Expanded** (before any generation): horizontal scroll of card-type
 *    thumbnails on top, full-width word input + Generate button below.
 *  - **Compact** (during/after generation): the selected card-type collapses
 *    to a small thumbnail on the left, with the input + Generate stacked to
 *    its right.
 *
 * Three ingredients drive the morph:
 *
 *  1. **`LookaheadScope`** — runs an extra lookahead measurement pass so
 *     elements know where they *will be* in the target layout, before they
 *     actually render there.
 *  2. **`Modifier.animateBounds(LookaheadScope, ...)`** on each animating
 *     element. Spring-interpolates the rendered bounds (position + size)
 *     from the current values to the lookahead targets. This is what makes
 *     the input bar visually "fly" from its expanded position to its compact
 *     one — same composition node, just animated bounds.
 *  3. **`movableContentOf`** for the input column. Without it, Compose would
 *     unmount the input when the parent layout switches from Column to Row,
 *     destroying its TextField state and breaking the animation. With it,
 *     the same composition node moves between positions in the tree.
 *
 * The picker → thumbnail swap is still a fade (they're different composables)
 * but the input bar — the thing the user is actually watching — does a true
 * bounds interpolation.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun PickerAndInputArea(
    state: GeneratorUiState,
    onDesignToggled: (String) -> Unit,
    onQueryChanged: (String) -> Unit,
    onSubmit: () -> Unit
) {
    val isCompact = state.hasGenerated || state.isGenerating

    val props = InputBlockProps(
        query = state.query,
        isGenerating = state.isGenerating,
        generateEnabled = state.query.isNotBlank() &&
            state.selectedNoteTypeId != null &&
            !state.isGenerating,
        onQueryChanged = onQueryChanged,
        onSubmit = onSubmit
    )

    val inputContent = remember {
        movableContentOf<InputBlockProps, Modifier> { p, modifier ->
            InputAndGenerate(
                query = p.query,
                isGenerating = p.isGenerating,
                generateEnabled = p.generateEnabled,
                onQueryChanged = p.onQueryChanged,
                onSubmit = p.onSubmit,
                modifier = modifier
            )
        }
    }

    // Soft critically-damped spring. `StiffnessMedium` (the previous setting)
    // felt jumpy because the position interpolation finished before the size
    // catches up, producing a small visible "pop" at the end. `MediumLow` is
    // ~400ms — long enough that position and size interpolate together
    // smoothly, short enough that it still feels responsive.
    val boundsTransform = remember {
        BoundsTransform { _, _ ->
            spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMediumLow
            )
        }
    }

    LookaheadScope {
        Box(modifier = Modifier.fillMaxWidth()) {
            if (isCompact) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    state.selectedNoteType?.let { ct ->
                        // The thumbnail doesn't exist in expanded mode, so
                        // `animateBounds` alone can't morph it in — there's
                        // no "previous bounds" to interpolate from. Drive an
                        // explicit slide-in via a MutableTransitionState that
                        // starts false and immediately targets true on first
                        // composition, so AnimatedVisibility plays its enter
                        // transition.
                        val thumbVisible = remember {
                            MutableTransitionState(false).apply { targetState = true }
                        }
                        AnimatedVisibility(
                            visibleState = thumbVisible,
                            enter = fadeIn(tween(durationMillis = 360)) +
                                slideInHorizontally(tween(durationMillis = 360)) { -it / 2 }
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .width(72.dp)
                                    .animateBounds(
                                        lookaheadScope = this@LookaheadScope,
                                        boundsTransform = boundsTransform
                                    )
                            ) {
                                MiniCardPreview(noteType = ct, widthDp = 60)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = ct.name,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 2,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                    inputContent(
                        props,
                        Modifier
                            .weight(1f)
                            .animateBounds(
                                lookaheadScope = this@LookaheadScope,
                                boundsTransform = boundsTransform
                            )
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    AnimatedVisibility(
                        visible = !isCompact,
                        enter = fadeIn() + expandHorizontally(expandFrom = Alignment.Start),
                        exit = fadeOut() + shrinkHorizontally(shrinkTowards = Alignment.Start)
                    ) {
                        NoteTypePicker(
                            designs = state.availableDesigns,
                            selectedIds = state.selectedTemplateIds,
                            isContextLoaded = state.isContextLoaded,
                            onToggle = onDesignToggled
                        )
                    }
                    inputContent(
                        props,
                        Modifier
                            .fillMaxWidth()
                            .animateBounds(
                                lookaheadScope = this@LookaheadScope,
                                boundsTransform = boundsTransform
                            )
                    )
                }
            }
        }
    }
}

/** Bundle of inputs the morphing [InputAndGenerate] reads per frame. */
private data class InputBlockProps(
    val query: String,
    val isGenerating: Boolean,
    val generateEnabled: Boolean,
    val onQueryChanged: (String) -> Unit,
    val onSubmit: () -> Unit
)

/**
 * The shared "word input + Generate button" column that morphs between
 * full-width (expanded mode) and weighted-1f (compact mode). Both layouts
 * call this with the same [InputBlockProps] via the movableContentOf wrapper
 * so the TextField's composition state survives the bounds animation.
 */
@Composable
private fun InputAndGenerate(
    query: String,
    isGenerating: Boolean,
    generateEnabled: Boolean,
    onQueryChanged: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChanged,
            placeholder = { Text("Word or phrase") },
            singleLine = true,
            enabled = !isGenerating,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { onSubmit() })
        )
        Spacer(Modifier.height(8.dp))
        // Chunky 3D primary action — the old purple GameButton restored.
        // Picks its color from `MaterialTheme.colorScheme.primary`, which the
        // theme now points at violet again (see ui/theme/Color.kt).
        GameButton(
            onClick = onSubmit,
            enabled = generateEnabled
        ) {
            if (isGenerating) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = LocalContentColor.current
                )
            } else Text("Generate")
        }
    }
}

@Composable
private fun SaveDismissActions(
    isSaving: Boolean,
    isGenerating: Boolean,
    canSave: Boolean,
    onSave: () -> Unit,
    onDismiss: () -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        // Cancel / Dismiss — same chunky GameButton style as the primary
        // action, in a muted secondary tone so it reads as "back out."
        // Stays tappable mid-generation so it can cancel the in-flight
        // generation; only disabled while a save is actually in progress.
        GameButton(
            onClick = onDismiss,
            modifier = Modifier.weight(1f),
            enabled = !isSaving,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface
        ) {
            Text(if (isGenerating) "Cancel" else "Dismiss")
        }
        // Primary action — purple GameButton.
        GameButton(
            onClick = onSave,
            modifier = Modifier.weight(1f),
            enabled = !isSaving && canSave
        ) {
            if (isSaving) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = LocalContentColor.current
                )
            } else Text("Approve & save")
        }
    }
}

/**
 * Progressive list of LLM-streamed fields under the loader. Walks the
 * card type's placed text fields in declared order; for each one, wraps
 * a row in [AnimatedVisibility] keyed on whether the value is in state.
 * The instant a value lands (LLM stream surfaces it), the row fades and
 * slides into view — no per-field choreography in the caller, the
 * framework handles the animation.
 *
 * Layout intentionally minimal: small label, value below in a slightly
 * larger weight. Image / audio fields are skipped (they have their own
 * status indication when they land — image swaps the loader for the
 * real preview once everything completes).
 */
@Composable
private fun StreamingFieldsPreview(
    noteType: NoteType,
    textByFieldKey: Map<String, String>,
    modifier: Modifier = Modifier
) {
    val textFields = remember(noteType) {
        val placed = noteType.allPlacements().map { it.fieldKey }.toSet()
        noteType.fields.filter { f ->
            f.key in placed &&
                f.generator !is FieldGenerator.ImageGen &&
                f.generator !is FieldGenerator.Tts
        }
    }
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        textFields.forEach { field ->
            val value = textByFieldKey[field.key].orEmpty()
            val visible = value.isNotBlank()
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(tween(durationMillis = 280)) +
                    slideInVertically(tween(durationMillis = 320)) { it / 3 }
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = field.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = value,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 4
                    )
                }
            }
        }
    }
}
