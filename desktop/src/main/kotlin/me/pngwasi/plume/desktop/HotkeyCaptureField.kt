package me.pngwasi.plume.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * Records a shortcut by listening for it.
 *
 * Two things make this more than a text field. Global hotkeys have to be **suspended while
 * recording**, or pressing the combination you want fires the action you are trying to rebind. And
 * modifier keys never arrive as characters, so the combination is read from the event's modifier
 * state rather than from anything typed — which is also why typed input is swallowed outright.
 *
 * Escape cancels, Enter saves, matching MyReviser.
 */
@Composable
fun HotkeyCaptureField(
    label: String,
    help: String,
    binding: String,
    /** The other actions' bindings, so a collision is refused at the point of making it. */
    otherBindings: List<String>,
    /** Only one field records at a time; the others disable themselves. */
    recordingElsewhere: Boolean,
    onRecordingChange: (Boolean) -> Unit,
    onBinding: (String) -> Unit,
) {
    val state = remember { HotkeyCaptureState() }
    // HotkeyCaptureState is plain state, so recomposition needs a nudge when it changes.
    var revision by remember { mutableStateOf(0) }
    val focusRequester = remember { FocusRequester() }
    val currentOnRecordingChange by rememberUpdatedState(onRecordingChange)

    fun mutate(block: () -> Unit) {
        block()
        revision++
    }

    val recording = state.recording.also { revision }
    val pressed = state.pressed
    val error = state.error

    fun save() = mutate {
        state.save(otherBindings)?.let(onBinding)
    }

    // The listener is suspended for as long as this field is recording, and resumed however
    // recording ends — saved, cancelled, or the window closed mid-capture.
    DisposableEffect(recording) {
        currentOnRecordingChange(recording)
        onDispose { if (recording) currentOnRecordingChange(false) }
    }

    LaunchedEffect(recording) {
        if (recording) focusRequester.requestFocus()
    }

    val shown = when {
        recording && pressed.isEmpty -> "Press the keys…"
        recording -> pressed.format()
        binding.isNotBlank() -> binding
        else -> "Not set"
    }

    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall)

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .background(
                        MaterialTheme.colorScheme.surface,
                        RoundedCornerShape(12.dp),
                    )
                    .border(
                        BorderStroke(
                            if (recording) 2.dp else 1.dp,
                            if (error != null) {
                                MaterialTheme.colorScheme.error
                            } else if (recording) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outlineVariant
                            },
                        ),
                        RoundedCornerShape(12.dp),
                    )
                    // Order matters: the key handler has to be above the focus target in the
                    // chain, or the focused node never forwards anything to it.
                    .onPreviewKeyEvent { event ->
                        if (!recording) return@onPreviewKeyEvent false

                        // Everything is consumed while recording, including Tab and typed
                        // characters, so the combination cannot leak into the rest of the window.
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent true

                        when (event.key) {
                            Key.Escape -> mutate { state.cancel() }

                            Key.Enter, Key.NumPadEnter -> save()

                            else -> mutate {
                                state.press(
                                    ctrl = event.isCtrlPressed,
                                    alt = event.isAltPressed,
                                    shift = event.isShiftPressed,
                                    meta = event.isMetaPressed,
                                    key = if (event.key.isModifier()) {
                                        null
                                    } else {
                                        HotkeyRecorder.keyName(
                                            event.key.toString().removePrefix("Key: "),
                                        )
                                    },
                                )
                            }
                        }
                        true
                    }
                    .onFocusChanged { if (!it.isFocused && recording) mutate { state.stop() } }
                    .focusRequester(focusRequester)
                    .focusable()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    text = shown,
                    style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                    color = if (recording || binding.isNotBlank()) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }

            if (recording) {
                // Neither button takes focus: focus leaving the field ends the recording, and the
                // click would then be handled after it had already ended.
                Button(
                    onClick = { save() },
                    enabled = pressed.isValid(),
                    modifier = Modifier.focusProperties { canFocus = false },
                ) {
                    Text("Save")
                }
                OutlinedButton(
                    onClick = { mutate { state.cancel() } },
                    modifier = Modifier.focusProperties { canFocus = false },
                ) {
                    Text("Cancel")
                }
            } else {
                OutlinedButton(
                    onClick = { mutate { state.start() } },
                    enabled = !recordingElsewhere,
                ) {
                    Text("Record")
                }
            }
        }

        Text(
            text = error ?: if (recording) "Escape cancels, Enter saves." else help,
            style = MaterialTheme.typography.bodySmall,
            color = if (error != null) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** Modifiers arrive as key events too, but they belong in the modifier state, not the key list. */
private fun Key.isModifier(): Boolean = this in setOf(
    Key.CtrlLeft, Key.CtrlRight,
    Key.AltLeft, Key.AltRight,
    Key.ShiftLeft, Key.ShiftRight,
    Key.MetaLeft, Key.MetaRight,
)
