package me.pngwasi.plume.ime

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardReturn
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.pngwasi.plume.data.Languages

/**
 * The keyboard panel.
 *
 * Everything renders inline — an IME owns its own window, so dialogs and bottom sheets either fail
 * to show or appear behind it. Fixed height, because a panel that resizes as its state changes
 * makes the host app's layout jump under the user.
 */
@Composable
fun ImePanel(
    state: ImeState,
    onRevise: () -> Unit,
    onTranslate: () -> Unit,
    onPickLanguage: (String) -> Unit,
    onCancelPicker: () -> Unit,
    onOpenSettings: () -> Unit,
    onBackToKeyboard: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().height(PanelHeight),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp)) {
            Header(state = state, onBackToKeyboard = onBackToKeyboard, onOpenSettings = onOpenSettings)

            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                when (state) {
                    is ImeState.Ready -> ReadyBody(state, onRevise, onTranslate)
                    is ImeState.PickLanguage -> PickerBody(state, onPickLanguage, onCancelPicker)
                    is ImeState.Working -> WorkingBody(state.note)
                    is ImeState.Failed -> FailedBody(state, onRevise, onPickLanguage, onOpenSettings)
                }
            }
        }
    }
}

private val PanelHeight = 248.dp

@Composable
private fun Header(state: ImeState, onBackToKeyboard: () -> Unit, onOpenSettings: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "PLUME",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )

        Box(modifier = Modifier.weight(1f)) {
            val scopeLabel = (state as? ImeState.Ready)?.scope?.let {
                if (it == ActionScope.Selection) "selection" else "whole message"
            }
            if (scopeLabel != null) {
                Text(
                    text = "· $scopeLabel",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        TextButton(onClick = onOpenSettings, contentPadding = TightPadding) {
            Icon(Icons.Outlined.Settings, contentDescription = "Plume settings", modifier = Modifier.size(16.dp))
        }
        TextButton(onClick = onBackToKeyboard, contentPadding = TightPadding) {
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardReturn,
                contentDescription = "Back to keyboard",
                modifier = Modifier.size(16.dp),
            )
            Text("  Keyboard", style = MaterialTheme.typography.labelSmall)
        }
    }
}

private val TightPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp)

@Composable
private fun ReadyBody(state: ImeState.Ready, onRevise: () -> Unit, onTranslate: () -> Unit) {
    val empty = state.scope == null

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when {
            state.confirmation != null -> Confirmation(state.confirmation, Modifier.weight(1f))
            empty -> Hint(
                "Type with your usual keyboard, then switch back here to fix or translate it.",
                Modifier.weight(1f),
            )
            else -> Preview(state.preview, Modifier.weight(1f))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Button(
                onClick = onRevise,
                enabled = !empty,
                modifier = Modifier.weight(1f).height(52.dp),
            ) {
                Icon(Icons.Outlined.AutoFixHigh, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("  Revise")
            }
            OutlinedButton(
                onClick = onTranslate,
                enabled = !empty,
                modifier = Modifier.weight(1f).height(52.dp),
            ) {
                Icon(Icons.Outlined.Translate, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("  Translate")
            }
        }
    }
}

@Composable
private fun Preview(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun Hint(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth().heightIn(min = 60.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Confirmation(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = 60.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            Icons.Outlined.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun WorkingBody(note: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        Text("$note…", style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun PickerBody(
    state: ImeState.PickLanguage,
    onPick: (String) -> Unit,
    onCancel: () -> Unit,
) {
    // Recents first: in a keyboard the target is nearly always one the user just used, and there is
    // no room for a search field without pushing the actions off screen. Falling back to the device
    // defaults matters — a user who unpinned everything would otherwise reach a dead end here, with
    // no way to translate and no way to open settings from inside the picker.
    val options = remember(state) { pickerOptions(state.recents, state.favorites) }

    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Translate into", style = MaterialTheme.typography.titleMedium)

        if (options.isEmpty()) {
            Hint("No languages available.", Modifier.weight(1f))
        } else {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                options.chunked(3).take(3).forEach { row ->
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(row, key = { it }) { code ->
                            SuggestionChip(
                                onClick = { onPick(code) },
                                label = {
                                    Text(
                                        Languages.resolve(code).displayName(),
                                        style = MaterialTheme.typography.labelMedium,
                                        maxLines = 1,
                                    )
                                },
                                colors = SuggestionChipDefaults.suggestionChipColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    labelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                ),
                            )
                        }
                    }
                }
            }
        }

        TextButton(onClick = onCancel) { Text("Cancel") }
    }
}

@Composable
private fun FailedBody(
    state: ImeState.Failed,
    onRevise: () -> Unit,
    onTranslate: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = state.message,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (state.settingsFix) {
                Button(onClick = onOpenSettings, modifier = Modifier.weight(1f)) { Text("Open Plume") }
            }
            when (val retry = state.retry) {
                ImeState.Retry.Revise -> OutlinedButton(
                    onClick = onRevise,
                    modifier = Modifier.weight(1f),
                ) { Text("Retry") }

                is ImeState.Retry.Translate -> OutlinedButton(
                    onClick = { onTranslate(retry.code) },
                    modifier = Modifier.weight(1f),
                ) { Text("Retry") }

                null -> Unit
            }
        }
    }
}
