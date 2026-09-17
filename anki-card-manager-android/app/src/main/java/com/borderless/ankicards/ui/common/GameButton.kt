package com.borderless.ankicards.ui.common

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Duolingo-style chunky 3D button.
 *
 * Visually two stacked layers:
 *  - bottom "shadow" face (a darker variant of [color]) — fixed in place
 *  - top "key" face (in [color]) — sits raised by [depth]
 *
 * On press, the top face translates down by [depth] (collapsing onto the
 * shadow) for a satisfying tactile click. On release it springs back.
 *
 * The visible button height equals [height] regardless of press state — the
 * outer Box reserves [height] + [depth] of vertical space, so layout doesn't
 * shift when the button is pressed.
 */
@Composable
fun GameButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = MaterialTheme.colorScheme.primary,
    contentColor: Color = MaterialTheme.colorScheme.onPrimary,
    height: Dp = 52.dp,
    depth: Dp = 5.dp,
    cornerRadius: Dp = 16.dp,
    content: @Composable RowScope.() -> Unit
) {
    var pressed by remember { mutableStateOf(false) }

    val activeColor = if (enabled) color else color.muted()
    val activeContentColor = if (enabled) contentColor else contentColor.copy(alpha = 0.6f)
    val shadowColor = activeColor.darker(0.65f)

    val pressOffset by animateDpAsState(
        targetValue = if (pressed && enabled) depth else 0.dp,
        animationSpec = tween(durationMillis = 60),
        label = "pressOffset"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height + depth)
    ) {
        // Bottom shadow face — fills the full reserved area.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(cornerRadius))
                .background(shadowColor)
        )
        // Top key face — sits raised by `depth`, slides down on press.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .offset(y = pressOffset)
                .clip(RoundedCornerShape(cornerRadius))
                .background(activeColor)
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectTapGestures(
                        onPress = {
                            pressed = true
                            val released = tryAwaitRelease()
                            pressed = false
                            if (released) onClick()
                        }
                    )
                }
                .padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            CompositionLocalProvider(LocalContentColor provides activeContentColor) {
                ProvideTextStyle(MaterialTheme.typography.labelLarge.copy(fontSize = 16.sp)) {
                    content()
                }
            }
        }
    }
}

// ── Color helpers ───────────────────────────────────────────────────────────

private fun Color.darker(factor: Float): Color =
    Color(
        red = (red * factor).coerceIn(0f, 1f),
        green = (green * factor).coerceIn(0f, 1f),
        blue = (blue * factor).coerceIn(0f, 1f),
        alpha = alpha
    )

private fun Color.muted(): Color =
    copy(
        red = red * 0.5f + 0.25f,
        green = green * 0.5f + 0.25f,
        blue = blue * 0.5f + 0.25f
    )
