package com.borderless.ankicards

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import com.borderless.ankicards.data.settings.ThemeMode
import com.borderless.ankicards.ui.MainScaffold
import com.borderless.ankicards.ui.Routes
import com.borderless.ankicards.ui.theme.AnkiCardManagerTheme

class MainActivity : ComponentActivity() {

    /** Bumped whenever a new "navigate to" intent arrives, so Compose re-runs the effect. */
    private var pendingNavigation by mutableStateOf<NavRequest?>(null)

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // Result ignored — if denied, the app simply won't post notifications.
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        maybeRequestNotificationPermission()
        pendingNavigation = readDestination(intent)

        val container = (application as AnkiCardsApp).container

        setContent {
            val themeMode by container.settingsRepository.themeMode
                .collectAsStateWithLifecycle(initialValue = ThemeMode.System)
            AnkiCardManagerTheme(themeMode = themeMode) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()

                    LaunchedEffect(pendingNavigation) {
                        val req = pendingNavigation ?: return@LaunchedEffect
                        when (req.destination) {
                            DEST_INBOX -> navController.navigate(Routes.INBOX)
                        }
                        pendingNavigation = null
                    }

                    MainScaffold(navController = navController, container = container)
                }
            }
        }
    }

    /** Warm-start: a notification was tapped while the app was already running. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingNavigation = readDestination(intent)
    }

    private fun readDestination(intent: Intent?): NavRequest? {
        val dest = intent?.getStringExtra(EXTRA_NAVIGATE_TO) ?: return null
        // Token defends against the same intent re-firing on rotation/configuration change.
        return NavRequest(dest, token = System.nanoTime())
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private data class NavRequest(val destination: String, val token: Long)

    companion object {
        const val EXTRA_NAVIGATE_TO = "extra_navigate_to"
        const val DEST_INBOX = "inbox"
    }
}
