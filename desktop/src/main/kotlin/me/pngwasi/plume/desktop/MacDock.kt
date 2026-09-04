package me.pngwasi.plume.desktop

import com.sun.jna.Callback
import com.sun.jna.Function
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import me.pngwasi.plume.data.DesktopOs

/**
 * Whether Plume appears in the Dock, which on macOS follows whether a window is open.
 *
 * A tray utility with no window should not sit in the Dock or in Cmd-Tab; one showing a settings
 * window should, or the window cannot be reached again after the user clicks away.
 *
 * Two things make this fiddly, and MyReviser got both wrong:
 *
 * **Switching to Regular is not enough.** `setActivationPolicy:` alone leaves the new window behind
 * whatever the user was in, without keyboard focus — the window is technically shown and
 * practically unusable. It has to be followed by activating the app.
 *
 * **AppKit is main-thread only.** These calls arrive from a Compose recomposition or a hotkey
 * callback, neither of which is the AppKit thread, so they are dispatched onto the main queue
 * rather than made directly. Calling straight through happens to work until it does not, and the
 * failure is a hang.
 *
 * The policy is read back after being set, and `TransformProcessType` is the fallback when it did
 * not take: the bundle declares `LSUIElement`, so an app that fails to leave Accessory has no Dock
 * entry and no way back to its own window, and it would fail in silence.
 */
object MacDock {

    private const val POLICY_REGULAR = 0L
    private const val POLICY_ACCESSORY = 1L

    /** `kProcessTransformToForegroundApplication` and `kProcessTransformToUIElementApplication`. */
    private const val TRANSFORM_FOREGROUND = 1
    private const val TRANSFORM_UI_ELEMENT = 4

    val isSupported: Boolean get() = DesktopOs.current == DesktopOs.MacOs && runtime != null

    /**
     * Window on screen: show in the Dock, and bring it to the front.
     *
     * Activation is dispatched separately so it lands on the run-loop turn after the policy change.
     * Doing both in one turn is the reported failure: the icon appears, and the app stays behind
     * with an inert menu bar until the user clicks that icon.
     */
    fun showInDock() {
        onMainThread { applyPolicy(POLICY_REGULAR) }
        onMainThread { activate() }
    }

    /** Window closed: back to a menu-bar-only app. */
    fun hideFromDock() = onMainThread { applyPolicy(POLICY_ACCESSORY) }

    private fun applyPolicy(policy: Long) {
        setActivationPolicy(policy)
        if (activationPolicy() == policy) return

        val transform = if (policy == POLICY_REGULAR) TRANSFORM_FOREGROUND else TRANSFORM_UI_ELEMENT
        val status = transformProcessType(transform)
        PlumeLog.error(
            "macOS did not take activation policy $policy (now ${activationPolicy()}); " +
                "TransformProcessType returned $status",
        )
    }

    /**
     * Logged once at startup: without it a Dock that never appears has no trail to follow.
     *
     * Reports what loaded rather than the current policy, because reading that is an AppKit call
     * and this runs wherever the log line is written.
     */
    fun diagnostics(): String = when {
        DesktopOs.current != DesktopOs.MacOs -> "not macOS"
        runtime == null -> "the Objective-C runtime could not be reached"
        msgSend == null -> "objc_msgSend could not be resolved"
        mainQueue == null -> "the main dispatch queue could not be found"
        else -> "ready, fallback ${if (services != null) "available" else "unavailable"}"
    }

    private interface ObjC : Library {
        fun objc_getClass(name: String): Pointer?
        fun sel_registerName(name: String): Pointer?
    }

    /**
     * The Carbon fallback. Deprecated in favour of `setActivationPolicy:`, still working, and worth
     * keeping precisely because it reaches the Dock without going through `objc_msgSend` — which is
     * the part of the path above that cannot be checked from anywhere but a Mac.
     */
    private interface ApplicationServices : Library {
        fun TransformProcessType(process: ProcessSerialNumber, transform: Int): Int
    }

    @Structure.FieldOrder("highLongOfPSN", "lowLongOfPSN")
    class ProcessSerialNumber : Structure() {
        @JvmField var highLongOfPSN: Int = 0

        /** `kCurrentProcess`. */
        @JvmField var lowLongOfPSN: Int = 2
    }

    private interface Dispatch : Library {
        fun dispatch_async_f(queue: Pointer?, context: Pointer?, work: DispatchFunction)

        fun interface DispatchFunction : Callback {
            fun invoke(context: Pointer?)
        }
    }

    private val runtime: ObjC? by lazy {
        if (DesktopOs.current != DesktopOs.MacOs) null
        else runCatching { Native.load("objc", ObjC::class.java) }.getOrNull()
    }

    private val dispatch: Dispatch? by lazy {
        if (DesktopOs.current != DesktopOs.MacOs) null
        else runCatching { Native.load("System", Dispatch::class.java) }.getOrNull()
    }

    private val services: ApplicationServices? by lazy {
        if (DesktopOs.current != DesktopOs.MacOs) null
        else runCatching { Native.load("ApplicationServices", ApplicationServices::class.java) }
            .getOrNull()
    }

    private val mainQueue: Pointer? by lazy {
        // dispatch_get_main_queue() is a macro over the _dispatch_main_q symbol, so there is no
        // function to call — the address of the global is the queue.
        runCatching {
            com.sun.jna.NativeLibrary.getInstance("System")
                .getGlobalVariableAddress("_dispatch_main_q")
        }.getOrNull()
    }

    private val msgSend: Function? by lazy {
        runCatching {
            com.sun.jna.NativeLibrary.getInstance("objc").getFunction("objc_msgSend")
        }.getOrNull()
    }

    private fun sharedApplication(): Pointer? {
        val objc = runtime ?: return null
        val send = msgSend ?: return null
        val cls = objc.objc_getClass("NSApplication") ?: return null
        val sel = objc.sel_registerName("sharedApplication") ?: return null
        return send.invokePointer(arrayOf(cls, sel))
    }

    private fun setActivationPolicy(policy: Long) {
        val objc = runtime ?: return
        val send = msgSend ?: return
        val app = sharedApplication() ?: return
        val sel = objc.sel_registerName("setActivationPolicy:") ?: return
        send.invokeInt(arrayOf(app, sel, policy))
    }

    /** Null when the bridge is unreachable, which is different from "the policy is Regular". */
    private fun activationPolicy(): Long? {
        val objc = runtime ?: return null
        val send = msgSend ?: return null
        val app = sharedApplication() ?: return null
        val sel = objc.sel_registerName("activationPolicy") ?: return null
        return runCatching { send.invokeLong(arrayOf(app, sel)) }.getOrNull()
    }

    private fun transformProcessType(transform: Int): Int? =
        runCatching { services?.TransformProcessType(ProcessSerialNumber(), transform) }.getOrNull()

    /**
     * `activate` on macOS 14+, falling back to `activateIgnoringOtherApps:` on older systems —
     * without which the window opens behind whatever the user was looking at.
     */
    /**
     * macOS 14's `activate` is only a suggestion: the frontmost app has to yield, and it does not
     * for a menu-bar click. `activateIgnoringOtherApps:` is deprecated rather than gone.
     */
    private fun activate() {
        val objc = runtime ?: return
        val send = msgSend ?: return
        val app = sharedApplication() ?: return
        val forceful = objc.sel_registerName("activateIgnoringOtherApps:")
        if (forceful != null && respondsTo(app, "activateIgnoringOtherApps:")) {
            send.invokeVoid(arrayOf(app, forceful, 1))
            return
        }
        val cooperative = objc.sel_registerName("activate") ?: return
        send.invokeVoid(arrayOf(app, cooperative))
    }

    private fun respondsTo(target: Pointer, selector: String): Boolean {
        val objc = runtime ?: return false
        val send = msgSend ?: return false
        val respondsSel = objc.sel_registerName("respondsToSelector:") ?: return false
        val sel = objc.sel_registerName(selector) ?: return false
        return send.invokeInt(arrayOf(target, respondsSel, sel)) != 0
    }

    // Held for the lifetime of the process: a callback that native code still holds must not be
    // collected, and these are one-shot dispatches whose timing we do not control.
    private val pending = java.util.concurrent.ConcurrentLinkedQueue<Dispatch.DispatchFunction>()

    private fun onMainThread(block: () -> Unit) {
        if (!isSupported) return
        val queue = mainQueue
        val dispatcher = dispatch
        if (queue == null || dispatcher == null) {
            // Better a direct call than silently doing nothing; this is the path taken only when
            // libdispatch could not be reached at all.
            runCatching { block() }
            return
        }
        lateinit var work: Dispatch.DispatchFunction
        work = Dispatch.DispatchFunction {
            runCatching { block() }
            pending.remove(work)
        }
        pending.add(work)
        dispatcher.dispatch_async_f(queue, null, work)
    }
}
