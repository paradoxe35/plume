package me.pngwasi.plume.desktop

import me.pngwasi.plume.data.DesktopOs
import me.pngwasi.plume.native.PlumeNative
import me.pngwasi.plume.native.PlumeNativeLibrary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * macOS grants Accessibility and Input Monitoring separately, and Plume needs both: one lets it
 * see the shortcut, the other lets it replace the text. Checking only Accessibility is why the app
 * reported "Shortcuts are active" on a Mac where nothing worked.
 */
class MacPermissionsTest {

    /** The library is only pattern-matched here, never called, so a stub is enough. */
    private val library = java.lang.reflect.Proxy.newProxyInstance(
        PlumeNativeLibrary::class.java.classLoader,
        arrayOf(PlumeNativeLibrary::class.java),
    ) { _, _, _ -> null } as PlumeNativeLibrary

    private fun availability(accessibility: Boolean, inputMonitoring: Boolean) =
        hotkeyAvailability(
            nativeState = PlumeNative.State.Ready(library),
            os = DesktopOs.MacOs,
            macPermissions = { MacPermissionState(accessibility, inputMonitoring) },
        )

    @Test
    fun `both granted is ready`() {
        assertEquals(
            HotkeyAvailability.Ready,
            availability(accessibility = true, inputMonitoring = true),
        )
    }

    /** The case that was silently reported as working. */
    @Test
    fun `accessibility alone is not enough`() {
        val result = availability(accessibility = true, inputMonitoring = false)

        assertTrue(result is HotkeyAvailability.NeedsPermission, "$result")
        assertTrue(result.summary.contains("Input Monitoring"), result.summary)
    }

    @Test
    fun `input monitoring alone is not enough`() {
        val result = availability(accessibility = false, inputMonitoring = true)

        assertTrue(result is HotkeyAvailability.NeedsPermission, "$result")
        assertTrue(result.summary.contains("Accessibility"), result.summary)
    }

    /** Both missing has to name both, or the user grants one and is no better off. */
    @Test
    fun `neither granted names both`() {
        val result = availability(accessibility = false, inputMonitoring = false)

        assertTrue(result is HotkeyAvailability.NeedsPermission, "$result")
        assertTrue(result.summary.contains("Accessibility"), result.summary)
        assertTrue(result.summary.contains("Input Monitoring"), result.summary)
    }

    @Test
    fun `missing lists only what is missing`() {
        assertEquals(emptyList(), MacPermissionState(true, true).missing)
        assertEquals(
            listOf(MacPermission.InputMonitoring),
            MacPermissionState(accessibility = true, inputMonitoring = false).missing,
        )
        assertEquals(
            listOf(MacPermission.Accessibility, MacPermission.InputMonitoring),
            MacPermissionState(accessibility = false, inputMonitoring = false).missing,
        )
    }

    /** Every permission needs a reason the user can act on, not just a name. */
    @Test
    fun `each permission explains itself`() {
        MacPermission.entries.forEach {
            assertTrue(it.label.isNotBlank(), "$it has no label")
            assertTrue(it.why.length > 20, "$it does not explain what it is for: ${it.why}")
        }
    }
}
