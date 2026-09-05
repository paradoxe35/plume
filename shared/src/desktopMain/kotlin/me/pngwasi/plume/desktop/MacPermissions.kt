package me.pngwasi.plume.desktop

import com.sun.jna.Library
import com.sun.jna.Native
import me.pngwasi.plume.data.DesktopOs

/** The two macOS privileges Plume's shortcuts depend on. They are granted separately. */
enum class MacPermission(val label: String, val why: String) {
    /** Sending the copy and paste keystrokes, and reaching another application's text. */
    Accessibility(
        label = "Accessibility",
        why = "Lets Plume replace the text you selected.",
    ),

    /** Seeing the shortcut itself. Granting Accessibility alone leaves the listener deaf. */
    InputMonitoring(
        label = "Input Monitoring",
        why = "Lets Plume notice the shortcut you press.",
    ),
}

data class MacPermissionState(
    val accessibility: Boolean,
    val inputMonitoring: Boolean,
) {
    val allGranted: Boolean get() = accessibility && inputMonitoring

    val missing: List<MacPermission>
        get() = buildList {
            if (!accessibility) add(MacPermission.Accessibility)
            if (!inputMonitoring) add(MacPermission.InputMonitoring)
        }
}

/**
 * What macOS will and will not let Plume do.
 *
 * Both are needed and neither implies the other: Accessibility alone leaves the listener deaf, so
 * the shortcuts never fire and the app looks broken while reporting itself healthy. Plume checked
 * only Accessibility before, and assumed even that.
 *
 * Nothing here can be verified from a Linux machine, so each call is written to fail towards
 * "granted": on a system where the frameworks cannot be reached, warning about a permission that
 * was in fact given is worse than staying quiet.
 */
object MacPermissions {

    val isSupported: Boolean get() = DesktopOs.current == DesktopOs.MacOs

    fun current(): MacPermissionState = MacPermissionState(
        accessibility = isAccessibilityTrusted(),
        inputMonitoring = isInputMonitoringGranted(),
    )

    /**
     * Opens the pane holding the switch. No system dialog: the card on screen already says what is
     * needed, and macOS's own prompt is a second window saying it again.
     */
    fun openSettings(permission: MacPermission): Boolean = runCatching {
        val pane = when (permission) {
            MacPermission.Accessibility -> "Privacy_Accessibility"
            MacPermission.InputMonitoring -> "Privacy_ListenEvent"
        }
        ProcessBuilder("open", "x-apple.systempreferences:com.apple.preference.security?$pane")
            .start()
            .waitFor() == 0
    }.getOrDefault(false)

    private interface ApplicationServices : Library {
        fun AXIsProcessTrusted(): Boolean

        /** The question an event tap actually asks. macOS 10.15 and later. */
        fun CGPreflightListenEventAccess(): Boolean
    }

    private interface IOKit : Library {
        /** 0 granted, 1 denied, 2 not yet asked. */
        fun IOHIDCheckAccess(request: Int): Int
    }

    private const val REQUEST_LISTEN_EVENT = 1
    private const val ACCESS_GRANTED = 0

    private val applicationServices: ApplicationServices? by lazy { load("ApplicationServices") }

    private val ioKit: IOKit? by lazy { load("IOKit") }

    private inline fun <reified T : Library> load(framework: String): T? {
        if (!isSupported) return null
        return runCatching { Native.load(framework, T::class.java) }
            .onFailure { PlumeLog.error("macOS $framework could not be reached", it) }
            .getOrNull()
    }

    private fun isAccessibilityTrusted(): Boolean {
        val services = applicationServices ?: return assumeGranted("Accessibility")
        return runCatching { services.AXIsProcessTrusted() }
            .getOrElse { assumeGranted("Accessibility") }
    }

    /** Fails towards granted, since a false warning is worse — but never silently. */
    private fun assumeGranted(permission: String): Boolean {
        PlumeLog.error("Could not read the macOS $permission state; assuming it is granted")
        return true
    }

    /**
     * `CGPreflightListenEventAccess` asks what the listener asks: may this process receive
     * `KeyDown`. `IOHIDCheckAccess` answers about HID, which reads granted while keys are withheld.
     */
    private fun isInputMonitoringGranted(): Boolean {
        applicationServices?.let { services ->
            runCatching { return services.CGPreflightListenEventAccess() }
        }
        val kit = ioKit ?: return assumeGranted("Input Monitoring")
        return runCatching { kit.IOHIDCheckAccess(REQUEST_LISTEN_EVENT) == ACCESS_GRANTED }
            .getOrElse { assumeGranted("Input Monitoring") }
    }
}
