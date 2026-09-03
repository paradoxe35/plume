package me.pngwasi.plume.ui

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.window.ComposeUIViewController
import me.pngwasi.plume.data.PlumeStores
import me.pngwasi.plume.data.ThemeMode
import me.pngwasi.plume.ui.settings.SettingsViewModel
import me.pngwasi.plume.ui.theme.PlumeTheme
import platform.UIKit.UIViewController

/**
 * The container app's settings UI, rendered by Compose Multiplatform.
 *
 * Only the container app uses this. The keyboard extension draws its own SwiftUI panel: it has
 * roughly 60MB before iOS kills it, and Compose's Kotlin/Native runtime alone costs a quarter of
 * that.
 */
fun mainViewController(): UIViewController = ComposeUIViewController {
    val viewModel = remember {
        SettingsViewModel(PlumeStores.settings, PlumeStores.secrets)
    }
    val settings by viewModel.settings.collectAsState()
    val loaded = settings

    PlumeTheme(mode = loaded?.theme ?: ThemeMode.System) {
        if (loaded != null) {
            IosSettingsApp(viewModel = viewModel, settings = loaded)
        }
    }
}
