package me.pngwasi.plume.desktop

import com.sun.jna.Pointer
import me.pngwasi.plume.data.DesktopOs
import me.pngwasi.plume.data.HotkeyDefaults
import me.pngwasi.plume.data.isWaylandSession
import me.pngwasi.plume.native.PlumeNative
import me.pngwasi.plume.native.PlumeNativeLibrary

/** The three bindable actions. The string is what crosses the FFI boundary. */
enum class HotkeyAction(val id: String) {
    ReviseSelection("revise_selection"),
    ReviseAll("revise_all"),
    TranslateSelection("translate_selection");

    companion object {
        fun fromId(id: String): HotkeyAction? = entries.firstOrNull { it.id == id }
    }
}

/** Whether hotkeys can work here, and what the user must do if not. */
sealed interface HotkeyAvailability {
    data object Ready : HotkeyAvailability

    /** Fixable by the user; [instruction] says how. */
    data class NeedsPermission(val summary: String, val instruction: String) : HotkeyAvailability

    data class Unavailable(val reason: String) : HotkeyAvailability
}

/**
 * Registers global hotkeys with the Rust listener.
 *
 * The callback arrives on Rust's listener thread, so it does the least possible work: it maps the
 * action string and hands off. Anything slow here stalls every subsequent keystroke the listener
 * sees.
 */
class HotkeyService(
        private val library: PlumeNativeLibrary,
        private val onAction: (HotkeyAction) -> Unit,
) : AutoCloseable {

    private val manager: Pointer? = library.plume_hotkey_manager_new()

    // JNA callbacks are only reachable from native code while something on the Kotlin side still
    // references them; letting these be collected would crash the listener thread.
    private val callbacks = mutableListOf<PlumeNativeLibrary.HotkeyCallback>()

    private var started = false

    fun register(bindings: Map<HotkeyAction, String>): List<String> {
        val failures = mutableListOf<String>()
        library.plume_hotkey_clear(manager)
        callbacks.clear()

        bindings.forEach { (action, binding) ->
            if (binding.isBlank()) return@forEach
            val callback =
                    PlumeNativeLibrary.HotkeyCallback { pointer ->
                        val id = pointer?.getString(0)
                        HotkeyAction.fromId(id.orEmpty())?.let(onAction)
                    }
            callbacks += callback
            if (library.plume_hotkey_register(manager, binding, action.id, callback) != 0) {
                failures += binding
            }
        }
        return failures
    }

    fun start(): Boolean {
        if (started) return true
        started = library.plume_hotkey_start(manager) == 0
        return started
    }

    fun stop() {
        if (!started) return
        library.plume_hotkey_stop(manager)
        started = false
    }

    override fun close() {
        stop()
        library.plume_hotkey_manager_free(manager)
        callbacks.clear()
    }

    companion object {
        fun createOrNull(onAction: (HotkeyAction) -> Unit): HotkeyService? {
            val library = PlumeNative.library ?: return null
            val service = HotkeyService(library, onAction)
            if (service.manager == null) {
                service.close()
                return null
            }
            return service
        }
    }
}

/**
 * What the settings screen should say about hotkeys on this machine.
 *
 * Each platform fails differently and silently — the binding simply never fires — so the state is
 * reported before the user tries it rather than after.
 */
fun hotkeyAvailability(
        nativeState: PlumeNative.State = PlumeNative.state,
        os: DesktopOs = DesktopOs.current,
        wayland: Boolean = isWaylandSession(),
        inInputGroup: () -> Boolean = ::userIsInInputGroup,
        macPermissions: () -> MacPermissionState = MacPermissions::current,
): HotkeyAvailability {
    if (nativeState is PlumeNative.State.Unavailable) {
        return HotkeyAvailability.Unavailable(nativeState.reason)
    }
    return when (os) {
        DesktopOs.MacOs -> {
            // Both, and neither implies the other: Input Monitoring sees the shortcut and
            // Accessibility replaces the text, so one alone leaves the other silently blocking.
            val missing = macPermissions().missing
            if (missing.isEmpty()) {
                HotkeyAvailability.Ready
            } else {
                HotkeyAvailability.NeedsPermission(
                    summary = "Plume needs " + missing.joinToString(" and ") { it.label },
                    // What happens if they are not granted. The how is a button now, so repeating
                    // "open System Settings" here would just be noise beside it.
                    instruction = "Until both are allowed, the shortcuts cannot fire and nothing " +
                        "will happen when you press one.",
                )
            }
        }
        DesktopOs.Linux ->
                if (!wayland || inInputGroup()) {
                    HotkeyAvailability.Ready
                } else {
                    HotkeyAvailability.NeedsPermission(
                            "Wayland needs Plume in the input group",
                            "Run: sudo usermod -aG input $USER_NAME — then log out and back in.",
                    )
                }
        DesktopOs.Windows -> HotkeyAvailability.Ready
    }
}

private val USER_NAME: String
    get() = System.getProperty("user.name") ?: "\$USER"

/** Wayland's evdev grab needs the user in `input`; without it the listener sees nothing. */
fun userIsInInputGroup(): Boolean =
        runCatching {
                    val process = ProcessBuilder("id", "-nG").start()
                    val groups = process.inputStream.bufferedReader().readText()
                    process.waitFor()
                    groups.split(Regex("\\s+")).any { it == "input" }
                }
                .getOrDefault(false)

/**
 * Revise keeps MyReviser's bindings exactly, on all three systems: they have been in daily use for
 * long enough to be worth more than any reasoning about what might clash.
 *
 * Translate is the one Plume invented, because MyReviser has no translate action to copy. It was
 * `ctrl+alt+t`, which GNOME and KDE both use to launch a terminal. `g` is three keys like the
 * others, is bound by neither desktop nor by VS Code or a browser, and is under the left hand.
 */
fun hotkeyDefaultsFor(os: DesktopOs = DesktopOs.current): HotkeyDefaults =
        when (os) {
            DesktopOs.MacOs ->
                    HotkeyDefaults(
                            reviseSelection = "ctrl+cmd",
                            reviseAll = "ctrl+option+space",
                            translateSelection = "ctrl+option+g",
                    )
            DesktopOs.Windows ->
                    HotkeyDefaults(
                            reviseSelection = "ctrl+win",
                            reviseAll = "ctrl+alt+space",
                            translateSelection = "ctrl+alt+g",
                    )
            DesktopOs.Linux ->
                    HotkeyDefaults(
                            reviseSelection = "ctrl+super",
                            reviseAll = "ctrl+alt+space",
                            translateSelection = "ctrl+alt+g",
                    )
        }
