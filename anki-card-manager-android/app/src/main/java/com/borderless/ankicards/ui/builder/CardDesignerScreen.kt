package com.borderless.ankicards.ui.builder

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.borderless.ankicards.domain.recipe.CardFace
import com.borderless.ankicards.domain.recipe.CardField
import com.borderless.ankicards.domain.recipe.FieldGenerator
import com.borderless.ankicards.domain.recipe.FieldLayout
import com.borderless.ankicards.domain.recipe.FieldPlacement
import com.borderless.ankicards.domain.recipe.FieldPresetCatalog
import kotlin.math.roundToInt

/**
 * Visual card designer. The user drags field chips from the palette onto a
 * card-shaped surface, then drags/resizes the placed boxes. A flip button
 * swaps between front and back faces with a 3D rotation animation.
 *
 * Mechanics:
 *  - Palette uses a long-press-to-drag gesture. The pointerInput on a chip
 *    captures the entire drag (Compose routes events to the initial down
 *    target until release), so we can paint a ghost overlay following the
 *    finger across containers without using Compose's official drag-and-drop
 *    APIs, which fight free-form placement.
 *  - When the user releases over the card surface, we compute the grid cell
 *    under the finger and ask the view model to add the field + place it.
 *  - Already-placed boxes own their own pointerInput for move (whole-box
 *    drag) and a corner handle for resize.
 *  - Overlap is checked in the view model; the UI just calls and reflects
 *    the result.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardDesignerScreen(
    viewModel: NoteTypeBuilderViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(state.justSaved) {
        if (state.justSaved) viewModel.consumeJustSaved()
    }

    // Shared state for the palette-drag overlay. Coordinates are in the root
    // composable's local space.
    var paletteDrag by remember { mutableStateOf<PaletteDrag?>(null) }
    var cardSurface by remember { mutableStateOf(CardSurfaceMetrics.Empty) }
    // The overlay Box that hosts the drag ghost is inset by the Scaffold
    // padding (status bar + app bar). The drag pointer is reported in ROOT
    // coordinates, so we capture this Box's own root origin and subtract it
    // when positioning the ghost — otherwise the ghost floats ~1 app-bar's
    // worth below the finger (the bug that motivated this work).
    var overlayOrigin by remember { mutableStateOf(Offset.Zero) }
    val haptics = LocalHapticFeedback.current

    // Drag feedback flags, derived once and threaded down to the card surface
    // so it can light up as a drop target.
    val isDragging = paletteDrag != null
    val isOverCard = paletteDrag?.let { cardSurface.contains(it.pointerInRoot) } == true

    Scaffold(
        // MainScaffold already applied system-bar insets; don't double-apply
        // (this pushed the TopAppBar down by an extra status-bar height).
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text(if (state.id == null) "New card" else "Edit card") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.save() },
                        enabled = !state.isSaving
                    ) {
                        if (state.isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(Icons.Filled.Check, contentDescription = "Save")
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (state.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }
            return@Scaffold
        }

        Box(modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .onGloballyPositioned { overlayOrigin = it.positionInRoot() }
        ) {
            DesignerContent(
                state = state,
                isDragging = isDragging,
                isOverCard = isOverCard,
                onSetFace = viewModel::setVisibleFace,
                onDesignNameChange = viewModel::onDesignNameChanged,
                onCardSurfaceMeasured = { cardSurface = it },
                onMoveField = viewModel::moveField,
                onResizeField = viewModel::resizeField,
                onEditField = viewModel::openFieldEditor,
                onRemovePlacement = viewModel::removePlacement,
                onPaletteDragChanged = { paletteDrag = it },
                onPaletteDragEnded = { drag ->
                    val cell = cardSurface.cellAt(drag.pointerInRoot)
                    if (cell != null) {
                        // Satisfying confirmation that the field landed.
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        val existing = state.fields.any { it.key == drag.fieldKey }
                        val field = if (existing) state.fields.first { it.key == drag.fieldKey }
                        else drag.buildField().also { viewModel.addField(it) }
                        viewModel.placeField(field.key, cell.col, cell.row)
                    }
                    paletteDrag = null
                }
            )

            paletteDrag?.let { drag ->
                DragGhost(
                    label = drag.label,
                    // Convert the root-space pointer into this overlay Box's
                    // local space so the ghost sits under the finger.
                    position = drag.pointerInRoot - overlayOrigin,
                    overCard = cardSurface.contains(drag.pointerInRoot)
                )
            }
        }
    }

    val editingKey = state.editingFieldKey
    if (editingKey != null) {
        val field = state.fields.firstOrNull { it.key == editingKey }
        if (field != null) {
            FieldEditorSheet(
                field = field,
                onDismiss = viewModel::closeFieldEditor,
                onUpdate = { transform -> viewModel.updateField(editingKey, transform) }
            )
        }
    }
}

@Composable
private fun DesignerContent(
    state: NoteTypeBuilderUiState,
    isDragging: Boolean,
    isOverCard: Boolean,
    onSetFace: (CardFace) -> Unit,
    onDesignNameChange: (String) -> Unit,
    onCardSurfaceMeasured: (CardSurfaceMetrics) -> Unit,
    onMoveField: (String, Int, Int) -> Boolean,
    onResizeField: (String, Int, Int) -> Boolean,
    onEditField: (String) -> Unit,
    onRemovePlacement: (String) -> Unit,
    onPaletteDragChanged: (PaletteDrag?) -> Unit,
    onPaletteDragEnded: (PaletteDrag) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // The designer edits ONE design (card type). Its name is the
            // user-facing "card type name"; the underlying note type is hidden
            // plumbing grouped by language.
            OutlinedTextField(
                value = state.currentTemplate?.name.orEmpty(),
                onValueChange = onDesignNameChange,
                label = { Text("Card type name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            FaceSwitcher(visible = state.visibleFace, onSelect = onSetFace)
            AnkiStatusLine(status = state.ankiPushStatus, saveError = state.saveError)
        }
        Box(modifier = Modifier.weight(1f, fill = true)) {
            CardSurface(
                state = state,
                isDragging = isDragging,
                isOverCard = isOverCard,
                onSurfaceMeasured = onCardSurfaceMeasured,
                onMoveField = onMoveField,
                onResizeField = onResizeField,
                onEditField = onEditField,
                onRemovePlacement = onRemovePlacement
            )
        }
        Palette(
            language = state.language,
            onDragChanged = onPaletteDragChanged,
            onDragEnded = onPaletteDragEnded
        )
    }
}

@Composable
private fun FaceSwitcher(visible: CardFace, onSelect: (CardFace) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FaceButton("Front", selected = visible == CardFace.FRONT) { onSelect(CardFace.FRONT) }
        FaceButton("Back", selected = visible == CardFace.BACK) { onSelect(CardFace.BACK) }
        Spacer(Modifier.weight(1f))
        FilledTonalButton(onClick = {
            onSelect(if (visible == CardFace.FRONT) CardFace.BACK else CardFace.FRONT)
        }) {
            Icon(Icons.Filled.Flip, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(6.dp))
            Text("Flip")
        }
    }
}

@Composable
private fun FaceButton(label: String, selected: Boolean, onClick: () -> Unit) {
    val container = if (selected) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.surfaceContainerHigh
    val content = if (selected) MaterialTheme.colorScheme.onPrimary
    else MaterialTheme.colorScheme.onSurface
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
        shape = RoundedCornerShape(10.dp)
    ) {
        Text(label, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
    }
}

@Composable
private fun AnkiStatusLine(status: AnkiPushStatus, saveError: String?) {
    val pair: Pair<String, androidx.compose.ui.graphics.Color>? = when {
        saveError != null -> saveError to MaterialTheme.colorScheme.error
        status is AnkiPushStatus.InProgress ->
            "Pushing to AnkiDroid…" to MaterialTheme.colorScheme.onSurfaceVariant
        status is AnkiPushStatus.Success ->
            "Saved · AnkiDroid model #${status.modelId}" to MaterialTheme.colorScheme.primary
        status is AnkiPushStatus.Skipped ->
            "Saved locally · ${status.reason}" to MaterialTheme.colorScheme.onSurfaceVariant
        status is AnkiPushStatus.Failed ->
            "Saved locally · AnkiDroid: ${status.message}" to MaterialTheme.colorScheme.error
        else -> null
    }
    if (pair != null) {
        Text(pair.first, style = MaterialTheme.typography.bodySmall, color = pair.second)
    }
}

// ── Card surface ─────────────────────────────────────────────────────────

@Composable
private fun CardSurface(
    state: NoteTypeBuilderUiState,
    isDragging: Boolean,
    isOverCard: Boolean,
    onSurfaceMeasured: (CardSurfaceMetrics) -> Unit,
    onMoveField: (String, Int, Int) -> Boolean,
    onResizeField: (String, Int, Int) -> Boolean,
    onEditField: (String) -> Unit,
    onRemovePlacement: (String) -> Unit
) {
    val rotation by animateFloatAsState(
        targetValue = if (state.visibleFace == CardFace.BACK) 180f else 0f,
        animationSpec = tween(durationMillis = 450),
        label = "card-flip"
    )
    val density = LocalDensity.current

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        // Card is portrait 3:4. Pick the dimension that constrains us.
        val maxW = maxWidth
        val maxH = maxHeight
        val cardWidth = if (maxW * 4 / 3 <= maxH) maxW else maxH * 3 / 4
        val cardHeight = cardWidth * 4 / 3

        Box(
            modifier = Modifier
                .size(width = cardWidth, height = cardHeight)
                .graphicsLayer {
                    rotationY = rotation
                    cameraDistance = 12f * density.density
                }
        ) {
            // Swap content at the 90° crossover so the back face isn't mirrored.
            val showingFront = rotation <= 90f
            if (showingFront) {
                FaceCanvas(
                    face = CardFace.FRONT,
                    state = state,
                    isDragging = isDragging,
                    isOverCard = isOverCard,
                    onSurfaceMeasured = onSurfaceMeasured,
                    onMoveField = onMoveField,
                    onResizeField = onResizeField,
                    onEditField = onEditField,
                    onRemovePlacement = onRemovePlacement
                )
            } else {
                Box(modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { rotationY = 180f }
                ) {
                    FaceCanvas(
                        face = CardFace.BACK,
                        state = state,
                        isDragging = isDragging,
                        isOverCard = isOverCard,
                        onSurfaceMeasured = onSurfaceMeasured,
                        onMoveField = onMoveField,
                        onResizeField = onResizeField,
                        onEditField = onEditField,
                        onRemovePlacement = onRemovePlacement
                    )
                }
            }
        }
    }
}

@Composable
private fun FaceCanvas(
    face: CardFace,
    state: NoteTypeBuilderUiState,
    isDragging: Boolean,
    isOverCard: Boolean,
    onSurfaceMeasured: (CardSurfaceMetrics) -> Unit,
    onMoveField: (String, Int, Int) -> Boolean,
    onResizeField: (String, Int, Int) -> Boolean,
    onEditField: (String) -> Unit,
    onRemovePlacement: (String) -> Unit
) {
    var sizePx by remember { mutableStateOf(IntSize.Zero) }
    var positionInRoot by remember { mutableStateOf(Offset.Zero) }

    val placements = state.placements.filter { it.face == face }

    val shape = RoundedCornerShape(20.dp)
    // The drop surface must read as a distinct, raised card — not blend into
    // the page. Use the highest tonal container (per DESIGN.md) plus a border
    // that brightens to the violet accent while a chip is being dragged over.
    val borderColor = when {
        isOverCard -> MaterialTheme.colorScheme.primary
        isDragging -> MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
        else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
    }
    val borderWidth by animateDpAsState(
        targetValue = if (isOverCard) 3.dp else 1.5.dp,
        animationSpec = tween(150),
        label = "drop-border"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .shadow(10.dp, shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, shape)
            .border(borderWidth, borderColor, shape)
            .onSizeChanged { sizePx = it }
            .onGloballyPositioned { coords -> positionInRoot = coords.positionInRoot() }
            .pointerInput(Unit) {
                // Claim taps on empty card area so they don't fall through.
                detectTapGestures { }
            }
    ) {
        GridBackground()
        placements.forEach { p ->
            key(p.fieldKey + "@" + face.name) {
                PlacedFieldBox(
                    placement = p,
                    field = state.fields.firstOrNull { it.key == p.fieldKey },
                    surfaceSize = sizePx,
                    onMove = { col, row -> onMoveField(p.fieldKey, col, row) },
                    onResize = { w, h -> onResizeField(p.fieldKey, w, h) },
                    onEdit = { onEditField(p.fieldKey) },
                    onRemove = { onRemovePlacement(p.fieldKey) }
                )
            }
        }
    }

    LaunchedEffect(sizePx, positionInRoot, face, state.visibleFace) {
        if (face == state.visibleFace) {
            onSurfaceMeasured(CardSurfaceMetrics(positionInRoot, sizePx))
        }
    }
}

/** Faint grid lines drawn behind the placed fields. */
@Composable
private fun GridBackground() {
    val color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
    Canvas(modifier = Modifier.fillMaxSize()) {
        val cellW = size.width / FieldLayout.GRID_COLS
        val cellH = size.height / FieldLayout.GRID_ROWS
        for (c in 1 until FieldLayout.GRID_COLS) {
            val x = cellW * c
            drawLine(color, start = Offset(x, 0f), end = Offset(x, size.height), strokeWidth = 1f)
        }
        for (r in 1 until FieldLayout.GRID_ROWS) {
            val y = cellH * r
            drawLine(color, start = Offset(0f, y), end = Offset(size.width, y), strokeWidth = 1f)
        }
    }
}

@Composable
private fun PlacedFieldBox(
    placement: FieldPlacement,
    field: CardField?,
    surfaceSize: IntSize,
    onMove: (Int, Int) -> Boolean,
    onResize: (Int, Int) -> Boolean,
    onEdit: () -> Unit,
    onRemove: () -> Unit
) {
    if (surfaceSize == IntSize.Zero) return
    val cellW = surfaceSize.width.toFloat() / FieldLayout.GRID_COLS
    val cellH = surfaceSize.height.toFloat() / FieldLayout.GRID_ROWS
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current

    // Transient deltas while dragging/resizing. The MOVE delta is applied via
    // graphicsLayer (visual only) so the gesture node below stays put — moving
    // the node itself with .offset{} relocated its hit-region every frame,
    // which is what made dragging drop events and "snap to release" instead of
    // tracking the finger. The RESIZE delta changes the measured size, so it
    // stays in .size().
    var dragDelta by remember(placement) { mutableStateOf(Offset.Zero) }
    var resizeDelta by remember(placement) { mutableStateOf(Offset.Zero) }
    var dragging by remember(placement) { mutableStateOf(false) }

    val baseX = placement.layout.col * cellW
    val baseY = placement.layout.row * cellH
    val baseW = placement.layout.w * cellW
    val baseH = placement.layout.h * cellH

    val drawW = (baseW + resizeDelta.x).coerceAtLeast(cellW * 2)
    val drawH = (baseH + resizeDelta.y).coerceAtLeast(cellH * 1)

    // Subtle "picked up" feedback.
    val lift by animateFloatAsState(if (dragging) 1.04f else 1f, label = "drag-lift")
    val elevation by animateDpAsState(if (dragging) 12.dp else 2.dp, label = "drag-elevation")
    val boxShape = RoundedCornerShape(8.dp)

    Box(
        modifier = Modifier
            .offset { IntOffset(baseX.roundToInt(), baseY.roundToInt()) }
            .size(
                width = with(density) { drawW.toDp() },
                height = with(density) { drawH.toDp() }
            )
            .graphicsLayer {
                translationX = dragDelta.x
                translationY = dragDelta.y
                scaleX = lift
                scaleY = lift
            }
            .shadow(elevation, boxShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, boxShape)
            .border(
                width = if (dragging) 2.dp else 0.dp,
                color = if (dragging) MaterialTheme.colorScheme.primary
                else androidx.compose.ui.graphics.Color.Transparent,
                shape = boxShape
            )
            // Key on the WHOLE placement (not just fieldKey/face): the gesture
            // closure captures baseX/baseY/baseW/baseH, which derive from
            // placement.layout. If we don't restart the block when the layout
            // changes, the SECOND drag computes its target from the stale
            // original origin — the "works once, then reverts" bug.
            .pointerInput(placement, surfaceSize) {
                detectDragGestures(
                    onDragStart = {
                        dragging = true
                        dragDelta = Offset.Zero
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    },
                    onDragEnd = {
                        val newCol = ((baseX + dragDelta.x) / cellW).roundToInt()
                        val newRow = ((baseY + dragDelta.y) / cellH).roundToInt()
                        onMove(newCol, newRow)
                        dragDelta = Offset.Zero
                        dragging = false
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    },
                    onDragCancel = {
                        dragDelta = Offset.Zero
                        dragging = false
                    }
                ) { change, dragAmount ->
                    change.consume()
                    dragDelta += dragAmount
                }
            }
    ) {
        Column(modifier = Modifier
            .fillMaxSize()
            .padding(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.OpenWith,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.size(6.dp))
                Text(
                    text = field?.label ?: placement.fieldKey,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onEdit, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Filled.Edit, contentDescription = "Edit", modifier = Modifier.size(16.dp))
                }
                IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Filled.Delete, contentDescription = "Remove", modifier = Modifier.size(16.dp))
                }
            }
            if (field != null) {
                Text(
                    text = generatorBlurb(field),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2
                )
            }
        }

        // Resize handle, bottom-right.
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .size(22.dp)
                .background(
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                    RoundedCornerShape(topStart = 8.dp, bottomEnd = 8.dp)
                )
                // Key on the WHOLE placement (not just fieldKey/face): the gesture
            // closure captures baseX/baseY/baseW/baseH, which derive from
            // placement.layout. If we don't restart the block when the layout
            // changes, the SECOND drag computes its target from the stale
            // original origin — the "works once, then reverts" bug.
            .pointerInput(placement, surfaceSize) {
                    detectDragGestures(
                        onDragStart = { resizeDelta = Offset.Zero },
                        onDragEnd = {
                            val newW = ((baseW + resizeDelta.x) / cellW).roundToInt()
                            val newH = ((baseH + resizeDelta.y) / cellH).roundToInt()
                            onResize(newW.coerceAtLeast(2), newH.coerceAtLeast(1))
                            resizeDelta = Offset.Zero
                        },
                        onDragCancel = { resizeDelta = Offset.Zero }
                    ) { change, dragAmount ->
                        // Consume so the parent's move-drag doesn't also fire on
                        // this same pointer (resize and move are mutually exclusive).
                        change.consume()
                        resizeDelta += dragAmount
                    }
                }
        )
    }
}

// ── Palette ──────────────────────────────────────────────────────────────

@Composable
private fun Palette(
    language: String?,
    onDragChanged: (PaletteDrag?) -> Unit,
    onDragEnded: (PaletteDrag) -> Unit
) {
    // Only show chips that make sense for this card's language. A
    // Cantonese-tagged card type gets Core + Cantonese + Extras; a Spanish
    // card type gets Core + European + Extras; a Generic card type gets
    // just Core + Extras. See FieldPresetCatalog.forLanguage for the
    // language → group mapping.
    val groups = remember(language) { FieldPresetCatalog.forLanguage(language) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(
            "Long-press a chip and drag it onto the card",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            groups.forEach { group ->
                items(group.entries) { entry ->
                    PaletteChip(
                        entry = entry,
                        onDragChanged = onDragChanged,
                        onDragEnded = onDragEnded
                    )
                }
            }
        }
    }
}

@Composable
private fun PaletteChip(
    entry: FieldPresetCatalog.PresetEntry,
    onDragChanged: (PaletteDrag?) -> Unit,
    onDragEnded: (PaletteDrag) -> Unit
) {
    var chipOrigin by remember { mutableStateOf(Offset.Zero) }
    var currentDrag by remember { mutableStateOf<PaletteDrag?>(null) }
    val haptics = LocalHapticFeedback.current

    AssistChip(
        onClick = { /* drag-only — tap is a no-op in v1 */ },
        label = { Text(entry.displayName) },
        modifier = Modifier
            .widthIn(min = 80.dp)
            .onGloballyPositioned { chipOrigin = it.positionInRoot() }
            .pointerInput(entry.displayName) {
                detectDragAfterLongPress(
                    timeoutMillis = GRAB_LONG_PRESS_MS,
                    onLongPressStart = { localOffset ->
                        // The satisfying "grab" — fires the moment the press
                        // latches, before any movement.
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        val built = entry.build()
                        val pointerInRoot = chipOrigin + localOffset
                        val drag = PaletteDrag(
                            fieldKey = built.key,
                            label = built.label,
                            pointerInRoot = pointerInRoot,
                            buildField = entry.build
                        )
                        currentDrag = drag
                        onDragChanged(drag)
                    },
                    onDrag = { delta ->
                        currentDrag = currentDrag?.let { it.copy(pointerInRoot = it.pointerInRoot + delta) }
                        onDragChanged(currentDrag)
                    },
                    onDragEnd = {
                        currentDrag?.let(onDragEnded)
                        currentDrag = null
                    },
                    onDragCancel = {
                        currentDrag = null
                        onDragChanged(null)
                    }
                )
            }
    )
}

/** How long the user must hold a palette chip before it lifts into a drag.
 *  The Android default (~400-500ms) felt sluggish; this is snappy but still
 *  long enough not to fire on a horizontal scroll of the palette. */
private const val GRAB_LONG_PRESS_MS = 220L

/**
 * Like `detectDragGesturesAfterLongPress`, but with a caller-controlled
 * long-press [timeoutMillis] instead of the platform default. Built on the
 * pointer-scope `withTimeout`, which throws
 * [PointerEventTimeoutCancellationException] when the hold passes the
 * threshold (without cancelling the surrounding gesture coroutine).
 *
 * If the pointer lifts or the gesture is cancelled (e.g. the parent LazyRow
 * claims it for a horizontal scroll) before the timeout, no drag begins.
 */
private suspend fun PointerInputScope.detectDragAfterLongPress(
    timeoutMillis: Long,
    onLongPressStart: (Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val longPressed = try {
            withTimeout(timeoutMillis) {
                // Returns when the pointer goes up (tap) or is cancelled. If
                // neither happens in time, withTimeout throws → it's a hold.
                waitForUpOrCancellation()
            }
            false
        } catch (_: PointerEventTimeoutCancellationException) {
            true
        }
        if (longPressed) {
            onLongPressStart(down.position)
            val completed = drag(down.id) { change ->
                onDrag(change.positionChange())
                change.consume()
            }
            if (completed) onDragEnd() else onDragCancel()
        }
    }
}

@Composable
private fun DragGhost(label: String, position: Offset, overCard: Boolean) {
    // `position` is already in the host overlay's local space and points at
    // the finger. We center the ghost on it (measured size) so it tracks the
    // finger exactly instead of floating off to one side.
    var ghostSize by remember { mutableStateOf(IntSize.Zero) }

    // Pop-in on grab: a quick spring with a touch of overshoot. This is the
    // explicit "fun affordance" exception to the no-bouncy-springs rule.
    val pop = remember { Animatable(0.7f) }
    LaunchedEffect(Unit) {
        pop.animateTo(
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = 0.5f,
                stiffness = Spring.StiffnessMedium
            )
        )
    }

    val containerColor = if (overCard) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.surfaceContainerHighest
    val contentColor = if (overCard) MaterialTheme.colorScheme.onPrimary
    else MaterialTheme.colorScheme.onSurface
    val shape = RoundedCornerShape(10.dp)

    Box(
        modifier = Modifier
            .offset {
                IntOffset(
                    (position.x - ghostSize.width / 2f).roundToInt(),
                    (position.y - ghostSize.height / 2f).roundToInt()
                )
            }
            .onSizeChanged { ghostSize = it }
            .graphicsLayer {
                scaleX = pop.value
                scaleY = pop.value
            }
            .shadow(12.dp, shape)
            .background(containerColor, shape)
            .border(
                1.5.dp,
                MaterialTheme.colorScheme.primary.copy(alpha = if (overCard) 1f else 0.5f),
                shape
            )
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(
            label,
            color = contentColor,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold
        )
    }
}

// ── Field metadata sheet (ported from list-based builder) ────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FieldEditorSheet(
    field: CardField,
    onDismiss: () -> Unit,
    onUpdate: ((CardField) -> CardField) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Edit field", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold)
            OutlinedTextField(
                value = field.label,
                onValueChange = { v -> onUpdate { it.copy(label = v) } },
                label = { Text("Label") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = field.description,
                onValueChange = { v -> onUpdate { it.copy(description = v) } },
                label = { Text("Description") },
                supportingText = { Text("Plain English. For AI fields, becomes the prompt.") },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
            )
            HorizontalDivider()
            Text("Key: ${field.key}", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Generator: ${generatorBlurb(field)}", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            FilledTonalButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Done") }
            Spacer(Modifier.height(8.dp))
        }
    }
}

// ── helpers ──────────────────────────────────────────────────────────────

private fun generatorBlurb(field: CardField): String = when (val g = field.generator) {
    is FieldGenerator.UserInput -> "Manual input"
    is FieldGenerator.Dictionary -> "Dictionary (${g.source.name.lowercase()})"
    is FieldGenerator.Llm -> "AI-generated"
    is FieldGenerator.Tts -> "Audio (TTS from \"${g.sourceFieldKey}\")"
    is FieldGenerator.ImageGen -> "Image (${g.style})"
}

private data class PaletteDrag(
    val fieldKey: String,
    val label: String,
    val pointerInRoot: Offset,
    val buildField: () -> CardField
)

private data class CardSurfaceMetrics(
    val originInRoot: Offset,
    val sizePx: IntSize
) {
    fun contains(p: Offset): Boolean =
        p.x in originInRoot.x..(originInRoot.x + sizePx.width) &&
            p.y in originInRoot.y..(originInRoot.y + sizePx.height)

    fun cellAt(p: Offset): GridCell? {
        if (!contains(p)) return null
        val cellW = sizePx.width.toFloat() / FieldLayout.GRID_COLS
        val cellH = sizePx.height.toFloat() / FieldLayout.GRID_ROWS
        val col = ((p.x - originInRoot.x) / cellW).toInt().coerceIn(0, FieldLayout.GRID_COLS - 1)
        val row = ((p.y - originInRoot.y) / cellH).toInt().coerceIn(0, FieldLayout.GRID_ROWS - 1)
        return GridCell(col, row)
    }

    companion object {
        val Empty = CardSurfaceMetrics(Offset.Zero, IntSize.Zero)
    }
}

private data class GridCell(val col: Int, val row: Int)
