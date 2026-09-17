package com.borderless.ankicards.ui.common

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The visual styles the card-loading animation can take. Add a new entry here
 * and a branch in [CardGenerationLoader] to grow the set; callers can pick one
 * deliberately or randomise per generation for variety.
 */
enum class CardLoaderStyle {
    /** Upward-drifting sparkle particles + breathing AutoAwesome icon. */
    Sparkles,

    /** A glossy 3D orb in the center that pulses, emitting concentric waves
     *  that ripple out to the card edges on every beat. */
    Orb
}

/**
 * The card-shaped "we're generating" loading visualization.
 *
 * Shown in place of [GeneratedCardWebSurface] while the pipeline is
 * mid-flight but before any fields have come back. Matches the real
 * preview's 3:4 portrait shape and rounded-rect surface so the swap-in of
 * the actual card (once generation completes) feels like the same object
 * filling in, not a different component appearing.
 *
 * The body is chosen by [style] (see [CardLoaderStyle]); every style shares
 * the same card shell + the bottom caption line. Each style drives its motion
 * from a single [rememberInfiniteTransition], so the cost is one timer
 * regardless of how many elements it animates.
 */
@Composable
fun CardGenerationLoader(
    modifier: Modifier = Modifier,
    style: CardLoaderStyle = CardLoaderStyle.Orb
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val cardWidth = maxWidth
        val cardHeight = cardWidth * 4 / 3

        Box(
            modifier = Modifier
                .size(width = cardWidth, height = cardHeight)
                .shadow(8.dp, RoundedCornerShape(20.dp))
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
        ) {
            when (style) {
                CardLoaderStyle.Sparkles -> {
                    AmbientGradient()
                    SparkleField()
                    CenterIcon()
                }
                CardLoaderStyle.Orb -> OrbWaves()
            }
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(top = 20.dp)
            ) {
                AnimatedCaption()
            }
        }
    }
}

// ── Layer 1 ─────────────────────────────────────────────────────────────

/**
 * Soft violet radial highlight whose center slowly orbits, giving the card
 * a "breathing" wash of color. Sits behind everything else.
 */
@Composable
private fun AmbientGradient() {
    val transition = rememberInfiniteTransition(label = "ambient")
    // One full 360° rotation every 7s. Slow enough to feel ambient, not
    // distracting; long enough that the eye doesn't lock onto the motion.
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 2f * PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 7000, easing = LinearEasing)
        ),
        label = "ambient-angle"
    )
    val accent = MaterialTheme.colorScheme.primary
    Canvas(modifier = Modifier.fillMaxSize()) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        // Orbit radius is ~22% of the card's shorter dimension — keeps the
        // highlight on the card, not bleeding off the edges.
        val orbit = minOf(size.width, size.height) * 0.22f
        val highlightCenter = Offset(
            x = cx + cos(angle) * orbit,
            y = cy + sin(angle) * orbit
        )
        // Radial gradient: bright-ish at the moving center, fully transparent
        // by 70% of the card's diagonal. The 0.32 max alpha keeps things
        // subtle — the sparkles + icon should dominate, this is set dressing.
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(
                    accent.copy(alpha = 0.32f),
                    accent.copy(alpha = 0.10f),
                    Color.Transparent
                ),
                center = highlightCenter,
                radius = minOf(size.width, size.height) * 0.7f
            )
        )
    }
}

// ── Layer 2 ─────────────────────────────────────────────────────────────

/**
 * Static per-particle parameters. The animation comes from a shared phase
 * value; each particle reads its own per-frame state by adding [phaseOffset]
 * to the global phase. Position/size/rotation never change between renders,
 * which is why we can keep this as a plain data class outside composition.
 */
private data class Sparkle(
    val xFraction: Float,   // 0..1 horizontal anchor across the card
    val baseScale: Float,   // 0.6..1.4 — variety in particle size
    val phaseOffset: Float, // 0..1 — where in the loop this particle starts
    val swayAmplitude: Float, // 8..28 — px horizontal wiggle as it rises
    val rotationSpeed: Float  // 90..360 — degrees rotated over one loop
)

/**
 * The sparkle layer. [rememberInfiniteTransition] drives one shared
 * `time: Float` from 0..1, and each particle derives its own phase from
 * that. So 14 animated sparkles cost one timer + one Canvas redraw per frame.
 */
@Composable
private fun SparkleField() {
    // Generated once per composition. Deterministic-feeling but actually
    // pseudo-random — we just use index-based math so the same set comes
    // back if the composable recomposes from scratch.
    val sparkles = remember {
        List(14) { i ->
            val r1 = fract(i * 0.6180339887f) // golden-ratio sequence — well-distributed
            val r2 = fract(i * 0.7548776662f)
            val r3 = fract(i * 0.3678794412f)
            Sparkle(
                xFraction = 0.06f + r1 * 0.88f,
                baseScale = 0.6f + r2 * 0.8f,
                phaseOffset = r3,
                swayAmplitude = 8f + r1 * 20f,
                rotationSpeed = 90f + r2 * 270f
            )
        }
    }
    val transition = rememberInfiniteTransition(label = "sparkles")
    val time by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "sparkles-time"
    )
    val accent = MaterialTheme.colorScheme.primary
    Canvas(modifier = Modifier.fillMaxSize()) {
        // Particle base size scales with card size so the loader looks right
        // at any width (smaller phones, tablets, future split-screen, etc.)
        val baseRadiusPx = minOf(size.width, size.height) * 0.022f
        sparkles.forEach { s ->
            val phase = fract(time + s.phaseOffset)
            // Alpha curve: ease in 0→0.25, hold 0.25→0.7, ease out 0.7→1.
            // Sparkles are at full brightness for most of their life and only
            // fade at the very start and end.
            val alpha = when {
                phase < 0.25f -> phase / 0.25f
                phase > 0.7f -> 1f - (phase - 0.7f) / 0.3f
                else -> 1f
            }.coerceIn(0f, 1f)
            // Scale curve: small → big → small. Most visible in the middle.
            val scaleCurve = sin(phase * PI.toFloat())
            val radius = baseRadiusPx * s.baseScale * (0.5f + 0.5f * scaleCurve)
            // Position: start near bottom of card, drift up to ~10% past top.
            // The sway is a sine wave along the path so it doesn't just go
            // straight up — looks like rising bubbles or sparks.
            val startY = size.height * 1.05f
            val endY = -size.height * 0.05f
            val y = startY + (endY - startY) * phase
            val swayX = sin(phase * 2f * PI.toFloat()) * s.swayAmplitude
            val x = s.xFraction * size.width + swayX
            val rotationDeg = phase * s.rotationSpeed
            translate(left = x, top = y) {
                rotate(degrees = rotationDeg, pivot = Offset.Zero) {
                    drawSparkle(
                        radius = radius,
                        color = accent.copy(alpha = alpha * 0.95f)
                    )
                }
            }
        }
    }
}

/**
 * Draws a 4-pointed sparkle/star (like the AutoAwesome icon) centered at
 * the current canvas origin. The horizontal and vertical arms are longer
 * than the diagonals, which gives the classic "twinkle" silhouette rather
 * than a uniform 4-point star.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSparkle(
    radius: Float,
    color: Color
) {
    val outer = radius
    val inner = radius * 0.25f
    val path = Path().apply {
        moveTo(0f, -outer)        // top arm
        lineTo(inner, -inner)
        lineTo(outer, 0f)         // right arm
        lineTo(inner, inner)
        lineTo(0f, outer)         // bottom arm
        lineTo(-inner, inner)
        lineTo(-outer, 0f)        // left arm
        lineTo(-inner, -inner)
        close()
    }
    drawPath(path = path, color = color)
}

// ── Layer 3 ─────────────────────────────────────────────────────────────

/**
 * Centered AutoAwesome icon. Pulses alpha and scale together so it reads
 * as breathing rather than flashing. Same primary-violet tint as the rest
 * of the loader so the whole composition stays in one color family.
 */
@Composable
private fun CenterIcon() {
    val transition = rememberInfiniteTransition(label = "center-icon")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "center-icon-pulse"
    )
    val scale = 0.95f + pulse * 0.18f
    val alpha = 0.55f + pulse * 0.35f
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Filled.AutoAwesome,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary.copy(alpha = alpha),
            modifier = Modifier
                .size(72.dp)
                .scale(scale)
                .rotate(pulse * 10f - 5f) // subtle ±5° wobble
        )
    }
}

// ── Orb style ───────────────────────────────────────────────────────────

/**
 * A glossy 3D orb pulsing at the card's center, emitting concentric waves
 * that ripple outward to the edges on every beat.
 *
 * Composition (drawn back-to-front in one Canvas):
 *   1. **Waves** — `RING_COUNT` stroked circles, phase-staggered, each
 *      expanding from the orb's surface to the far corner and fading as it
 *      goes. Because they're emitted from behind the orb, they read as energy
 *      radiating *from* it.
 *   2. **Bloom** — a soft radial glow centred on the orb, pulsing with the
 *      beat so the whole center "breathes" light.
 *   3. **The orb** — a filled circle with an *off-center* radial gradient
 *      (highlight → mid → shade) to fake a sphere lit from the top-left, a
 *      thin rim light, and a small bright specular dot. The offset highlight
 *      is what sells the 3D read.
 *
 * One [rememberInfiniteTransition] drives a single `t in [0,1)`; the orb beat
 * and each ring's progress are derived from it, so they stay phase-locked and
 * the cost is one timer.
 */
@Composable
private fun OrbWaves() {
    val transition = rememberInfiniteTransition(label = "orb")
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = ORB_PERIOD_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "orb-time"
    )

    val primary = MaterialTheme.colorScheme.primary
    // Top-left lit sphere: bright near-white highlight, mid = the accent, and a
    // darkened accent for the far/shaded side.
    val highlight = lerp(primary, Color.White, 0.8f)
    val shade = lerp(primary, Color.Black, 0.55f)

    Canvas(modifier = Modifier.fillMaxSize()) {
        val center = Offset(size.width / 2f, size.height / 2f)
        // Far corner = the furthest a wave must travel to clear the card.
        val maxRadius = hypot(size.width / 2f, size.height / 2f)
        val baseOrbRadius = minOf(size.width, size.height) * 0.13f

        // Beat: oscillates RING_COUNT times per loop so a swell coincides with
        // each wave being emitted. 0..1, smooth (sine).
        val beat = 0.5f + 0.5f * sin(t * RING_COUNT * 2f * PI.toFloat())
        val orbRadius = baseOrbRadius * (1f + 0.10f * beat)

        // Light direction (unit-ish), pointing toward the top-left.
        val lightX = -0.42f
        val lightY = -0.46f

        // ── 1. Waves ──
        for (k in 0 until RING_COUNT) {
            val p = fract(t + k.toFloat() / RING_COUNT)
            // Ease-out: waves shoot out fast then slow as they near the edge.
            val eased = 1f - (1f - p) * (1f - p)
            val radius = orbRadius + (maxRadius - orbRadius) * eased
            // Fade from full at birth to nothing by the time they hit the edge.
            val life = (1f - p).coerceIn(0f, 1f)
            val alpha = life * life
            if (alpha <= 0.02f) continue
            // Soft wide halo + a brighter thin leading edge = a glowing ripple.
            drawCircle(
                color = primary.copy(alpha = alpha * 0.45f),
                radius = radius,
                center = center,
                style = Stroke(width = baseOrbRadius * (0.30f + 0.45f * life))
            )
            drawCircle(
                color = highlight.copy(alpha = alpha * 0.9f),
                radius = radius,
                center = center,
                style = Stroke(width = baseOrbRadius * 0.14f)
            )
        }

        // ── 2. Bloom ──
        val bloomRadius = orbRadius * 3.4f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    primary.copy(alpha = 0.40f * (0.65f + 0.35f * beat)),
                    primary.copy(alpha = 0.12f),
                    Color.Transparent
                ),
                center = center,
                radius = bloomRadius
            ),
            radius = bloomRadius,
            center = center
        )

        // ── 3. The orb ──
        val specular = Offset(
            x = center.x + lightX * orbRadius,
            y = center.y + lightY * orbRadius
        )
        // Sphere body: gradient centred on the highlight, larger than the orb so
        // the shade only reaches full darkness past the rim → smooth falloff.
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(highlight, primary, shade),
                center = specular,
                radius = orbRadius * 1.45f
            ),
            radius = orbRadius,
            center = center
        )
        // Thin rim light around the whole orb for a crisp, glassy edge.
        drawCircle(
            color = highlight.copy(alpha = 0.30f),
            radius = orbRadius,
            center = center,
            style = Stroke(width = orbRadius * 0.05f)
        )
        // Glossy specular hotspot near the light side, brightening on the beat.
        drawCircle(
            color = highlight.copy(alpha = 0.55f + 0.4f * beat),
            radius = orbRadius * 0.20f,
            center = Offset(
                x = center.x + lightX * orbRadius * 1.05f,
                y = center.y + lightY * orbRadius * 1.05f
            )
        )
    }
}

private const val ORB_PERIOD_MS = 2400
private const val RING_COUNT = 4

// ── Layer 4 ─────────────────────────────────────────────────────────────

/**
 * "Generating your card" line with animated dots so the user has a
 * lightweight "still alive" signal even during slow API calls. Cycles
 * through 0/1/2/3 trailing dots once per second.
 */
@Composable
private fun AnimatedCaption() {
    val transition = rememberInfiniteTransition(label = "caption")
    val tick by transition.animateFloat(
        initialValue = 0f,
        targetValue = 4f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "caption-tick"
    )
    val dotCount = tick.toInt().coerceIn(0, 3)
    val dots = ".".repeat(dotCount)
    Text(
        text = "Generating your card$dots",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
        fontWeight = FontWeight.Medium,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.size(4.dp))
}

// ── helpers ─────────────────────────────────────────────────────────────

/** Fractional part. `fract(1.7) == 0.7`. Used to keep phases in [0, 1). */
private fun fract(v: Float): Float = v - kotlin.math.floor(v)
