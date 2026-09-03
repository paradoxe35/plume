package me.pngwasi.plume.desktop

import com.sun.jna.Callback
import com.sun.jna.Function
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
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
 */
object MacDock {

    private const val POLICY_REGULAR = 0L
    private const val POLICY_ACCESSORY = 1L

    val isSupported: Boolean get() = DesktopOs.current == DesktopOs.MacOs && runtime != null

    /** Window on screen: show in the Dock, and bring it to the front. */
    fun showInDock() = onMainThread {
        setActivationPolicy(POLICY_REGULAR)
        activate()
    }

    /** Window closed: back to a menu-bar-only app. */
    fun hideFromDock() = onMainThread {
        setActivationPolicy(POLICY_ACCESSORY)
    }

    // --- Objective-C runtime -------------------------------------------------------------------

    private interface ObjC : Library {
        fun objc_getClass(name: String): Pointer?
        fun sel_registerName(name: String): Pointer?
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

    /**
     * `activate` on macOS 14+, falling back to `activateIgnoringOtherApps:` on older systems —
     * without which the window opens behind whatever the user was looking at.
     */
    private fun activate() {
        val objc = runtime ?: return
        val send = msgSend ?: return
        val app = sharedApplication() ?: return
        val modern = objc.sel_registerName("activate")
        if (modern != null && respondsTo(app, "activate")) {
            send.invokeVoid(arrayOf(app, modern))
            return
        }
        val legacy = objc.sel_registerName("activateIgnoringOtherApps:") ?: return
        send.invokeVoid(arrayOf(app, legacy, 1))
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
