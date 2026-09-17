package com.borderless.ankicards.ui.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import com.borderless.ankicards.data.gemini.GeminiModelOption
import com.borderless.ankicards.data.gemini.GeminiModels
import com.borderless.ankicards.data.gemini.ProThinkingLevel
import com.borderless.ankicards.data.settings.ImageSourcePreference
import com.borderless.ankicards.data.settings.LoaderStylePreference
import com.borderless.ankicards.data.settings.ThemeMode
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // After save: show "Saved ✓" briefly, then navigate back.
    LaunchedEffect(state.justSaved) {
        if (state.justSaved) {
            delay(700)
            onBack()
        }
    }

    // Zero the inner insets — MainScaffold already applied them once.
    Scaffold(contentWindowInsets = WindowInsets(0)) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = 16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Inline header — same pattern as the generator screen. No
            // TopAppBar because Settings is reached via the bottom nav,
            // not a sub-flow; no back arrow needed.
            Text(
                text = "Settings",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 8.dp)
            )

            ThemeSection(
                themeMode = state.themeMode,
                onChange = viewModel::onThemeModeChanged
            )

            LoaderStyleSection(
                selected = state.loaderStyle,
                onChange = viewModel::onLoaderStyleChanged
            )

            ModelDropdown(
                label = "Text model",
                options = GeminiModels.textOptions,
                selectedId = state.textModel,
                onSelect = viewModel::onTextModelChanged
            )

            // Pro thinking knob — only shown when the selected text model
            // is a Pro variant. Flash models still send requests with the
            // model's default thinking and ignore this setting.
            if (GeminiModels.isProTextModel(state.textModel)) {
                ProThinkingSection(
                    selected = state.proThinkingLevel,
                    onChange = viewModel::onProThinkingLevelChanged
                )
            }

            ImageSourceSection(
                selected = state.imageSource,
                onChange = viewModel::onImageSourceChanged
            )

            // Which generator to use is moot when the user has ruled
            // generation out entirely — same show-only-when-relevant pattern
            // as the Pro thinking knob above.
            if (state.imageSource != ImageSourcePreference.SearchOnly) {
                ModelDropdown(
                    label = "Image model",
                    options = GeminiModels.imageOptions,
                    selectedId = state.imageModel,
                    onSelect = viewModel::onImageModelChanged
                )
            }

            OutlinedTextField(
                value = state.proxyUrl,
                onValueChange = viewModel::onProxyUrlChanged,
                label = { Text("Proxy URL") },
                placeholder = { Text("https://your-project.vercel.app") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = state.proxyToken,
                onValueChange = viewModel::onProxyTokenChanged,
                label = { Text("Proxy Token") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = state.deckName,
                onValueChange = viewModel::onDeckNameChanged,
                label = { Text("Deck Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = state.modelName,
                onValueChange = viewModel::onModelNameChanged,
                label = { Text("Note Type (Model) Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            SaveButton(
                isSaving = state.isSaving,
                justSaved = state.justSaved,
                onClick = viewModel::onSaveClicked
            )
        }
    }
}

@Composable
private fun ThemeSection(
    themeMode: ThemeMode,
    onChange: (ThemeMode) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "Theme",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeMode.entries.forEach { mode ->
                FilterChip(
                    selected = themeMode == mode,
                    onClick = { onChange(mode) },
                    label = { Text(mode.displayName) }
                )
            }
        }
    }
}

/**
 * FilterChip row for picking the card-generation loading animation. Mirrors
 * the [ThemeSection] pattern — write-through, no Save needed. "Surprise me"
 * (Random) rolls a different style each generation.
 */
/**
 * FilterChip row for choosing where card images come from, with a caption
 * explaining the selected option's trade-off. Write-through like the other
 * chip sections — no Save button.
 *
 * The caption matters more here than for the other settings: the three
 * options differ in speed, cost, and whether a card can end up with no image
 * at all, and none of that is guessable from a three-word chip label.
 */
@Composable
private fun ImageSourceSection(
    selected: ImageSourcePreference,
    onChange: (ImageSourcePreference) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "Card images",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ImageSourcePreference.entries.forEach { source ->
                FilterChip(
                    selected = selected == source,
                    onClick = { onChange(source) },
                    label = { Text(source.displayName) }
                )
            }
        }
        Text(
            selected.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun LoaderStyleSection(
    selected: LoaderStylePreference,
    onChange: (LoaderStylePreference) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "Loading animation",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LoaderStylePreference.entries.forEach { style ->
                FilterChip(
                    selected = selected == style,
                    onClick = { onChange(style) },
                    label = { Text(style.displayName) }
                )
            }
        }
    }
}

/**
 * FilterChip row for picking the Pro thinking level. Mirrors the
 * [ThemeSection] pattern — write-through, no Save needed. Only visible
 * when the selected text model is a Pro variant; non-Pro requests don't
 * thread this value through.
 */
@Composable
private fun ProThinkingSection(
    selected: ProThinkingLevel,
    onChange: (ProThinkingLevel) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "Pro thinking",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ProThinkingLevel.entries.forEach { level ->
                FilterChip(
                    selected = selected == level,
                    onClick = { onChange(level) },
                    label = { Text(level.displayName) }
                )
            }
        }
    }
}

@Composable
private fun SaveButton(
    isSaving: Boolean,
    justSaved: Boolean,
    onClick: () -> Unit
) {
    val containerColor by animateColorAsState(
        targetValue = if (justSaved) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
        } else {
            MaterialTheme.colorScheme.primary
        },
        label = "saveButtonColor"
    )

    Button(
        onClick = onClick,
        enabled = !isSaving && !justSaved,
        colors = ButtonDefaults.buttonColors(containerColor = containerColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        when {
            isSaving -> CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary
            )
            justSaved -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Text("Saved")
            }
            else -> Text("Save")
        }
    }
}

/**
 * A tappable card that shows the currently-selected Gemini model and
 * opens a [DropdownMenu] of options on tap. Each option's id is opaque
 * to the UI — the dropdown shows display name + subtitle and emits the
 * id via [onSelect]. Used for both the text and image model pickers.
 *
 * Selection writes through immediately (no "Save" needed) — same as
 * the theme picker. The next AI call reads the new model from settings.
 */
@Composable
private fun ModelDropdown(
    label: String,
    options: List<GeminiModelOption>,
    selectedId: String,
    onSelect: (String) -> Unit
) {
    val selected = remember(options, selectedId) {
        options.firstOrNull { it.id == selectedId } ?: options.first()
    }
    var expanded by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Card(
            shape = RoundedCornerShape(10.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            ),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = true }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = selected.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Icon(
                    imageVector = Icons.Filled.ArrowDropDown,
                    contentDescription = "Change $label",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = option.displayName,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = if (option.id == selectedId) FontWeight.SemiBold
                                             else FontWeight.Normal
                            )
                        },
                        leadingIcon = if (option.id == selectedId) {
                            {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        } else null,
                        onClick = {
                            if (option.id != selectedId) onSelect(option.id)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}
