package me.pngwasi.plume.desktop

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
import kotlin.system.exitProcess

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
    private val nativeInput = lazy { NativeSystemInput.createOrNull() }

    private val systemInput: NativeSystemInput? get() = nativeInput.value

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

    private val _permissions = MutableStateFlow(MacPermissions.current())

    /**
     * macOS privileges, polled.
     *
     * The system sends no notification when a switch is flipped, so the only way to notice is to
     * ask. Without it the warning sits there looking stale while the user is in System Settings
     * having just granted it.
     */
    val permissions: StateFlow<MacPermissionState> = _permissions.asStateFlow()

    /**
     * Whether a privilege was missing when Plume started.
     *
     * The listener is wired once at launch, so granting a permission afterwards does not reach it:
     * the shortcuts stay dead until the process restarts. Remembering this is what lets the UI ask
     * for a restart rather than leaving the user to work that out.
     */
    val permissionsMissingAtLaunch: Boolean = !_permissions.value.allGranted

    fun watchPermissions() {
        if (!MacPermissions.isSupported) return
        scope.launch {
            while (isActive) {
                delay(PERMISSION_POLL)
                val latest = MacPermissions.current()
                if (latest != _permissions.value) {
                    _permissions.value = latest
                    PlumeLog.info("macOS permissions changed: missing ${latest.missing}")
                }
                // Once everything is granted there is nothing left to watch: what remains is the
                // restart, and that is the user's to take.
                if (latest.allGranted) return@launch
            }
        }
    }

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
        PlumeLog.info("Shortcut support: ${availability::class.simpleName}")
        rejectedBindings = service.register(
            mapOf(
                HotkeyAction.ReviseSelection to settings.desktop.reviseSelectionOrDefault(defaults),
                HotkeyAction.ReviseAll to settings.desktop.reviseAllOrDefault(defaults),
                HotkeyAction.TranslateSelection to
                    settings.desktop.translateSelectionOrDefault(defaults),
            ),
        )
        if (rejectedBindings.isNotEmpty()) {
            PlumeLog.error("The system refused these shortcuts: ${rejectedBindings.joinToString()}")
        }
        if (!service.start()) PlumeLog.error("The shortcut listener did not start")
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

    /**
     * Stops this copy so a fresh one can start with the new privileges.
     *
     * Ends the process rather than the window: the replacement waits for this pid to disappear
     * before it launches, and closing the application loop would leave the JVM up — which is how a
     * restart used to end with two Plumes running.
     */
    fun restart(): Boolean {
        if (!AppRelaunch.relaunch()) {
            PlumeLog.error("No installed launcher to restart; quit and start Plume again")
            return false
        }
        shutdown()
        exitProcess(0)
    }

    /** Called from the tray, the window and a restart, so it has to survive being called twice. */
    @Synchronized
    fun shutdown() {
        hotkeys?.close()
        hotkeys = null
        // Only when something built it: reading the lazy here would open a clipboard connection for
        // the sole purpose of closing it.
        if (nativeInput.isInitialized()) nativeInput.value?.close()
    }
}

/** Slow enough to be invisible, quick enough that the card updates while the user watches. */
private const val PERMISSION_POLL = 1_500L
