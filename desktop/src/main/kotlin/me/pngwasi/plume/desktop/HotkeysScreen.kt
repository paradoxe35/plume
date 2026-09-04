package me.pngwasi.plume.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.pngwasi.plume.ui.components.rememberTrackedScrollState
import me.pngwasi.plume.data.DesktopSettings
import me.pngwasi.plume.ui.components.SectionLabel
import me.pngwasi.plume.ui.components.SettingsCard
import me.pngwasi.plume.ui.components.SettingsRow
import me.pngwasi.plume.ui.icons.PlumeIcons

/**
 * Shortcuts, and the reason they are not working when they are not working.
 *
 * The permission state is stated before the user tries a shortcut, because every platform fails the
 * same silent way — the binding simply never fires, with nothing to distinguish "not permitted"
 * from "wrong keys".
 */
@Composable
fun HotkeysScreen(
    settings: DesktopSettings,
    defaults: me.pngwasi.plume.data.HotkeyDefaults,
    availability: HotkeyAvailability,
    /** Set when the system refused the key listener, which no binding can work around. */
    listenerError: String? = null,
    rejectedBindings: List<String>,
    onChange: (DesktopSettings) -> Unit,
    /** Suspends the global listener while a shortcut is being recorded. */
    onRecordingChange: (Boolean) -> Unit = {},
) {
    // Which field is recording, if any. Pressing the combination you are rebinding would otherwise
    // fire the action you are rebinding.
    var recording by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(recording) { onRecordingChange(recording != null) }

    // Leaving the screen part-way through recording would otherwise leave the listener suspended,
    // with every shortcut dead and nothing on screen saying so.
    DisposableEffect(Unit) { onDispose { onRecordingChange(false) } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberTrackedScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 32.dp),
    ) {
        Text(
            text = "Plume listens for these anywhere, then works on whatever is selected in the " +
                "app you are using.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
        )

        PermissionCard(availability, listenerError)

        if (rejectedBindings.isNotEmpty()) {
            SectionLabel("Not registered")
            SettingsCard {
                SettingsRow(
                    title = rejectedBindings.joinToString(", "),
                    subtitle = "The system refused these, usually because another app has them.",
                    icon = PlumeIcons.ErrorOutline,
                )
            }
        }

        SectionLabel("Shortcuts")

        val bindings = listOf(
            settings.reviseSelectionOrDefault(defaults),
            settings.reviseAllOrDefault(defaults),
            settings.translateSelectionOrDefault(defaults),
        )

        HotkeyCaptureField(
            label = "Revise selection",
            help = "Fix spelling and grammar in whatever is selected.",
            binding = bindings[0],
            otherBindings = listOf(bindings[1], bindings[2]),
            recordingElsewhere = recording != null && recording != 0,
            onRecordingChange = { active -> recording = if (active) 0 else null },
            onBinding = { onChange(settings.copy(reviseSelection = it)) },
        )
        HotkeyCaptureField(
            label = "Revise everything",
            help = "Select the whole field first, then revise it.",
            binding = bindings[1],
            otherBindings = listOf(bindings[0], bindings[2]),
            recordingElsewhere = recording != null && recording != 1,
            onRecordingChange = { active -> recording = if (active) 1 else null },
            onBinding = { onChange(settings.copy(reviseAll = it)) },
        )
        HotkeyCaptureField(
            label = "Translate selection",
            help = "Translate into your default target, or the first pinned language.",
            binding = bindings[2],
            otherBindings = listOf(bindings[0], bindings[1]),
            recordingElsewhere = recording != null && recording != 2,
            onRecordingChange = { active -> recording = if (active) 2 else null },
            onBinding = { onChange(settings.copy(translateSelection = it)) },
        )

    }
}

@Composable
private fun PermissionCard(availability: HotkeyAvailability, listenerError: String?) {
    // A refused listener beats a granted permission: the checks can pass and the tap still fail,
    // and saying "active" then is the reason a dead shortcut reads as a wrong binding.
    if (listenerError != null) {
        SettingsCard {
            SettingsRow(
                title = "Plume is not listening",
                subtitle = listenerError,
                icon = PlumeIcons.ErrorOutline,
            )
        }
        return
    }

    when (availability) {
        HotkeyAvailability.Ready -> SettingsCard {
            SettingsRow(
                title = "Shortcuts are active",
                subtitle = "Plume can see key presses from other applications.",
                icon = PlumeIcons.CheckCircle,
            )
        }

        // Points at the home screen rather than repeating it. Permissions gate the whole app, so
        // they are granted in one place; saying nothing here would just look broken.
        is HotkeyAvailability.NeedsPermission -> SettingsCard {
            SettingsRow(
                title = "Waiting on permissions",
                subtitle = "Grant them on the home screen, then these start working.",
                icon = PlumeIcons.ErrorOutline,
            )
        }

        is HotkeyAvailability.Unavailable -> SettingsCard {
            SettingsRow(
                title = "Shortcuts are unavailable",
                subtitle = availability.reason,
                icon = PlumeIcons.ErrorOutline,
            )
        }
    }
}
