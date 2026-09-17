@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.borderless.ankicards.ui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.borderless.ankicards.data.gemini.GeneratedMedia
import com.borderless.ankicards.domain.recipe.CardFace
import com.borderless.ankicards.domain.recipe.CardField
import com.borderless.ankicards.domain.recipe.NoteType
import com.borderless.ankicards.domain.recipe.FieldGenerator
import com.borderless.ankicards.domain.recipe.FieldLayout
import com.borderless.ankicards.domain.recipe.FieldPlacement
import kotlin.math.roundToInt

/**
 * Full-size card preview that renders a [NoteType]'s placements on a 3:4
 * surface, populated with the actual generated values. Used by the Phase 2
 * generator screen so the user sees their card in the same shape AnkiDroid
 * will render — not as a flat list of editor rows.
 *
 * Interactions:
 *  - Tap any placed box → opens the host's edit sheet for that field (via [onFieldClicked]).
 *  - Tap the regenerate icon on a box → [onFieldRegenerate] for that field.
 *  - Tap Flip → swaps front ↔ back with the same 3D animation as the designer.
 *
 * Fields without placements on either face are surfaced below the card via
 * [onListUnplacedField] so the user can still edit / regenerate them.
 */
@Composable
fun GeneratedCardSurface(
    noteType: NoteType,
    textByFieldKey: Map<String, String>,
    mediaByFieldKey: Map<String, GeneratedMedia>,
    fieldErrors: Map<String, String>,
    regeneratingFieldKey: String?,
    onFieldClicked: (CardField) -> Unit,
    onFieldRegenerate: (CardField) -> Unit,
    modifier: Modifier = Modifier
) {
    var visibleFace by remember { mutableStateOf(CardFace.FRONT) }
    val rotation by animateFloatAsState(
        targetValue = if (visibleFace == CardFace.BACK) 180f else 0f,
        animationSpec = tween(durationMillis = 450),
        label = "card-flip"
    )
    val density = LocalDensity.current

    Column(modifier = modifier.fillMaxWidth()) {
        FaceSwitcher(
            visible = visibleFace,
            onSelect = { visibleFace = it }
        )
        Spacer(Modifier.size(8.dp))

        // The card itself. BoxWithConstraints lets us size the card to whatever
        // width we have, deriving the height from the 3:4 aspect ratio.
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val cardWidth = maxWidth
            val cardHeight = cardWidth * 4 / 3

            Box(
                modifier = Modifier
                    .size(width = cardWidth, height = cardHeight)
                    .graphicsLayer {
                        rotationY = rotation
                        cameraDistance = 12f * density.density
                    }
            ) {
                val showingFront = rotation <= 90f
                if (showingFront) {
                    FaceContent(
                        noteType = noteType,
                        face = CardFace.FRONT,
                        textByFieldKey = textByFieldKey,
                        mediaByFieldKey = mediaByFieldKey,
                        fieldErrors = fieldErrors,
                        regeneratingFieldKey = regeneratingFieldKey,
                        onFieldClicked = onFieldClicked,
                        onFieldRegenerate = onFieldRegenerate
                    )
                } else {
                    Box(modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { rotationY = 180f }
                    ) {
                        FaceContent(
                            noteType = noteType,
                            face = CardFace.BACK,
                            textByFieldKey = textByFieldKey,
                            mediaByFieldKey = mediaByFieldKey,
                            fieldErrors = fieldErrors,
                            regeneratingFieldKey = regeneratingFieldKey,
                            onFieldClicked = onFieldClicked,
                            onFieldRegenerate = onFieldRegenerate
                        )
                    }
                }
            }
        }

        // Below the card: any fields that exist on the card type but aren't
        // placed on either face of the previewed (primary) template. The user
        // can still edit / regenerate them.
        val placedKeys = (noteType.placementsOn(CardFace.FRONT) + noteType.placementsOn(CardFace.BACK))
            .map { it.fieldKey }.toSet()
        val unplaced = noteType.fields.filter { it.key !in placedKeys }
        if (unplaced.isNotEmpty()) {
            Spacer(Modifier.size(16.dp))
            Text(
                "Other fields",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.size(6.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                unplaced.forEach { field ->
                    UnplacedFieldRow(
                        field = field,
                        textValue = textByFieldKey[field.key].orEmpty(),
                        media = mediaByFieldKey[field.key],
                        isRegenerating = regeneratingFieldKey == field.key,
                        onClick = { onFieldClicked(field) },
                        onRegenerate = { onFieldRegenerate(field) }
                    )
                }
            }
        }
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
            Icon(Icons.Filled.Flip, contentDescription = null, modifier = Modifier.size(16.dp))
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
    Surface(
        onClick = onClick,
        color = container,
        contentColor = content,
        shape = RoundedCornerShape(10.dp)
    ) {
        Text(label, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
    }
}

/**
 * Renders one face of the card: the rounded-rect surface plus every
 * [FieldPlacement] on it as an absolutely-positioned box.
 */
@Composable
private fun FaceContent(
    noteType: NoteType,
    face: CardFace,
    textByFieldKey: Map<String, String>,
    mediaByFieldKey: Map<String, GeneratedMedia>,
    fieldErrors: Map<String, String>,
    regeneratingFieldKey: String?,
    onFieldClicked: (CardField) -> Unit,
    onFieldRegenerate: (CardField) -> Unit
) {
    val placements = noteType.placementsOn(face)
    val fieldByKey = noteType.fields.associateBy { it.key }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .shadow(8.dp, RoundedCornerShape(20.dp))
            .clip(RoundedCornerShape(20.dp))
            // `surface` matches the app background in dark mode, so the card
            // visually merges into the page. `surfaceContainerHighest` is a
            // tonal step up — clear distinction without fighting the theme.
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
    ) {
        if (placements.isEmpty()) {
            Text(
                text = "No fields placed on this face yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp)
            )
        } else {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val totalW = constraints.maxWidth.toFloat()
                val totalH = constraints.maxHeight.toFloat()
                val cellW = totalW / FieldLayout.GRID_COLS
                val cellH = totalH / FieldLayout.GRID_ROWS

                placements.forEach { p ->
                    val field = fieldByKey[p.fieldKey] ?: return@forEach
                    val swatchOffset = IntOffset(
                        x = (p.layout.col * cellW).roundToInt(),
                        y = (p.layout.row * cellH).roundToInt()
                    )
                    val swatchSize = IntSize(
                        width = (p.layout.w * cellW).roundToInt(),
                        height = (p.layout.h * cellH).roundToInt()
                    )
                    PlacedFieldBox(
                        field = field,
                        textValue = textByFieldKey[field.key].orEmpty(),
                        media = mediaByFieldKey[field.key],
                        hasError = fieldErrors[field.key] != null,
                        isRegenerating = regeneratingFieldKey == field.key,
                        offset = swatchOffset,
                        size = swatchSize,
                        onClick = { onFieldClicked(field) },
                        onRegenerate = { onFieldRegenerate(field) }
                    )
                }
            }
        }
    }
}

@Composable
private fun PlacedFieldBox(
    field: CardField,
    textValue: String,
    media: GeneratedMedia?,
    hasError: Boolean,
    isRegenerating: Boolean,
    offset: IntOffset,
    size: IntSize,
    onClick: () -> Unit,
    onRegenerate: () -> Unit
) {
    val borderColor = if (hasError) MaterialTheme.colorScheme.error
    else MaterialTheme.colorScheme.outlineVariant
    val bg = MaterialTheme.colorScheme.surfaceContainerHighest

    Box(
        modifier = Modifier
            .offset { offset }
            .layout { measurable, _ ->
                val placeable = measurable.measure(
                    Constraints.fixed(size.width, size.height)
                )
                layout(placeable.width, placeable.height) { placeable.place(0, 0) }
            }
            .padding(2.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .clickable(onClick = onClick)
    ) {
        when (field.generator) {
            is FieldGenerator.ImageGen -> {
                if (media is GeneratedMedia.Image) {
                    AsyncImage(
                        model = media.bytes,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    PlacedPlaceholder(
                        icon = Icons.Filled.Image,
                        labelTop = field.label,
                        body = if (isRegenerating) "Generating…" else "Image",
                        smallBox = size.width < 200 || size.height < 200
                    )
                }
            }
            is FieldGenerator.Tts -> {
                if (media is GeneratedMedia.Audio) {
                    // Real playback affordance — tap plays the MP3, with a
                    // subtle scale/brightness animation while audio is on.
                    // Button is centered in the placed box at a fixed dp size;
                    // the box itself can be any size.
                    AudioPlayButton(
                        bytes = media.bytes,
                        modifier = Modifier.fillMaxSize(),
                        sizeDp = 44
                    )
                } else {
                    PlacedPlaceholder(
                        icon = Icons.Filled.AudioFile,
                        labelTop = field.label,
                        body = if (isRegenerating) "Generating…" else "Audio pending",
                        smallBox = size.width < 200 || size.height < 200
                    )
                }
            }
            else -> {
                PlacedText(
                    labelTop = field.label,
                    body = textValue.ifBlank { if (isRegenerating) "Generating…" else "—" },
                    smallBox = size.width < 200 || size.height < 200
                )
            }
        }

        // Regenerate affordance, top-right corner of the placed box.
        Surface(
            onClick = onRegenerate,
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(2.dp)
                .size(20.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (isRegenerating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 1.5.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = "Regenerate",
                        modifier = Modifier.size(12.dp)
                    )
                }
            }
        }

        // Faint error halo when this field failed during the last generation.
        if (hasError) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(6.dp))
                    .background(borderColor.copy(alpha = 0.10f))
            )
        }
    }
}

@Composable
private fun PlacedText(labelTop: String, body: String, smallBox: Boolean) {
    Column(modifier = Modifier
        .fillMaxSize()
        .padding(6.dp)
    ) {
        if (!smallBox) {
            Text(
                text = labelTop,
                fontSize = 9.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Clip
            )
            Spacer(Modifier.size(2.dp))
        }
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxSize(),
            maxLines = if (smallBox) 1 else 6,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun PlacedPlaceholder(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    labelTop: String,
    body: String,
    smallBox: Boolean
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(if (smallBox) 14.dp else 22.dp)
        )
        if (!smallBox) {
            Spacer(Modifier.size(2.dp))
            Text(
                text = labelTop,
                fontSize = 9.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium
            )
        }
        Text(
            text = body,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun UnplacedFieldRow(
    field: CardField,
    textValue: String,
    media: GeneratedMedia?,
    isRegenerating: Boolean,
    onClick: () -> Unit,
    onRegenerate: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = field.label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
                val preview = when {
                    media is GeneratedMedia.Image -> "(image)"
                    media is GeneratedMedia.Audio -> "(audio)"
                    textValue.isNotBlank() -> textValue
                    else -> "—"
                }
                Text(
                    text = preview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Surface(
                onClick = onRegenerate,
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(28.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (isRegenerating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 1.5.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = "Regenerate",
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        }
    }
}
