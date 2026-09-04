package me.pngwasi.plume.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.pngwasi.plume.ui.icons.PlumeIcons

/**
 * Model field backed by the provider's live catalogue.
 *
 * The text field stays editable throughout: catalogues go stale, private deployments expose names
 * that never appear in `/models`, and OpenRouter alone lists hundreds. Browsing is the convenience;
 * typing is the guarantee.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelField(
    value: String,
    state: ModelsState,
    error: String?,
    onValueChange: (String) -> Unit,
    onReload: () -> Unit,
) {
    var browsing by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    Column {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Model *") },
            singleLine = true,
            isError = error != null,
            trailingIcon = {
                when (state) {
                    ModelsState.Loading -> CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                    else -> IconButton(onClick = onReload) {
                        Icon(PlumeIcons.Refresh, contentDescription = "Reload models")
                    }
                }
            },
            supportingText = {
                Text(error ?: modelHint(state))
            },
        )

        if (state is ModelsState.Loaded) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { browsing = true }) {
                    Text("Browse ${state.models.size} models")
                }
            }
        }
    }

    if (browsing && state is ModelsState.Loaded) {
        ModalBottomSheet(
            onDismissRequest = { browsing = false },
            sheetState = sheetState,
        ) {
            ModelBrowser(
                models = state.models,
                selected = value,
                onPick = {
                    onValueChange(it)
                    browsing = false
                },
            )
        }
    }
}

@Composable
private fun ModelBrowser(models: List<String>, selected: String, onPick: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(query, models) {
        if (query.isBlank()) models else models.filter { it.contains(query.trim(), ignoreCase = true) }
    }

    Column(modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
        Text("Choose a model", style = MaterialTheme.typography.titleLarge)

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            placeholder = { Text("Filter models") },
            singleLine = true,
        )

        if (filtered.isEmpty()) {
            Text(
                text = "Nothing matches \"$query\". You can still type the name in the field.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 16.dp),
            )
        } else {
            LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                items(filtered, key = { it }) { model ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            onClick = { onPick(model) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = model,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (model == selected) {
                                Icon(
                                    PlumeIcons.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp),
                                )
                            } else {
                                Box(modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun modelHint(state: ModelsState): String = when (state) {
    ModelsState.Idle -> "Required."
    ModelsState.Loading -> "Loading the provider's model list…"
    is ModelsState.Loaded -> "Pick from the list, or type any model name."
    is ModelsState.Unavailable -> state.reason
}
