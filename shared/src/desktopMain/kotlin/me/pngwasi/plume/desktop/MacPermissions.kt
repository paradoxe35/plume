package me.pngwasi.plume.desktop

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
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
            MacPermission.Accessibility -> {
                runCatching { promptForAccessibility() }
                openSettings(permission)
            }
            MacPermission.InputMonitoring -> {
                // The prompt that also puts Plume in the list, so there is something to switch on.
                runCatching { applicationServices?.CGRequestListenEventAccess() }
                    .onFailure { runCatching { ioKit?.IOHIDRequestAccess(REQUEST_LISTEN_EVENT) } }
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

        /** With the prompt option, this is also what puts Plume in the Accessibility list. */
        fun AXIsProcessTrustedWithOptions(options: Pointer?): Boolean

        /** The question an event tap actually asks. macOS 10.15 and later. */
        fun CGPreflightListenEventAccess(): Boolean
        fun CGRequestListenEventAccess(): Boolean
    }

    private interface CoreFoundation : Library {
        fun CFDictionaryCreate(
            allocator: Pointer?,
            keys: Array<Pointer?>,
            values: Array<Pointer?>,
            numValues: Long,
            keyCallBacks: Pointer?,
            valueCallBacks: Pointer?,
        ): Pointer?

        fun CFRelease(cf: Pointer?)
    }

    private interface IOKit : Library {
        /** 0 granted, 1 denied, 2 not yet asked. */
        fun IOHIDCheckAccess(request: Int): Int
        fun IOHIDRequestAccess(request: Int): Boolean
    }

    private const val REQUEST_LISTEN_EVENT = 1
    private const val ACCESS_GRANTED = 0

    private val applicationServices: ApplicationServices? by lazy { load("ApplicationServices") }
    private val coreFoundation: CoreFoundation? by lazy { load("CoreFoundation") }

    /** The address of a framework global; `dereference` for the ones holding a pointer. */
    private fun global(framework: String, symbol: String, dereference: Boolean): Pointer? =
        runCatching {
            val address = NativeLibrary.getInstance(framework).getGlobalVariableAddress(symbol)
            if (dereference) address.getPointer(0) else address
        }.getOrNull()

    /**
     * Shows the system's own Accessibility dialog, which is the only thing that adds Plume to that
     * list. Opening the settings pane does not: without this the user is sent to a list Plume is
     * not in, with nothing to switch on — which is how a permission stays ungranted forever.
     */
    private fun promptForAccessibility(): Boolean {
        val services = applicationServices ?: return false
        val cf = coreFoundation ?: return false
        val promptKey = global("ApplicationServices", "kAXTrustedCheckOptionPrompt", true)
            ?: return false
        val yes = global("CoreFoundation", "kCFBooleanTrue", true) ?: return false
        val keyCallbacks = global("CoreFoundation", "kCFTypeDictionaryKeyCallBacks", false)
            ?: return false
        val valueCallbacks = global("CoreFoundation", "kCFTypeDictionaryValueCallBacks", false)
            ?: return false

        val options = cf.CFDictionaryCreate(
            null, arrayOf(promptKey), arrayOf(yes), 1, keyCallbacks, valueCallbacks,
        ) ?: return false
        return try {
            services.AXIsProcessTrustedWithOptions(options)
        } finally {
            // CFDictionaryCreate returns it retained; the keys and values are constants we borrow.
            cf.CFRelease(options)
        }
    }
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

    /**
     * Every check here fails towards "granted": warning about a privilege that was in fact given is
     * worse than staying quiet. But this is also the state where Plume reports itself ready while
     * the system withholds key presses, so it does not get to be silent as well as wrong.
     */
    private fun assumeGranted(permission: String): Boolean {
        PlumeLog.error("Could not read the macOS $permission state; assuming it is granted")
        return true
    }

    /**
     * `CGPreflightListenEventAccess` first, because it is the question the key listener actually
     * asks: whether this process may receive `KeyDown` through an event tap. `IOHIDCheckAccess`
     * answers about HID access, which can read as granted while key events are still withheld —
     * and that reads to the user as a shortcut that silently does nothing.
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
