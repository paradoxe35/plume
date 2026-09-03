package me.pngwasi.plume.desktop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.pngwasi.plume.data.AppSettings
import me.pngwasi.plume.data.PlumeStores
import me.pngwasi.plume.data.SecretStore
import me.pngwasi.plume.data.SettingsRepository

/**
 * Owns the long-lived desktop pieces: the hotkey listener, the clipboard bridge, and the settings
 * the tray reads. Everything here outlives the settings window, which is why none of it lives in
 * a composable.
 */
class DesktopController(
    private val scope: CoroutineScope,
    val repository: SettingsRepository = PlumeStores.settings,
    val secrets: SecretStore = PlumeStores.secrets,
) {

    /** Built lazily and reused: constructing a clipboard connection per action is slow on X11. */
    private val systemInput: NativeSystemInput? by lazy { NativeSystemInput.createOrNull() }

    private val capture: TextCapture? by lazy { systemInput?.let { TextCapture(it) } }

    val actions = DesktopActions(
        scope = scope,
        repository = repository,
        secrets = secrets,
        captureFactory = { capture },
    )

    val settings: StateFlow<AppSettings?> =
        repository.settings.stateIn(scope, SharingStarted.Eagerly, null)

    private val _openRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Emitted when something outside the window asks for it — a hotkey with nothing to act on. */
    val openRequests: SharedFlow<Unit> = _openRequests

    private var hotkeys: HotkeyService? = null

    /** Bindings the OS refused, so the settings screen can say which ones are dead. */
    var rejectedBindings: List<String> = emptyList()
        private set

    val availability: HotkeyAvailability get() = hotkeyAvailability()

    fun applyHotkeys(settings: AppSettings) {
        val service = hotkeys ?: HotkeyService.createOrNull { action ->
            // Runs on the Rust listener thread: hand off immediately and do nothing slow here.
            scope.launch {
                when (action) {
                    HotkeyAction.ReviseSelection -> actions.reviseSelection()
                    HotkeyAction.ReviseAll -> actions.reviseEverything()
                    HotkeyAction.TranslateSelection -> {
                        val target = settings.translate.defaultTarget
                            ?: settings.translate.favorites.firstOrNull()
                        if (target == null) _openRequests.tryEmit(Unit)
                        else actions.translateSelection(target)
                    }
                }
            }
        }?.also { hotkeys = it } ?: return

        val defaults = hotkeyDefaultsFor()
        rejectedBindings = service.register(
            mapOf(
                HotkeyAction.ReviseSelection to settings.desktop.reviseSelectionOrDefault(defaults),
                HotkeyAction.ReviseAll to settings.desktop.reviseAllOrDefault(defaults),
                HotkeyAction.TranslateSelection to
                    settings.desktop.translateSelectionOrDefault(defaults),
            ),
        )
        service.start()
    }

    /**
     * False when running from a build rather than an install: there is no launcher to register,
     * and offering the toggle anyway would promise something that cannot happen.
     */
    val launchAtLoginAvailable: Boolean by lazy {
        System.getProperty("jpackage.app-path") != null
    }

    /**
     * Stops the listener while a shortcut is being recorded, and starts it again afterwards.
     *
     * Without this, pressing the combination you are trying to rebind fires the action bound to it
     * — and the recording field never sees the keys, because the listener grabbed them.
     */
    fun setListening(listening: Boolean) {
        val service = hotkeys ?: return
        if (listening) service.start() else service.stop()
    }

    /** Used by the history screen so the user can recover an original Plume replaced. */
    fun copyToClipboard(text: String) {
        systemInput?.setClipboardText(text)
    }

    fun shutdown() {
        hotkeys?.close()
        hotkeys = null
        systemInput?.close()
    }
}
