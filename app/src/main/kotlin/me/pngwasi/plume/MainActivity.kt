package me.pngwasi.plume

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import me.pngwasi.plume.data.AppSettings
import me.pngwasi.plume.data.BuiltInProviders
import me.pngwasi.plume.data.ThemeMode
import me.pngwasi.plume.ime.KeyboardComponent
import me.pngwasi.plume.ui.settings.AboutScreen
import me.pngwasi.plume.ui.settings.AddProviderDialog
import me.pngwasi.plume.ui.settings.AppearanceScreen
import me.pngwasi.plume.ui.settings.Destination
import me.pngwasi.plume.ui.settings.HomeScreen
import me.pngwasi.plume.ui.settings.KeyboardScreen
import me.pngwasi.plume.ui.settings.ProviderEditScreen
import me.pngwasi.plume.ui.settings.ProvidersScreen
import me.pngwasi.plume.ui.settings.ReviseScreen
import me.pngwasi.plume.ui.settings.SettingsViewModel
import me.pngwasi.plume.ui.settings.TranslatePromptScreen
import me.pngwasi.plume.ui.settings.TranslateScreen
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
            val viewModel: SettingsViewModel = viewModel()
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsApp(
    viewModel: SettingsViewModel,
    settings: AppSettings,
    landing: Destination?,
) {
    val stack = remember {
        mutableStateListOf<Destination>(Destination.Home).apply { landing?.let { add(it) } }
    }
    val current = stack.last()
    val keyed by viewModel.keyedProviders.collectAsStateWithLifecycle()
    val probe by viewModel.probe.collectAsStateWithLifecycle()
    val models by viewModel.models.collectAsStateWithLifecycle()
    val keyboardStatus by viewModel.keyboardStatus.collectAsStateWithLifecycle()

    var showAddProvider by remember { mutableStateOf(false) }
    val context = LocalContext.current

    fun push(destination: Destination) = stack.add(destination)
    fun pop() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }

    BackHandler(enabled = stack.size > 1) { pop() }

    // The model catalogue belongs to whichever provider is open; drop it on the way out.
    LaunchedEffect(current) {
        if (current is Destination.Keyboard) viewModel.refreshKeyboardStatus()
        if (current !is Destination.ProviderEdit) {
            viewModel.resetModels()
            viewModel.clearProbe()
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = if (current is Destination.ProviderEdit) {
                            settings.labelOf(current.providerId)
                        } else {
                            current.title
                        },
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                navigationIcon = {
                    if (stack.size > 1) {
                        IconButton(onClick = ::pop) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        AnimatedContent(
            targetState = current,
            transitionSpec = {
                fadeIn(tween(140)) togetherWith fadeOut(tween(140))
            },
            label = "screen",
            modifier = Modifier.padding(padding),
        ) { destination ->
            when (destination) {
                Destination.Home -> HomeScreen(
                    settings = settings,
                    keyedProviders = keyed,
                    onOpen = ::push,
                )

                Destination.Providers -> ProvidersScreen(
                    settings = settings,
                    keyedProviders = keyed,
                    onEdit = { push(Destination.ProviderEdit(it)) },
                    onSetActionProvider = viewModel::setActionProvider,
                    onAddCustom = { showAddProvider = true },
                )

                is Destination.ProviderEdit -> {
                    val id = destination.providerId
                    val config = settings.providers[id]
                    if (config == null) {
                        LaunchedEffect(id) { pop() }
                    } else {
                        ProviderEditScreen(
                            providerId = id,
                            initial = config,
                            initialApiKey = remember(id) { viewModel.apiKey(id) },
                            isDefault = settings.defaultProvider == id,
                            probe = probe,
                            models = models,
                            onSave = { updated, key -> viewModel.saveProvider(id, updated, key) },
                            onSetDefault = { viewModel.setDefaultProvider(id) },
                            onTest = { viewModel.testConnection(id) },
                            onLoadModels = { updated, key -> viewModel.loadModels(updated, key) },
                            onDelete = if (BuiltInProviders.isBuiltIn(id)) {
                                null
                            } else {
                                { viewModel.deleteProvider(id); pop() }
                            },
                            onClearProbe = viewModel::clearProbe,
                        )
                    }
                }

                Destination.Revise -> ReviseScreen(
                    settings = settings.revise,
                    onChange = { transform -> viewModel.updateRevise(transform) },
                )

                Destination.Translate -> TranslateScreen(
                    settings = settings.translate,
                    onToggleFavorite = viewModel::toggleFavoriteLanguage,
                    onSetDefaultTarget = viewModel::setDefaultTarget,
                    onOpenPrompt = { push(Destination.TranslatePrompt) },
                )

                Destination.TranslatePrompt -> TranslatePromptScreen(
                    settings = settings.translate,
                    onChange = { transform -> viewModel.updateTranslate(transform) },
                )

                Destination.Keyboard -> KeyboardScreen(
                    enabled = settings.keyboardEnabled,
                    status = keyboardStatus,
                    onToggle = viewModel::setKeyboardEnabled,
                    onOpenSystemSettings = {
                        context.startActivity(KeyboardComponent.systemKeyboardSettings())
                    },
                    onShowPicker = viewModel::showKeyboardPicker,
                )

                Destination.Appearance -> AppearanceScreen(
                    current = settings.theme,
                    onSelect = viewModel::setTheme,
                )

                Destination.About -> AboutScreen()
            }
        }
    }

    if (showAddProvider) {
        AddProviderDialog(
            existingNames = settings.providers.keys,
            onDismiss = { showAddProvider = false },
            onCreate = { name, config ->
                showAddProvider = false
                viewModel.saveProvider(name, config, null)
                push(Destination.ProviderEdit(name))
            },
        )
    }
}
