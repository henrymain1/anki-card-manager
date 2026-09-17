package com.borderless.ankicards.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.borderless.ankicards.domain.recipe.CardFace
import com.borderless.ankicards.domain.recipe.CardField
import com.borderless.ankicards.domain.recipe.NoteType
import com.borderless.ankicards.domain.recipe.FieldGenerator
import com.borderless.ankicards.domain.recipe.FieldLayout
import com.borderless.ankicards.domain.recipe.Template
import kotlin.math.roundToInt

/**
 * Compact visual of one face of a [NoteType], rendered as a portrait card
 * silhouette with each placement drawn as a small coloured rectangle.
 *
 * Reused everywhere we want users to see *what their design looks like*
 * without having to open the full visual designer: the Designs list rows, the
 * card-type picker on the generator (Phase 2), the deck detail screen
 * (Phase 4), eventual import previews.
 *
 * Renders with the same percentage-of-card-dimensions model as the Anki HTML
 * exporter, so what you see here lines up with what AnkiDroid will produce.
 *
 * @param template The specific design (template) to preview. Defaults to the
 *                card type's primary design when null.
 * @param widthDp Outer width of the preview. Height is derived from the
 *                portrait 3:4 aspect ratio used everywhere else.
 */
@Composable
fun MiniCardPreview(
    noteType: NoteType,
    template: Template? = null,
    face: CardFace = CardFace.FRONT,
    widthDp: Int = 60,
    modifier: Modifier = Modifier
) {
    val heightDp = widthDp * 4 / 3
    val placements = (template ?: noteType.primaryTemplate)?.placementsOn(face).orEmpty()
    val fieldByKey = noteType.fields.associateBy { it.key }

    Box(
        modifier = modifier
            .size(width = widthDp.dp, height = heightDp.dp)
            .shadow(1.dp, RoundedCornerShape(6.dp))
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surface)
    ) {
        if (placements.isEmpty()) {
            Text(
                text = "empty",
                fontSize = 8.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center)
            )
        } else {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val totalW = constraints.maxWidth.toFloat()
                val totalH = constraints.maxHeight.toFloat()
                val cellW = totalW / FieldLayout.GRID_COLS
                val cellH = totalH / FieldLayout.GRID_ROWS

                placements.forEach { p ->
                    val field = fieldByKey[p.fieldKey] ?: return@forEach
                    val l = p.layout
                    val swatchOffset = IntOffset(
                        x = (l.col * cellW).roundToInt(),
                        y = (l.row * cellH).roundToInt()
                    )
                    val swatchSize = IntSize(
                        width = (l.w * cellW).roundToInt(),
                        height = (l.h * cellH).roundToInt()
                    )
                    PlacementSwatch(
                        field = field,
                        swatchOffset = swatchOffset,
                        swatchSize = swatchSize
                    )
                }
            }
        }
    }
}

@Composable
private fun PlacementSwatch(
    field: CardField,
    swatchOffset: IntOffset,
    swatchSize: IntSize
) {
    val color = swatchColorFor(field.generator)
    Box(
        modifier = Modifier
            .offset { swatchOffset }
            // Fixed-pixel sizing via a layout modifier — converts the IntSize
            // (pixels we computed from the parent's constraints) into an exact
            // measured size without going through Dp.
            .layout { measurable, _ ->
                val placeable = measurable.measure(
                    Constraints.fixed(swatchSize.width, swatchSize.height)
                )
                layout(placeable.width, placeable.height) { placeable.place(0, 0) }
            }
            .padding(1.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(color),
        contentAlignment = Alignment.Center
    ) {
        // Render a short initial only when the swatch is big enough to read.
        if (swatchSize.width > 30 && swatchSize.height > 20) {
            Text(
                text = field.label.firstNonBlankInitial(),
                fontSize = 9.sp,
                color = Color.White.copy(alpha = 0.95f),
                maxLines = 1,
                overflow = TextOverflow.Clip
            )
        }
    }
}

/**
 * Distinct colours per generator type so the preview reads at a glance:
 * image fields stand out from text fields, audio from image, etc.
 */
@Composable
private fun swatchColorFor(generator: FieldGenerator): Color = when (generator) {
    is FieldGenerator.ImageGen -> MaterialTheme.colorScheme.tertiary
    is FieldGenerator.Tts -> MaterialTheme.colorScheme.secondary
    is FieldGenerator.Dictionary -> MaterialTheme.colorScheme.primary.copy(alpha = 0.75f)
    is FieldGenerator.Llm -> MaterialTheme.colorScheme.primary
    is FieldGenerator.UserInput -> MaterialTheme.colorScheme.outline
}

private fun String.firstNonBlankInitial(): String =
    trim().firstOrNull()?.uppercaseChar()?.toString() ?: ""
