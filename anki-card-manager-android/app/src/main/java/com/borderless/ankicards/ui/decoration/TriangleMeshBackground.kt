package com.borderless.ankicards.ui.decoration

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import com.borderless.ankicards.ui.theme.LocalIsDarkTheme
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Subtle, slowly-drifting purple triangle mesh — port of the desktop project's
 * Delaunay-style background (templates/js/generator.js).
 *
 * Design choices vs desktop:
 *  - Static topology: points and triangles are computed once.
 *  - Slow ambient drift only (no touch tracking — saves battery and avoids
 *    the desktop's mouse-warp interaction which doesn't translate to mobile).
 *  - Purple hue centered around HSL 258°, depth-shaded by per-vertex z.
 *
 * Performance: ~150 triangles per frame, single Canvas, no per-frame allocation
 * after the initial mesh build. Negligible impact on modern devices.
 */
@Composable
fun TriangleMeshBackground(
    modifier: Modifier = Modifier,
    cellSizePx: Float = 220f,
    jitter: Float = 0.55f,
    edgeAlpha: Float = 0.12f,
    seed: Long = 0xCAFEBABEL,
    content: @Composable BoxScope.() -> Unit = {}
) {
    // The mesh is a heavy decorative effect that only reads well on the dark
    // theme — on light surfaces it muddies the palette. Skip the Canvas
    // entirely in light mode; just render the children with no background.
    if (!LocalIsDarkTheme.current) {
        Box(modifier = modifier.fillMaxSize()) { content() }
        return
    }

    val mesh = remember(seed, cellSizePx, jitter) {
        buildMesh(seed = seed, cellSize = cellSizePx, jitter = jitter)
    }

    // Single time variable, advanced via withFrameNanos. Drives the gentle
    // sin/cos drift each vertex applies on top of its base position.
    var timeMs by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) {
            withFrameNanos { now -> timeMs = (now - start) / 1_000_000f }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            if (w <= 0f || h <= 0f) return@Canvas

            // Resolved per-vertex positions, reused each frame.
            val xs = mesh.scratchX
            val ys = mesh.scratchY
            for (i in mesh.points.indices) {
                val p = mesh.points[i]
                xs[i] = p.nx * w + sin(timeMs * AMBIENT_FREQ + p.phase) * AMBIENT_AMPLITUDE
                ys[i] = p.ny * h + cos(timeMs * AMBIENT_FREQ * 0.7f + p.phase) * AMBIENT_AMPLITUDE
            }

            val path = Path()
            for (tri in mesh.triangles) {
                val (i0, i1, i2) = tri
                path.reset()
                path.moveTo(xs[i0], ys[i0])
                path.lineTo(xs[i1], ys[i1])
                path.lineTo(xs[i2], ys[i2])
                path.close()

                val avgZ = (mesh.points[i0].z + mesh.points[i1].z + mesh.points[i2].z) / 3f
                drawPath(path = path, color = triFill(avgZ))
                drawPath(path = path, color = edgeColor(edgeAlpha), style = Stroke(width = 0.5f))
            }
        }
        content()
    }
}

// ── Mesh building ───────────────────────────────────────────────────────────

private data class MeshPoint(val nx: Float, val ny: Float, val z: Float, val phase: Float)
private data class MeshTri(val i: Int, val j: Int, val k: Int)
private data class Mesh(
    val points: List<MeshPoint>,
    val triangles: List<MeshTri>,
    val scratchX: FloatArray,
    val scratchY: FloatArray
)

private fun buildMesh(seed: Long, cellSize: Float, jitter: Float): Mesh {
    val rng = Random(seed)
    val refW = 1080f
    val refH = 2400f
    val cols = ((refW / cellSize).toInt() + 2)
    val rows = ((refH / cellSize).toInt() + 2)
    val stride = cols + 2

    val pts = ArrayList<MeshPoint>((rows + 2) * (cols + 2))
    for (r in -1..rows) {
        for (c in -1..cols) {
            val nx = (c * cellSize + (rng.nextFloat() - 0.5f) * cellSize * jitter) / refW
            val ny = (r * cellSize + (rng.nextFloat() - 0.5f) * cellSize * jitter) / refH
            val z = rng.nextFloat()
            val phase = rng.nextFloat() * (Math.PI * 2).toFloat()
            pts += MeshPoint(nx, ny, z, phase)
        }
    }

    val tris = ArrayList<MeshTri>()
    for (r in 0 until rows + 1) {
        for (c in 0 until cols + 1) {
            val i = r * stride + c
            val j = i + 1
            val k = i + stride
            val l = k + 1
            if (j >= pts.size || k >= pts.size || l >= pts.size) continue
            if ((r + c) % 2 == 0) {
                tris += MeshTri(i, j, k)
                tris += MeshTri(j, l, k)
            } else {
                tris += MeshTri(i, j, l)
                tris += MeshTri(i, l, k)
            }
        }
    }
    return Mesh(
        points = pts,
        triangles = tris,
        scratchX = FloatArray(pts.size),
        scratchY = FloatArray(pts.size)
    )
}

// ── Color helpers ───────────────────────────────────────────────────────────

/** Depth-shaded purple fill. Mirrors `triColor` from desktop generator.js. */
private fun triFill(avgZ: Float): Color {
    val lightness = 0.16f + avgZ * 0.20f      // 16-36% lightness
    val saturation = 0.55f + avgZ * 0.20f
    val alpha = 0.55f + avgZ * 0.30f
    return hslToColor(hue = 258f, saturation = saturation, lightness = lightness, alpha = alpha)
}

private fun edgeColor(baseAlpha: Float): Color =
    hslToColor(hue = 258f, saturation = 0.75f, lightness = 0.70f, alpha = baseAlpha)

/** Minimal HSL → RGB → Compose Color. */
private fun hslToColor(hue: Float, saturation: Float, lightness: Float, alpha: Float): Color {
    val h = (hue % 360f) / 60f
    val c = (1f - kotlin.math.abs(2f * lightness - 1f)) * saturation
    val x = c * (1f - kotlin.math.abs(h % 2f - 1f))
    val m = lightness - c / 2f
    val (r, g, b) = when {
        h < 1f -> Triple(c, x, 0f)
        h < 2f -> Triple(x, c, 0f)
        h < 3f -> Triple(0f, c, x)
        h < 4f -> Triple(0f, x, c)
        h < 5f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    return Color(red = r + m, green = g + m, blue = b + m, alpha = alpha)
}

private const val AMBIENT_FREQ = 0.0006f       // radians per ms
private const val AMBIENT_AMPLITUDE = 6f       // px peak displacement
