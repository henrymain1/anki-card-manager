package com.borderless.ankicards.ui.staging

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.graphics.Color
import com.borderless.ankicards.data.anki.FlashCardsContract
import com.borderless.ankicards.data.queue.QueueItem
import com.borderless.ankicards.ui.common.CardPreview
import com.borderless.ankicards.ui.common.GameButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxDetailScreen(
    viewModel: StagingViewModel,
    itemId: String,
    onBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    val item = state.items.firstOrNull { it.id == itemId }

    // The first frame after navigation, the new ViewModel's StateFlow returns
    // its empty initial value before the upstream emits — `item` is briefly null
    // even when the queue actually has it. Only treat null as "gone" once we
    // have confirmed the item existed at least once.
    var hasSeenItem by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(item) {
        if (item != null) hasSeenItem = true
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.onPermissionGranted() else viewModel.onPermissionDenied()
    }

    LaunchedEffect(state.needsAnkiPermission) {
        if (state.needsAnkiPermission) {
            viewModel.onPermissionRequestConsumed()
            permissionLauncher.launch(FlashCardsContract.PERMISSION_READ_WRITE)
        }
    }

    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.onMessageShown()
        }
    }

    // Pop back only when the item disappears AFTER we've seen it — i.e. the user
    // approved or dismissed it. Avoids the race where the detail screen pops
    // itself in the brief window before state hydrates.
    LaunchedEffect(item, hasSeenItem) {
        if (hasSeenItem && item == null) onBack()
    }

    Scaffold(
        // MainScaffold already applied system-bar insets; don't double-apply.
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        if (item == null) {
            // Either we haven't seen the item yet (state still hydrating) or it
            // was just approved/dismissed. Either way: render nothing; the
            // LaunchedEffect handles navigation when appropriate.
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            // Inline header — sub-flow page so it keeps a back arrow.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 16.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Text(
                    text = item.card?.front?.ifBlank { item.input } ?: item.input,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    maxLines = 1
                )
            }

        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            when (item.state) {
                QueueItem.State.Generating -> Generating(item)
                QueueItem.State.Failed -> Failed(item, viewModel)
                QueueItem.State.Ready, QueueItem.State.Approving -> Ready(item, viewModel)
            }
        } // end inner Column (horizontal-padded body)
        } // end outer Column (header + body)
    }
}

@Composable
private fun Generating(item: QueueItem) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "Generating card for \"${item.input}\"…",
            style = MaterialTheme.typography.bodyLarge
        )
        CircularProgressIndicator(strokeWidth = 2.dp)
        Text(
            "You can leave this screen — it'll keep working in the background.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
    }
}

@Composable
private fun Failed(item: QueueItem, viewModel: StagingViewModel) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "Generation failed",
            style = MaterialTheme.typography.titleLarge
        )
        Text(
            item.errorMessage ?: "Unknown error",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
        )
        GameButton(
            onClick = { viewModel.onRetry(item.id) },
            color = RetryAmber,
            contentColor = Color.White
        ) { Text("Retry") }
        GameButton(
            onClick = { viewModel.onDismiss(item.id) },
            color = Color(0xFF3A3A48),
            contentColor = Color.White
        ) { Text("Dismiss") }
    }
}

private val ApproveGreen = Color(0xFF22C55E)
private val RetryAmber = Color(0xFFF59E0B)
private val NeutralGray = Color(0xFF3A3A48)

@Composable
private fun Ready(item: QueueItem, viewModel: StagingViewModel) {
    val card = item.card ?: return
    val isApproving = item.state == QueueItem.State.Approving

    CardPreview(
        card = card,
        isLoadingImage = false,
        onCardChanged = { updated -> viewModel.onCardEdited(item.id, updated) }
    )

    if (item.errorMessage != null) {
        Text(
            item.errorMessage,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
    }

    GameButton(
        onClick = { viewModel.onApprove(item.id) },
        enabled = !isApproving,
        color = ApproveGreen,
        contentColor = Color.White
    ) {
        Text(if (isApproving) "Adding…" else "Approve & Add to Deck")
    }
    GameButton(
        onClick = { viewModel.onDismiss(item.id) },
        enabled = !isApproving,
        color = NeutralGray,
        contentColor = Color.White
    ) { Text("Dismiss") }
}
