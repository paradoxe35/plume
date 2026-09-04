package me.pngwasi.plume.desktop

import com.sun.jna.Pointer
import me.pngwasi.plume.native.PlumeNativeLibrary

/**
 * A stand-in for the Rust layer that counts the frees.
 *
 * The real library cannot be asked whether it was freed twice — it aborts the process instead — so
 * the only way to hold that line in a test is to count from this side.
 */
class CountingNativeLibrary : PlumeNativeLibrary {
    var clipboardFrees = 0
    var simulatorFrees = 0
    var hotkeyFrees = 0
    var hotkeyStarts = 0
    var hotkeyStops = 0
    var lastSetText: String? = null

    override fun plume_clipboard_new(): Pointer? = Pointer(1L)
    override fun plume_simulator_new(): Pointer? = Pointer(2L)
    override fun plume_hotkey_manager_new(): Pointer? = Pointer(3L)

    override fun plume_clipboard_free(handle: Pointer?) {
        clipboardFrees++
    }

    override fun plume_simulator_free(handle: Pointer?) {
        simulatorFrees++
    }

    override fun plume_hotkey_listen_error(handle: Pointer?): Pointer? = null

    override fun plume_hotkey_manager_free(handle: Pointer?) {
        hotkeyFrees++
    }

    override fun plume_clipboard_set_text(handle: Pointer?, text: String): Int {
        if (handle == null) return 1
        lastSetText = text
        return 0
    }

    override fun plume_get_last_error(): Pointer? = null
    override fun plume_free_string(s: Pointer?) = Unit
    override fun plume_clipboard_get_text(handle: Pointer?): Pointer? = null
    override fun plume_clipboard_has_text(handle: Pointer?): Int = 0
    override fun plume_clipboard_clear(handle: Pointer?): Int = 0
    override fun plume_clipboard_save(handle: Pointer?): Int = 0
    override fun plume_clipboard_restore(handle: Pointer?): Int = 0
    override fun plume_hotkey_clear(handle: Pointer?): Int = 0
    override fun plume_hotkey_register(
        handle: Pointer?,
        binding: String,
        action: String,
        callback: PlumeNativeLibrary.HotkeyCallback,
    ): Int = 0
    override fun plume_hotkey_start(handle: Pointer?): Int {
        hotkeyStarts++
        return 0
    }

    override fun plume_hotkey_stop(handle: Pointer?): Int {
        hotkeyStops++
        return 0
    }
    override fun plume_simulate_select_all(handle: Pointer?): Int = 0
    override fun plume_simulate_copy(handle: Pointer?): Int = 0
    override fun plume_simulate_paste(handle: Pointer?): Int = 0
    override fun plume_simulate_release_modifiers(handle: Pointer?): Int = 0
}
