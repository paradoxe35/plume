package me.pngwasi.plume.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.pngwasi.plume.ui.components.RowDivider
import me.pngwasi.plume.ui.components.SectionLabel
import me.pngwasi.plume.ui.components.SettingsCard
import me.pngwasi.plume.ui.components.SettingsRow
import me.pngwasi.plume.ui.icons.PlumeIcons

/**
 * Enabling a keyboard on iOS is two separate steps in Settings, and the second one is the one
 * people miss.
 *
 * Without Full Access a keyboard extension has no network at all, so every action fails in a way
 * that looks exactly like a misconfigured provider. Saying so up front is cheaper than the support
 * conversation afterwards.
 */
@Composable
fun IosKeyboardSetupScreen(confirmed: Boolean, onOpenSettings: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 32.dp),
    ) {
        Text(
            text = "iOS has no selection menu for other apps to join, so the keyboard is how you " +
                "reach Plume.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
        )

        // Confirmed by the keyboard itself, the only process that can see it. Never claims the
        // negative: an unconfirmed keyboard may simply not have been opened yet.
        SettingsCard {
            if (confirmed) {
                SettingsRow(
                    title = "The keyboard is working",
                    subtitle = "It has run with Full Access, so it can reach your provider.",
                    icon = PlumeIcons.CheckCircle,
                )
            } else {
                SettingsRow(
                    title = "Not confirmed yet",
                    subtitle = "Finish the two steps, then open any text field and switch to " +
                        "Plume once. This turns green when the keyboard reports back.",
                    icon = PlumeIcons.ErrorOutline,
                )
            }
        }

        SectionLabel("Two steps")
        SettingsCard {
            SettingsRow(
                title = "Add the keyboard",
                subtitle = "Settings → General → Keyboard → Keyboards → Add New Keyboard → Plume",
                icon = PlumeIcons.Keyboard,
            )
            RowDivider()
            SettingsRow(
                title = "Allow Full Access",
                subtitle = "Tap Plume in that list and turn it on. Without it Plume has no " +
                    "network and cannot read copied text.",
                icon = PlumeIcons.CheckCircle,
            )
        }

        Button(
            onClick = onOpenSettings,
            modifier = Modifier.padding(top = 20.dp),
        ) {
            Text("Open Settings")
        }
    }
}
