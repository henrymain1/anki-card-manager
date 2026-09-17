package com.borderless.ankicards.ui.staging

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.borderless.ankicards.data.anki.FlashCardsContract
import com.borderless.ankicards.data.queue.QueueItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StagingScreen(
    viewModel: StagingViewModel,
    onBack: () -> Unit,
    onItemClick: (String) -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

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

    Scaffold(
        // MainScaffold already applied system-bar insets; don't double-apply.
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Inline header — sub-flow page so it keeps a back arrow. The
            // previous TopAppBar pushed the title ~88dp below the status
            // bar; this Row puts it at the same level as the generator.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 4.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Text(
                    text = "Inbox (${state.items.size})",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                if (state.items.isNotEmpty()) {
                    IconButton(onClick = viewModel::onClearAll) {
                        Icon(Icons.Filled.DeleteSweep, contentDescription = "Clear all")
                    }
                }
            }

            if (state.items.isEmpty()) {
                EmptyState()
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(state.items, key = { it.id }) { item ->
                        InboxRow(
                            item = item,
                            onClick = { onItemClick(item.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "Inbox is empty",
                style = MaterialTheme.typography.titleLarge
            )
            Text(
                "Long-press a word in Pleco (or any app), tap Share, " +
                    "and pick \"Anki Card Manager\". Cards will queue up here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
private fun InboxRow(
    item: QueueItem,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Thumbnail(item)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.card?.front?.ifBlank { item.input } ?: item.input,
                    style = MaterialTheme.typography.titleLarge
                )
                if (!item.card?.chinese.isNullOrBlank()) {
                    Text(
                        item.card!!.chinese,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                    )
                }
                Spacer(Modifier.height(2.dp))
                StatusLine(item)
            }
        }
    }
}

@Composable
private fun Thumbnail(item: QueueItem) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = Modifier
            .size(width = 84.dp, height = 48.dp)
            .clip(shape),
        contentAlignment = Alignment.Center
    ) {
        val bytes = item.card?.imageBytes
        when {
            bytes != null -> AsyncImage(
                model = bytes,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            item.state == QueueItem.State.Generating ||
                item.state == QueueItem.State.Approving ->
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
            else -> Text(
                "—",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
            )
        }
    }
}

@Composable
private fun StatusLine(item: QueueItem) {
    val (text, alpha) = when (item.state) {
        QueueItem.State.Generating -> "Generating…" to 0.7f
        QueueItem.State.Ready -> (item.errorMessage ?: "Ready to approve") to 0.7f
        QueueItem.State.Failed -> (item.errorMessage ?: "Failed") to 0.9f
        QueueItem.State.Approving -> "Adding to AnkiDroid…" to 0.7f
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)
    )
}
