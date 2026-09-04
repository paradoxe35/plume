package me.pngwasi.plume.desktop

import me.pngwasi.plume.native.PlumeNativeLibrary

/** Rust logs to stdout, which a macOS `.app` discards — so a refused key listener left no trace. */
object NativeLogBridge {

    // JNA collects a callback as soon as nothing on this side holds it, taking the native
    // trampoline with it; the next call from Rust would then land on freed memory.
    private var sink: PlumeNativeLibrary.LogCallback? = null

    @Synchronized
    fun install(library: PlumeNativeLibrary) {
        if (sink != null) return
        val callback = PlumeNativeLibrary.LogCallback { message ->
            // Rust frees the string when this returns, so it has to be copied here, not kept.
            val text = runCatching { message?.getString(0) }.getOrNull()
            if (!text.isNullOrBlank()) PlumeLog.info(text.trim())
        }
        sink = callback
        runCatching { library.plume_set_log_callback(callback) }
            .onFailure { PlumeLog.error("The native layer's log could not be attached", it) }
    }
}
