package me.pngwasi.plume.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.pngwasi.plume.ui.components.rememberTrackedScrollState
import me.pngwasi.plume.data.Action
import me.pngwasi.plume.data.isReady
import me.pngwasi.plume.data.AppSettings
import me.pngwasi.plume.data.Languages
import me.pngwasi.plume.data.ThemeMode
import me.pngwasi.plume.ui.components.RowDivider
import me.pngwasi.plume.ui.components.SectionLabel
import me.pngwasi.plume.ui.components.SettingsCard
import me.pngwasi.plume.ui.components.SettingsRow
import me.pngwasi.plume.ui.components.StatusPill
import me.pngwasi.plume.ui.icons.PlumeIcons
import me.pngwasi.plume.ui.theme.LocalWarningColors

@Composable
fun HomeScreen(
    settings: AppSettings,
    keyedProviders: Set<String>,
    onOpen: (Destination) -> Unit,
    /** How the user reaches Plume here, which is the one thing that differs per platform. */
    intro: String = "Select text in any app, then pick Revise or Translate from the selection menu.",
    /** Something the system is withholding, which no amount of configuration will fix. */
    blocker: PlatformBlocker? = null,
    /** Rows only one platform has: the companion keyboard on Android, shortcuts on the desktop. */
    platformRows: @Composable () -> Unit = {},
    /** Rows about what Plume has done rather than how it behaves: the log, and what it changed. */
    platformHelpRows: @Composable () -> Unit = {},
    /**
     * Anything that belongs after the settings, separated from them. Quitting is not a setting, and
     * sitting it between two navigation rows made it easy to hit by accident.
     */
    platformFooter: @Composable () -> Unit = {},
) {
    val copy = remember { platformCopy() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberTrackedScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 32.dp),
    ) {
        Header(intro)
        ReadinessCard(
            settings = settings,
            keyedProviders = keyedProviders,
            blocker = blocker,
            // Only where tapping it leads somewhere useful. A withheld permission is granted from
            // the rows below, and sending the user to the provider list instead was a dead end.
            onFix = if (blocker == null) ({ onOpen(Destination.Providers) }) else null,
        )

        if (blocker != null && blocker.fixes.isNotEmpty()) {
            SectionLabel("Permissions required")
            val warning = LocalWarningColors.current
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = warning.container,
                border = BorderStroke(1.dp, warning.accent),
            ) {
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    blocker.fixes.forEachIndexed { index, fix ->
                        if (index > 0) {
                            HorizontalDivider(color = warning.accent.copy(alpha = 0.25f))
                        }
                        PermissionRow(fix)
                    }
                }
            }
        }

        SectionLabel("Actions")
        SettingsCard {
            SettingsRow(
                title = "Revise",
                subtitle = "Spelling, grammar and accents. Prompt and limits.",
                icon = PlumeIcons.AutoFixHigh,
                showChevron = true,
                onClick = { onOpen(Destination.Revise) },
            )
            RowDivider()
            SettingsRow(
                title = "Translate",
                subtitle = translateSubtitle(settings),
                icon = PlumeIcons.Translate,
                showChevron = true,
                onClick = { onOpen(Destination.Translate) },
            )
        }

        SectionLabel("Configuration")
        SettingsCard {
            SettingsRow(
                title = "AI providers",
                subtitle = providerSubtitle(settings),
                icon = PlumeIcons.Hub,
                showChevron = true,
                onClick = { onOpen(Destination.Providers) },
            )
            platformRows()
            RowDivider()
            SettingsRow(
                title = "Appearance",
                subtitle = when (settings.theme) {
                    ThemeMode.System -> "Follow system"
                    ThemeMode.Light -> "Light"
                    ThemeMode.Dark -> "Dark"
                },
                icon = PlumeIcons.DarkMode,
                showChevron = true,
                onClick = { onOpen(Destination.Appearance) },
            )
        }

        SectionLabel("Help")
        SettingsCard {
            SettingsRow(
                title = "How Plume works",
                subtitle = copy.aboutSubtitle,
                icon = PlumeIcons.Info,
                showChevron = true,
                onClick = { onOpen(Destination.About) },
            )
            platformHelpRows()
        }

        platformFooter()
    }
}

@Composable
private fun Header(intro: String) {
    Column(
        modifier = Modifier.padding(top = 4.dp, bottom = 22.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Plume", style = MaterialTheme.typography.displaySmall)
        Text(
            text = intro,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Named, so the user knows which switch is still off rather than being sent to hunt for it. */
@Composable
private fun PermissionRow(fix: BlockerFix) {
    val warning = LocalWarningColors.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = PlumeIcons.ErrorOutline,
            contentDescription = null,
            tint = warning.accent,
            modifier = Modifier.size(20.dp),
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = fix.label,
                style = MaterialTheme.typography.titleSmall,
                color = warning.onContainer,
            )
            Text(
                text = fix.why,
                style = MaterialTheme.typography.bodySmall,
                color = warning.onContainer,
            )
        }
        Button(
            onClick = fix.onSelect,
            colors = ButtonDefaults.buttonColors(
                containerColor = warning.accent,
                contentColor = warning.container,
            ),
        ) {
            Text(fix.action)
        }
    }
}

/**
 * The one thing a user must get right is a working provider, so its state is the first thing on
 * screen — and it is the only card that changes colour, which is what makes it read as a status.
 *
 * Readiness is per action, because the two can run on different providers and one of them being
 * misconfigured must not be hidden behind the other one working.
 */
@Composable
private fun ReadinessCard(
    settings: AppSettings,
    keyedProviders: Set<String>,
    blocker: PlatformBlocker?,
    /** Null when the card is only reporting: a blocker is fixed by the rows beneath it. */
    onFix: (() -> Unit)?,
) {
    val reviseReady = settings.isReady(Action.Revise, keyedProviders)
    val translateReady = settings.isReady(Action.Translate, keyedProviders)
    val allReady = reviseReady && translateReady && blocker == null

    // A withheld permission is a warning, not a to-do: nothing works until it is granted, and the
    // banner should not look calmer than the rows beneath it.
    val container = when {
        blocker != null -> LocalWarningColors.current.container
        allReady -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.secondaryContainer
    }
    val onContainer = when {
        blocker != null -> LocalWarningColors.current.onContainer
        allReady -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSecondaryContainer
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = container,
        border = BorderStroke(
            width = 1.dp,
            color = if (blocker != null) {
                LocalWarningColors.current.accent
            } else {
                MaterialTheme.colorScheme.outlineVariant
            },
        ),
        onClick = onFix ?: {},
        enabled = onFix != null,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = when {
                    blocker != null -> PlumeIcons.ErrorOutline
                    allReady -> PlumeIcons.AutoFixHigh
                    else -> PlumeIcons.Info
                },
                contentDescription = null,
                tint = onContainer,
                modifier = Modifier.size(22.dp),
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = when {
                        allReady -> "Ready to use"
                        // A blocked platform comes first: a key is no use if the system will not
                        // let the shortcut through.
                        blocker != null -> blocker.summary
                        !reviseReady && !translateReady -> "Setup needed"
                        !reviseReady -> "Revise needs setup"
                        else -> "Translate needs setup"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = onContainer,
                )
                Text(
                    text = blocker?.detail ?: readinessDetail(settings, reviseReady, translateReady),
                    style = MaterialTheme.typography.bodySmall,
                    color = onContainer,
                )
            }
        }
    }
}

private fun readinessDetail(
    settings: AppSettings,
    reviseReady: Boolean,
    translateReady: Boolean,
): String {
    val reviseId = settings.providerIdFor(Action.Revise)
    val translateId = settings.providerIdFor(Action.Translate)

    if (reviseReady && translateReady) {
        return if (reviseId == translateId) {
            val config = settings.providers[reviseId]
            "${settings.labelOf(reviseId)} · ${config?.model.orEmpty()}"
        } else {
            "Revise: ${settings.labelOf(reviseId)} · Translate: ${settings.labelOf(translateId)}"
        }
    }

    val broken = if (!reviseReady) reviseId else translateId
    val config = settings.providers[broken]
        ?: return "Pick an AI provider to get started."
    return when {
        config.baseUrl.isBlank() -> "${settings.labelOf(broken)} has no base URL."
        config.model.isBlank() -> "${settings.labelOf(broken)} has no model selected."
        else -> "Add an API key for ${settings.labelOf(broken)} to get started."
    }
}

private fun providerSubtitle(settings: AppSettings): String {
    val count = settings.providers.size
    return "Default: ${settings.labelOf(settings.defaultProvider)} · $count configured"
}

private fun translateSubtitle(settings: AppSettings): String {
    val preset = settings.translate.defaultTarget
    if (!preset.isNullOrBlank()) {
        return "Always into ${Languages.resolve(preset).displayName()}"
    }
    val favorites = settings.translate.favorites
    if (favorites.isEmpty()) return "Ask every time"
    return favorites.take(3).joinToString(", ") { Languages.resolve(it).displayName() }
}

@Composable
fun ReadyPill(ready: Boolean) {
    StatusPill(
        text = if (ready) "Ready" else "No key",
        container = if (ready) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        onContainer = if (ready) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
}
