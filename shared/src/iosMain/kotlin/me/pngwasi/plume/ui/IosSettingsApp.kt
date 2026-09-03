package me.pngwasi.plume.ui

import androidx.compose.runtime.Composable
import me.pngwasi.plume.data.AppSettings
import me.pngwasi.plume.ui.components.RowDivider
import me.pngwasi.plume.ui.components.SettingsRow
import me.pngwasi.plume.ui.icons.PlumeIcons
import me.pngwasi.plume.ui.settings.Destination
import me.pngwasi.plume.ui.settings.SettingsNavHost
import me.pngwasi.plume.ui.settings.SettingsViewModel
import me.pngwasi.plume.ui.settings.rememberSettingsStack
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString

/**
 * The container app's settings, which are the shared screens plus one iOS-only row.
 *
 * iOS has no selection-menu equivalent to `ACTION_PROCESS_TEXT`, so the keyboard is not an optional
 * extra here as it is on Android — it is the only way in, and setting it up is the first thing the
 * user needs.
 */
@Composable
fun IosSettingsApp(
    viewModel: SettingsViewModel,
    settings: AppSettings,
) {
    val stack = rememberSettingsStack()

    SettingsNavHost(
        viewModel = viewModel,
        settings = settings,
        stack = stack,
        intro = "Switch to the Plume keyboard in any app, then revise or translate what you typed.",
        platformRows = { push ->
            RowDivider()
            SettingsRow(
                title = "Plume keyboard",
                subtitle = "Enable it, and allow Full Access",
                icon = PlumeIcons.Keyboard,
                showChevron = true,
                onClick = { push(Destination.Keyboard) },
            )
        },
        platformScreen = { destination, _ ->
            if (destination is Destination.Keyboard) {
                IosKeyboardSetupScreen(onOpenSettings = ::openSystemSettings)
            }
        },
    )
}

private fun openSystemSettings() {
    val url = NSURL.URLWithString(UIApplicationOpenSettingsURLString) ?: return
    UIApplication.sharedApplication.openURL(url)
}
