package me.pngwasi.plume.desktop

import me.pngwasi.plume.data.DesktopOs
import me.pngwasi.plume.native.PlumeNative
import me.pngwasi.plume.native.PlumeNativeLibrary
import me.pngwasi.plume.native.lastError
import java.io.File
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * That the Rust library actually loads and its symbols resolve.
 *
 * This is the seam most likely to break silently: a renamed export, a missing build, a mismatched
 * signature — none of which the Kotlin compiler can see, because JNA binds by name at runtime. The
 * first sign in production would be the hotkeys doing nothing.
 *
 * The clipboard and simulator need a display server and are not exercised here; their sequencing is
 * covered by TextCaptureTest against a fake, and the hotkey manager needs no display.
 */
class NativeBindingTest {

    @BeforeTest
    fun setUp() {
        NativeLibraryPath.configure(projectRoot = File("..").absoluteFile)
    }

    private fun libraryBuilt(): Boolean =
        File("../native/target/release").listFiles()
            ?.any { it.name.startsWith("libplume_native") || it.name.startsWith("plume_native") }
            ?: false

    @Test
    fun `the library loads from the cargo output`() {
        if (!libraryBuilt()) return

        val state = PlumeNative.state

        assertTrue(
            state is PlumeNative.State.Ready,
            "expected the library to load, got $state",
        )
    }

    /**
     * Creating and freeing the hotkey manager touches the allocation path on both sides of the
     * boundary without needing a display, so a mismatched calling convention shows up here.
     */
    @Test
    fun `the hotkey manager can be created and freed`() {
        if (!libraryBuilt()) return
        val library = PlumeNative.library ?: return

        val manager = library.plume_hotkey_manager_new()

        assertNotNull(manager, "hotkey manager was null: ${library.lastError()}")
        assertEquals(0, library.plume_hotkey_clear(manager))
        library.plume_hotkey_manager_free(manager)
    }

    /** A null handle must be rejected rather than dereferenced. */
    @Test
    fun `null handles are refused instead of crashing`() {
        if (!libraryBuilt()) return
        val library = PlumeNative.library ?: return

        assertEquals(-1, library.plume_clipboard_save(null))
        assertEquals(-1, library.plume_simulate_copy(null))
    }

    /**
     * The packaged app has no cargo output beside it, so the library has to travel inside the jar
     * at JNA's own resource prefix. If this moves, the installers still build and the app still
     * starts — and every shortcut silently does nothing.
     */
    @Test
    fun `the library is on the classpath where JNA looks for it`() {
        if (!libraryBuilt()) return

        val path = "${com.sun.jna.Platform.RESOURCE_PREFIX}/${System.mapLibraryName("plume_native")}"

        assertNotNull(
            javaClass.classLoader.getResource(path),
            "expected the native library packaged at $path",
        )
    }

    /** Every function the Kotlin interface declares must exist in the built library. */
    @Test
    fun `every declared symbol resolves`() {
        if (!libraryBuilt()) return
        val library = PlumeNative.library ?: return

        // Reaching a function through JNA resolves it; an absent one throws UnsatisfiedLinkError.
        val manager = library.plume_hotkey_manager_new()
        library.plume_hotkey_stop(manager)
        library.plume_hotkey_manager_free(manager)

        // These return null on a headless machine, which is a valid answer rather than a link
        // failure — the Rust layer logs "no successful connection" on the way, and that line in a
        // CI log is expected rather than a problem.
        library.plume_clipboard_free(library.plume_clipboard_new())
        library.plume_simulator_free(library.plume_simulator_new())
    }

    private val ignored = PlumeNativeLibrary.HotkeyCallback {}

    private fun <T> withManager(block: (PlumeNativeLibrary, com.sun.jna.Pointer) -> T): T? {
        if (!libraryBuilt()) return null
        val library = PlumeNative.library ?: return null
        val manager = library.plume_hotkey_manager_new() ?: return null
        return try {
            block(library, manager)
        } finally {
            library.plume_hotkey_manager_free(manager)
        }
    }

    /**
     * Every key the recorder can save must be one the listener can match.
     *
     * The two tables are in different languages and neither compiler can see the other, so a name
     * only one of them knows saves cleanly and then never fires — which on macOS is exactly what a
     * withheld permission looks like. Driven from the recorder's own table so adding a key there
     * without teaching the listener about it fails here.
     */
    @Test
    fun `every key the recorder can save is one the listener accepts`() {
        withManager { library, manager ->
            HotkeyRecorder.keyNames().forEach { name ->
                val binding = "ctrl+alt+$name"
                assertEquals(
                    0,
                    library.plume_hotkey_register(manager, binding, "revise_all", ignored),
                    "the listener refused $binding: ${library.lastError()}",
                )
            }
        }
    }

    /** The bindings Plume ships on each system, checked against the listener that has to match them. */
    @Test
    fun `every shipped default is a binding the listener accepts`() {
        withManager { library, manager ->
            DesktopOs.entries.forEach { os ->
                val defaults = hotkeyDefaultsFor(os)
                listOf(defaults.reviseSelection, defaults.reviseAll, defaults.translateSelection)
                    .forEach { binding ->
                        assertEquals(
                            0,
                            library.plume_hotkey_register(manager, binding, "revise_all", ignored),
                            "$os ships $binding, which the listener refused: ${library.lastError()}",
                        )
                    }
            }
        }
    }
}
