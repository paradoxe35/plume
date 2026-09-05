package me.pngwasi.plume

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import me.pngwasi.plume.data.AppSettings
import me.pngwasi.plume.data.ThemeMode
import me.pngwasi.plume.ime.KeyboardComponent
import me.pngwasi.plume.ui.components.RowDivider
import me.pngwasi.plume.ui.components.SettingsRow
import me.pngwasi.plume.ui.icons.PlumeIcons
import me.pngwasi.plume.ui.settings.AndroidSettingsViewModel
import me.pngwasi.plume.ui.settings.Destination
import me.pngwasi.plume.ui.settings.KeyboardScreen
import me.pngwasi.plume.ui.settings.SettingsNavHost
import me.pngwasi.plume.ui.settings.rememberSettingsStack
import me.pngwasi.plume.ui.theme.PlumeTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val landing = when (intent?.getStringExtra(EXTRA_DESTINATION)) {
            DESTINATION_TRANSLATE -> Destination.Translate
            DESTINATION_PROVIDERS -> Destination.Providers
            else -> null
        }

        setContent {
            // Built here rather than by the default factory, which can only call a no-argument
            // constructor: the shared SettingsViewModel is not an AndroidViewModel, so nothing
            // hands it the Application it needs.
            val viewModel: AndroidSettingsViewModel = viewModel {
                AndroidSettingsViewModel(application)
            }
            val settings by viewModel.settings.collectAsStateWithLifecycle()

            PlumeTheme(mode = settings?.theme ?: ThemeMode.System) {
                val dark = when (settings?.theme ?: ThemeMode.System) {
                    ThemeMode.System -> isSystemInDarkTheme()
                    ThemeMode.Light -> false
                    ThemeMode.Dark -> true
                }
                SideEffect {
                    WindowCompat.getInsetsController(window, window.decorView)
                        .isAppearanceLightStatusBars = !dark
                }
                val loaded = settings
                if (loaded == null) {
                    Box(modifier = Modifier.fillMaxSize())
                } else {
                    SettingsApp(viewModel = viewModel, settings = loaded, landing = landing)
                }
            }
        }
    }

    companion object {
        const val EXTRA_DESTINATION = "me.pngwasi.plume.DESTINATION"
        const val DESTINATION_TRANSLATE = "translate"
        const val DESTINATION_PROVIDERS = "providers"
    }
}

@Composable
private fun SettingsApp(
    viewModel: AndroidSettingsViewModel,
    settings: AppSettings,
    landing: Destination?,
) {
    val stack = rememberSettingsStack(landing)
    val keyboardStatus by viewModel.keyboardStatus.collectAsStateWithLifecycle()
    val context = LocalContext.current

    BackHandler(enabled = stack.size > 1) {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }

    // Enabling the keyboard happens in Android's own settings, so the answer changes while Plume is
    // in the background. Navigation alone would leave the checklist stale on the way back.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshKeyboardStatus()
    }

    LaunchedEffect(stack.last()) {
        if (stack.last() is Destination.Keyboard) viewModel.refreshKeyboardStatus()
    }

    SettingsNavHost(
        viewModel = viewModel,
        settings = settings,
        stack = stack,
        intro = "Select text in any app, then pick Revise or Translate from the selection menu.",
        platformRows = { push ->
            RowDivider()
            SettingsRow(
                title = "Plume keyboard",
                subtitle = if (settings.keyboardEnabled) "On" else "Off · optional second way in",
                icon = PlumeIcons.Keyboard,
                showChevron = true,
                onClick = { push(Destination.Keyboard) },
            )
        },
        platformScreen = { destination, _ ->
            if (destination is Destination.Keyboard) {
                KeyboardScreen(
                    enabled = settings.keyboardEnabled,
                    status = keyboardStatus,
                    onToggle = viewModel::setKeyboardEnabled,
                    onOpenSystemSettings = {
                        context.startActivity(KeyboardComponent.systemKeyboardSettings())
                    },
                    onShowPicker = viewModel::showKeyboardPicker,
                )
            }
        },
    )
}
