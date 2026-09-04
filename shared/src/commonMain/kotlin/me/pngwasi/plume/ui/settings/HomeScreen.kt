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

@Composable
fun HomeScreen(
    settings: AppSettings,
    keyedProviders: Set<String>,
    onOpen: (Destination) -> Unit,
    /** How the user reaches Plume here, which is the one thing that differs per platform. */
    intro: String = "Select text in any app, then pick Revise or Translate from the selection menu.",
    /** Rows only one platform has: the companion keyboard on Android, shortcuts on the desktop. */
    platformRows: @Composable () -> Unit = {},
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
            onFix = { onOpen(Destination.Providers) },
        )

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
            RowDivider()
            SettingsRow(
                title = "How Plume works",
                subtitle = copy.aboutSubtitle,
                icon = PlumeIcons.Info,
                showChevron = true,
                onClick = { onOpen(Destination.About) },
            )
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
    onFix: () -> Unit,
) {
    val reviseReady = settings.isReady(Action.Revise, keyedProviders)
    val translateReady = settings.isReady(Action.Translate, keyedProviders)
    val allReady = reviseReady && translateReady

    val container = if (allReady) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }
    val onContainer = if (allReady) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = container,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        onClick = onFix,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (allReady) PlumeIcons.AutoFixHigh else PlumeIcons.Info,
                contentDescription = null,
                tint = onContainer,
                modifier = Modifier.size(22.dp),
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = when {
                        allReady -> "Ready to use"
                        !reviseReady && !translateReady -> "Setup needed"
                        !reviseReady -> "Revise needs setup"
                        else -> "Translate needs setup"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = onContainer,
                )
                Text(
                    text = readinessDetail(settings, reviseReady, translateReady),
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
