package me.pngwasi.plume.desktop

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch
import me.pngwasi.plume.data.AppSettings
import me.pngwasi.plume.data.BuiltInProviders
import me.pngwasi.plume.ui.components.RowDivider
import me.pngwasi.plume.ui.components.SettingsRow
import me.pngwasi.plume.ui.icons.PlumeIcons
import me.pngwasi.plume.ui.settings.AboutScreen
import me.pngwasi.plume.ui.settings.AddProviderDialog
import me.pngwasi.plume.ui.settings.AppearanceScreen
import me.pngwasi.plume.ui.settings.Destination
import me.pngwasi.plume.ui.settings.HomeScreen
import me.pngwasi.plume.ui.settings.ProviderEditScreen
import me.pngwasi.plume.ui.settings.ProvidersScreen
import me.pngwasi.plume.ui.settings.ReviseScreen
import me.pngwasi.plume.ui.settings.SettingsViewModel
import me.pngwasi.plume.ui.settings.TranslatePromptScreen
import me.pngwasi.plume.ui.settings.TranslateScreen

/**
 * The desktop settings window.
 *
 * Every screen except Shortcuts and Recent changes is the same composable the Android app renders;
 * what differs is the frame around them and the two destinations that only make sense with a tray
 * and a global hotkey behind them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DesktopSettingsWindow(
    controller: DesktopController,
    settings: AppSettings,
    history: List<HistoryEntry>,
    outcome: ActionOutcome,
) {
    val viewModel = remember(controller) {
        SettingsViewModel(controller.repository, controller.secrets)
    }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    val stack = remember { mutableStateListOf<Destination>(Destination.Home) }
    val current = stack.last()
    val keyed by viewModel.keyedProviders.collectAsState()
    val probe by viewModel.probe.collectAsState()
    val models by viewModel.models.collectAsState()
    var showAddProvider by remember { mutableStateOf(false) }

    fun push(destination: Destination) = stack.add(destination)
    fun pop() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }

    LaunchedEffect(current) {
        if (current !is Destination.ProviderEdit) {
            viewModel.resetModels()
            viewModel.clearProbe()
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    val barTitle = when (current) {
                        Destination.Home -> ""
                        is Destination.ProviderEdit -> settings.labelOf(current.providerId)
                        else -> current.title
                    }
                    if (barTitle.isNotEmpty()) {
                        Text(text = barTitle, style = MaterialTheme.typography.titleLarge)
                    }
                },
                navigationIcon = {
                    if (stack.size > 1) {
                        IconButton(onClick = ::pop) {
                            Icon(PlumeIcons.ArrowBack, contentDescription = "Back")
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
            transitionSpec = { fadeIn(tween(140)) togetherWith fadeOut(tween(140)) },
            label = "screen",
            modifier = Modifier.padding(padding),
        ) { destination ->
            when (destination) {
                Destination.Home -> HomeScreen(
                    settings = settings,
                    keyedProviders = keyed,
                    onOpen = ::push,
                    intro = "Select text anywhere, then press a Plume shortcut. " +
                        "Plume stays in the tray.",
                    platformRows = {
                        RowDivider()
                        SettingsRow(
                            title = "Shortcuts",
                            subtitle = shortcutSubtitle(controller),
                            icon = PlumeIcons.Keyboard,
                            showChevron = true,
                            onClick = { push(Destination.Hotkeys) },
                        )
                        RowDivider()
                        SettingsRow(
                            title = "Recent changes",
                            subtitle = if (history.isEmpty()) {
                                "Nothing yet this session"
                            } else {
                                "${history.size} kept, with the original text"
                            },
                            icon = PlumeIcons.Refresh,
                            showChevron = true,
                            onClick = { push(Destination.History) },
                        )
                    },
                )

                Destination.Hotkeys -> HotkeysScreen(
                    settings = settings.desktop,
                    defaults = hotkeyDefaultsFor(),
                    availability = controller.availability,
                    rejectedBindings = controller.rejectedBindings,
                    onChange = { updated ->
                        scope.launch {
                            controller.repository.update { it.copy(desktop = updated) }
                        }
                    },
                )

                Destination.History -> HistoryScreen(
                    history = history,
                    onCopy = { text -> controller.copyToClipboard(text) },
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

                Destination.Appearance -> AppearanceScreen(
                    current = settings.theme,
                    onSelect = viewModel::setTheme,
                )

                Destination.About -> AboutScreen()

                // Android's companion keyboard; there is no input-method list to join here.
                Destination.Keyboard -> Unit
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

private fun shortcutSubtitle(controller: DesktopController): String =
    when (val availability = controller.availability) {
        HotkeyAvailability.Ready ->
            if (controller.rejectedBindings.isEmpty()) "Active" else "Some shortcuts were refused"
        is HotkeyAvailability.NeedsPermission -> availability.summary
        is HotkeyAvailability.Unavailable -> "Unavailable"
    }
