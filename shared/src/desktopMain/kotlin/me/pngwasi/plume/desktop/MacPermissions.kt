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
     * Asks macOS for the permission, where macOS allows asking.
     *
     * Input Monitoring has a real prompt. Accessibility's needs a `CFDictionary` of options, which
     * is more interop than it is worth, so it opens the pane instead — which is where the user ends
     * up anyway, since the switch has to be flicked by hand.
     */
    fun request(permission: MacPermission) {
        when (permission) {
            MacPermission.Accessibility -> openSettings(permission)
            MacPermission.InputMonitoring -> {
                runCatching { ioKit?.IOHIDRequestAccess(REQUEST_LISTEN_EVENT) }
                openSettings(permission)
            }
        }
    }

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
    }

    private interface IOKit : Library {
        /** 0 granted, 1 denied, 2 not yet asked. */
        fun IOHIDCheckAccess(request: Int): Int
        fun IOHIDRequestAccess(request: Int): Boolean
    }

    private const val REQUEST_LISTEN_EVENT = 1
    private const val ACCESS_GRANTED = 0

    private val applicationServices: ApplicationServices? by lazy { load("ApplicationServices") }
    private val ioKit: IOKit? by lazy { load("IOKit") }

    private inline fun <reified T : Library> load(framework: String): T? =
        if (!isSupported) null
        else runCatching { Native.load(framework, T::class.java) }.getOrNull()

    private fun isAccessibilityTrusted(): Boolean {
        val services = applicationServices ?: return true
        return runCatching { services.AXIsProcessTrusted() }.getOrDefault(true)
    }

    /**
     * `IOHIDCheckAccess` rather than opening an event tap to see whether it works: the tap has to
     * be created, probed and released, and it is the API Apple added for exactly this question.
     */
    private fun isInputMonitoringGranted(): Boolean {
        val kit = ioKit ?: return true
        return runCatching { kit.IOHIDCheckAccess(REQUEST_LISTEN_EVENT) == ACCESS_GRANTED }
            .getOrDefault(true)
    }
}
