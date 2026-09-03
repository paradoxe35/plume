package me.pngwasi.plume.native

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer

/**
 * The Rust hotkey, clipboard and keystroke layer, mapped straight onto a Kotlin interface.
 *
 * JNA rather than JNI because the contract is twenty plain C functions and one function-pointer
 * callback, all of which JNA maps without a hand-written shim.
 */
interface PlumeNativeLibrary : Library {

    fun plume_get_last_error(): Pointer?
    fun plume_free_string(s: Pointer?)

    fun plume_clipboard_new(): Pointer?
    fun plume_clipboard_get_text(handle: Pointer?): Pointer?
    fun plume_clipboard_has_text(handle: Pointer?): Int
    fun plume_clipboard_set_text(handle: Pointer?, text: String): Int
    fun plume_clipboard_clear(handle: Pointer?): Int
    fun plume_clipboard_save(handle: Pointer?): Int
    fun plume_clipboard_restore(handle: Pointer?): Int
    fun plume_clipboard_free(handle: Pointer?)

    fun plume_hotkey_manager_new(): Pointer?
    fun plume_hotkey_clear(handle: Pointer?): Int
    fun plume_hotkey_register(
        handle: Pointer?,
        binding: String,
        action: String,
        callback: HotkeyCallback,
    ): Int
    fun plume_hotkey_start(handle: Pointer?): Int
    fun plume_hotkey_stop(handle: Pointer?): Int
    fun plume_hotkey_manager_free(handle: Pointer?)

    fun plume_simulator_new(): Pointer?
    fun plume_simulate_select_all(handle: Pointer?): Int
    fun plume_simulate_copy(handle: Pointer?): Int
    fun plume_simulate_paste(handle: Pointer?): Int
    fun plume_simulate_release_modifiers(handle: Pointer?): Int
    fun plume_simulator_free(handle: Pointer?)

    /** `void (*)(const char *action)`, invoked on the Rust listener thread. */
    fun interface HotkeyCallback : Callback {
        fun invoke(action: Pointer?)
    }
}

/**
 * Loads the library, from the packaged location first and the build output second.
 *
 * Failure here is expected and survivable: the settings window still opens, and the hotkey section
 * explains what is missing rather than the whole app refusing to start.
 */
object PlumeNative {

    sealed interface State {
        data class Ready(val library: PlumeNativeLibrary) : State
        data class Unavailable(val reason: String) : State
    }

    val state: State by lazy { load() }

    val library: PlumeNativeLibrary? get() = (state as? State.Ready)?.library

    private fun load(): State = try {
        State.Ready(Native.load(LIBRARY_NAME, PlumeNativeLibrary::class.java))
    } catch (e: UnsatisfiedLinkError) {
        State.Unavailable(
            "Plume's input library could not be loaded: ${e.message ?: "not found"}",
        )
    } catch (e: Exception) {
        State.Unavailable("Plume's input library could not be loaded: ${e.message}")
    }

    /** JNA maps this to libplume_native.so, plume_native.dll or libplume_native.dylib. */
    private const val LIBRARY_NAME = "plume_native"
}

/** Reads and frees a string the Rust side allocated. */
fun PlumeNativeLibrary.takeString(pointer: Pointer?): String? {
    if (pointer == null) return null
    return try {
        pointer.getString(0)
    } finally {
        plume_free_string(pointer)
    }
}

fun PlumeNativeLibrary.lastError(): String =
    takeString(plume_get_last_error()) ?: "unknown error"
