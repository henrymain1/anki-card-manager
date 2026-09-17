package com.borderless.ankicards.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Style
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.borderless.ankicards.di.AppContainer

/**
 * App shell that wraps the [AppNavHost] in a [Scaffold] with a bottom
 * navigation bar.
 *
 * Four tabs map onto four root destinations:
 *  - Decks    → [Routes.DECKS]
 *  - Generate → `generator` (the card creation flow, still the app's centerpiece)
 *  - Designs  → [Routes.CARD_TYPES] (card-type list, opens the visual designer)
 *  - Settings → [Routes.SETTINGS]
 *
 * The bottom bar hides itself on "deep" screens — the visual designer, inbox
 * detail, wordlist — because those screens benefit from full vertical space
 * and the user is in a focused sub-flow, not navigating top-level.
 *
 * Tab items are a custom composable instead of Material3's [NavigationBarItem]
 * because the standard component's selection pill only wraps the icon. We
 * want the pill to encompass the *whole* tab (icon + label) so the active
 * state reads as a tab-sized affordance, not a tiny chip behind a glyph.
 */
@Composable
fun MainScaffold(
    navController: NavHostController,
    container: AppContainer
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination: NavDestination? = backStackEntry?.destination
    val showBottomBar = currentDestination?.shouldShowBottomBar() ?: true

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    BottomTab.entries.forEach { tab ->
                        // Compare against the *base* route (everything before
                        // the optional `?arg={arg}` suffix). The Generator's
                        // registered route is "generator?word={word}" but the
                        // tab's route is just "generator" — an exact-match
                        // check would always come back false there, the
                        // !selected guard would pass, and tapping Generate
                        // while already on it would re-navigate (replaying
                        // the slide animation). Stripping the query lets the
                        // selected check match parameterized routes too.
                        val selected = currentDestination
                            ?.hierarchy
                            ?.any { it.route?.substringBefore('?') == tab.route } == true
                        TabItem(
                            selected = selected,
                            icon = tab.icon,
                            label = tab.label,
                            onClick = {
                                if (!selected) {
                                    navController.navigate(tab.route) {
                                        // Standard bottom-nav pattern: pop back to the
                                        // start destination, save & restore state per
                                        // tab so each tab feels independent.
                                        popUpTo(navController.graph.startDestinationId) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    ) { padding ->
        AppNavHost(
            navController = navController,
            container = container,
            modifier = Modifier.padding(padding)
        )
    }
}

/**
 * A single bottom-nav tab. The selection indicator is a rounded pill that
 * wraps the entire icon+label column — not just the icon, which is the
 * stock Material3 behavior. Uses `Modifier.selectable` for correct a11y
 * semantics (tablist + tab role).
 *
 * Sits inside a [NavigationBar] which still provides the container height,
 * surface color, elevation, and system-bar insets.
 */
@Composable
private fun RowScope.TabItem(
    selected: Boolean,
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    val contentColor =
        if (selected) MaterialTheme.colorScheme.onSecondaryContainer
        else MaterialTheme.colorScheme.onSurfaceVariant
    val background =
        if (selected) MaterialTheme.colorScheme.secondaryContainer
        else Color.Transparent

    // NB: do NOT add Modifier.fillMaxHeight() here. NavigationBar's internal
    // Row only sets a *min* height (80dp) — maxHeight is unbounded — so
    // fillMaxHeight will grow the pill until the surrounding Scaffold caps
    // it, which is roughly the whole screen. Let content size the pill
    // instead.
    Box(
        modifier = Modifier
            .weight(1f)
            // Outer horizontal padding keeps adjacent pills from touching.
            // Outer vertical padding gives a small gap above/below the pill
            // so it doesn't bleed into the bar's edges.
            .padding(horizontal = 6.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(background)
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.Tab
            )
            // Inner padding is the pill's "filling" — what gives it visible
            // height/width around the icon+label content.
            .padding(horizontal = 8.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = contentColor,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
            )
        }
    }
}

/**
 * The bottom-nav tabs in display order.
 *
 * Generate is centered (second of four) because it's the highest-frequency
 * destination — putting it dead-center on the bottom bar makes it the easiest
 * to thumb-tap on a phone, which matters when adding many cards in a row.
 */
private enum class BottomTab(
    val route: String,
    val label: String,
    val icon: ImageVector
) {
    Decks(Routes.DECKS, "Decks", Icons.Filled.FolderOpen),
    Generate("generator", "Generate", Icons.Filled.AutoAwesome),
    Designs(Routes.CARD_TYPES, "Designs", Icons.Filled.Style),
    Settings(Routes.SETTINGS, "Settings", Icons.Filled.Settings);
}

/**
 * Routes where the bottom bar should be hidden. Anything that's a focused
 * sub-flow (composer, detail screen) wants the full screen.
 */
private fun NavDestination.shouldShowBottomBar(): Boolean {
    val r = route ?: return true
    return when {
        r.startsWith("card-builder") -> false
        r.startsWith("inbox/{") -> false
        r == Routes.WORDLIST -> false
        else -> true
    }
}
