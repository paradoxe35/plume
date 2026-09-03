package me.pngwasi.plume.ui.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import me.pngwasi.plume.data.AppSettings
import me.pngwasi.plume.data.BuiltInProviders
import me.pngwasi.plume.ui.icons.PlumeIcons

/** The navigation stack, hoisted so a platform can drive it — Android's back gesture, say. */
@Composable
fun rememberSettingsStack(landing: Destination? = null): SnapshotStateList<Destination> =
    remember {
        mutableStateListOf<Destination>(Destination.Home).apply { landing?.let { add(it) } }
    }

/**
 * The settings app: the whole screen stack, shared by Android, the desktop and iOS.
 *
 * Only two things vary, and both are passed in: the rows on the home screen that only one platform
 * has, and the screens behind them. Everything else — providers, prompts, translation targets,
 * appearance — is identical everywhere, and keeping one copy is what makes that stay true.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsNavHost(
    viewModel: SettingsViewModel,
    settings: AppSettings,
    stack: SnapshotStateList<Destination>,
    intro: String,
    /** Rows added to the home screen's configuration card. */
    platformRows: @Composable (push: (Destination) -> Unit) -> Unit = {},
    /** Screens for destinations this host does not know about. */
    platformScreen: @Composable (destination: Destination, push: (Destination) -> Unit) -> Unit =
        { _, _ -> },
) {
    val current = stack.last()
    val keyed by viewModel.keyedProviders.collectAsState()
    val probe by viewModel.probe.collectAsState()
    val models by viewModel.models.collectAsState()
    var showAddProvider by remember { mutableStateOf(false) }

    val push: (Destination) -> Unit = { stack.add(it) }
    fun pop() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }

    // The model catalogue and the probe belong to whichever provider is open; drop them on the way
    // out so a stale list cannot appear against a different provider.
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
                    // Home carries its own large title, so repeating it here would say "Plume"
                    // twice on the first screen anyone sees.
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
                colors = TopAppBarDefaults.topAppBarColors(
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
            // Applied once here so no screen has to remember it: a screen that scrolls then lets
            // the soft keyboard push its content up rather than cover it. A no-op on the desktop.
            modifier = Modifier.padding(padding).imePadding(),
        ) { destination ->
            when (destination) {
                Destination.Home -> HomeScreen(
                    settings = settings,
                    keyedProviders = keyed,
                    onOpen = push,
                    intro = intro,
                    platformRows = { platformRows(push) },
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
                        // Deleted while open; there is nothing to show.
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

                else -> platformScreen(destination, push)
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
