package com.borderless.ankicards.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import com.borderless.ankicards.data.settings.ThemeMode

private val LightColors = lightColorScheme(
    primary = Primary,
    onPrimary = OnPrimary,
    surface = Surface,
    onSurface = OnSurface,
    surfaceVariant = SurfaceVariant,
    outline = Outline
)

private val DarkColors = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    outline = DarkOutline
)

/**
 * Reflects the *effective* dark/light state after the user's [ThemeMode]
 * preference is combined with the OS setting. Composables can read this to
 * adapt behavior (e.g. drop a heavy decorative background in light mode).
 *
 * `static` because the value changes at most a handful of times per session
 * — no need for fine-grained recomposition tracking.
 */
val LocalIsDarkTheme = staticCompositionLocalOf { false }

@Composable
fun AnkiCardManagerTheme(
    themeMode: ThemeMode = ThemeMode.System,
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val darkTheme = when (themeMode) {
        ThemeMode.System -> systemDark
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    CompositionLocalProvider(LocalIsDarkTheme provides darkTheme) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = AppTypography,
            content = content
        )
    }
}
