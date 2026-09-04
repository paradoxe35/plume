package me.pngwasi.plume.desktop

import com.sun.jna.Pointer
import me.pngwasi.plume.native.PlumeNative
import me.pngwasi.plume.native.PlumeNativeLibrary
import me.pngwasi.plume.native.takeString

/**
 * The desktop's whole view of the outside world: the clipboard, and the ability to press keys.
 *
 * An interface because the sequencing built on top of it is the part that gets subtly wrong, and
 * that part has to be testable on a machine with no display server.
 */
interface SystemInput {
    /** Null when the clipboard is empty or holds something that is not text. */
    fun clipboardText(): String?
    fun setClipboardText(text: String): Boolean
    fun clearClipboard(): Boolean

    /** Remembers the clipboard so [restoreClipboard] can put it back. */
    fun saveClipboard(): Boolean
    fun restoreClipboard(): Boolean

    fun releaseModifiers(): Boolean
    fun selectAll(): Boolean
    fun copy(): Boolean
    fun paste(): Boolean
}

/** [SystemInput] over the Rust layer. */
class NativeSystemInput(
    private val library: PlumeNativeLibrary,
) : SystemInput, AutoCloseable {

    // Cleared by close, which is what makes freeing twice impossible. Volatile because the tray
    // thread and the window thread both reach shutdown.
    @Volatile
    private var clipboard: Pointer? = library.plume_clipboard_new()

    @Volatile
    private var simulator: Pointer? = library.plume_simulator_new()

    val isUsable: Boolean get() = clipboard != null && simulator != null

    override fun clipboardText(): String? =
        library.takeString(library.plume_clipboard_get_text(clipboard))?.takeIf { it.isNotEmpty() }

    override fun setClipboardText(text: String): Boolean =
        library.plume_clipboard_set_text(clipboard, text) == 0

    override fun clearClipboard(): Boolean = library.plume_clipboard_clear(clipboard) == 0

    override fun saveClipboard(): Boolean = library.plume_clipboard_save(clipboard) == 0

    override fun restoreClipboard(): Boolean = library.plume_clipboard_restore(clipboard) == 0

    override fun releaseModifiers(): Boolean =
        library.plume_simulate_release_modifiers(simulator) == 0

    override fun selectAll(): Boolean = library.plume_simulate_select_all(simulator) == 0

    override fun copy(): Boolean = library.plume_simulate_copy(simulator) == 0

    override fun paste(): Boolean = library.plume_simulate_paste(simulator) == 0

    /**
     * Idempotent, and it has to be: quitting, closing the window and restarting all shut the
     * controller down, and freeing a handle twice aborts the process rather than raising anything
     * Kotlin can catch — the Rust side rebuilds a `Box` from the pointer, so the second free hands
     * malloc memory that is no longer ours.
     */
    @Synchronized
    override fun close() {
        clipboard?.let { library.plume_clipboard_free(it) }
        clipboard = null
        simulator?.let { library.plume_simulator_free(it) }
        simulator = null
    }

    companion object {
        /** Null when the native library is unavailable; the caller reports that, not a crash. */
        fun createOrNull(): NativeSystemInput? {
            val library = PlumeNative.library ?: return null
            val input = NativeSystemInput(library)
            if (!input.isUsable) {
                input.close()
                return null
            }
            return input
        }
    }
}
