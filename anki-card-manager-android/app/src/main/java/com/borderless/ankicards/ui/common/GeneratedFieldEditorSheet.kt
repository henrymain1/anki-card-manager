@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.borderless.ankicards.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.borderless.ankicards.data.gemini.GeneratedMedia
import com.borderless.ankicards.domain.recipe.CardField
import com.borderless.ankicards.domain.recipe.FieldGenerator

/**
 * Bottom sheet for tweaking one generated field. Opens when the user taps a
 * placed box on the [GeneratedCardSurface].
 *
 * Text fields get a multiline editor; image fields show a thumbnail and a
 * "Regenerate" button; audio fields just expose regenerate. In all cases the
 * Done button commits the (already-streamed) edit back to the host's state.
 */
@Composable
fun GeneratedFieldEditorSheet(
    field: CardField,
    textValue: String,
    media: GeneratedMedia?,
    errorMessage: String?,
    isRegenerating: Boolean,
    onTextChanged: (String) -> Unit,
    onRegenerate: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var local by remember(field.key, textValue) { mutableStateOf(textValue) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(
                    field.label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                FilledTonalButton(onClick = onRegenerate, enabled = !isRegenerating) {
                    if (isRegenerating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(Icons.Filled.Refresh, contentDescription = null,
                            modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(6.dp))
                        Text("Regenerate")
                    }
                }
            }

            when (field.generator) {
                is FieldGenerator.ImageGen ->
                    Text(
                        text = when (media) {
                            is GeneratedMedia.Image -> "An image has been generated. Tap Regenerate to try again."
                            else -> "No image yet. Tap Regenerate to create one."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                is FieldGenerator.Tts ->
                    Text(
                        text = when (media) {
                            is GeneratedMedia.Audio -> "Audio is ready (${media.bytes.size / 1024} KB)."
                            else -> "No audio yet. Tap Regenerate to create it."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                else -> OutlinedTextField(
                    value = local,
                    onValueChange = {
                        local = it
                        onTextChanged(it)
                    },
                    label = { Text("Value") },
                    supportingText = {
                        Text(field.description.ifBlank { "Generated value for this field" })
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                )
            }

            if (errorMessage != null) {
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(Modifier.size(4.dp))
            FilledTonalButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text("Done")
            }
            Spacer(Modifier.size(8.dp))
        }
    }
}
