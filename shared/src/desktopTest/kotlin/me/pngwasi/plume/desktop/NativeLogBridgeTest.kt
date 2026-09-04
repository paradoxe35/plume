package me.pngwasi.plume.desktop

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * JNA collects a callback the moment nothing on the Kotlin side references it, and the native
 * trampoline goes with it — so the next line Rust logs would land on freed memory. The bridge holds
 * the only reference, which is why it is installed once for the process and never replaced.
 */
class NativeLogBridgeTest {

    @Test
    fun `the sink is attached once and then left alone`() {
        val library = CountingNativeLibrary()

        NativeLogBridge.install(library)
        val attached = library.logCallback
        assertNotNull(attached, "nothing was attached, so native logs go nowhere")

        // A second install must not swap the callback the native side is already holding.
        val other = CountingNativeLibrary()
        NativeLogBridge.install(other)

        assertSame(attached, library.logCallback)
        assertNull(other.logCallback)
    }
}
