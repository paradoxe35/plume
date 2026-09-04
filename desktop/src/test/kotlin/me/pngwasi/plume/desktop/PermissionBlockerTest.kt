package me.pngwasi.plume.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the home screen shows about macOS privileges.
 *
 * The restart step is the part that is easy to get wrong and impossible to notice: the listener is
 * wired once at launch, so a privilege granted afterwards never reaches it. Without the prompt the
 * user grants everything, sees the warning disappear, presses the shortcut and still gets nothing.
 */
class PermissionBlockerTest {

    private fun blocker(
        availability: HotkeyAvailability,
        permissions: MacPermissionState,
        missingAtLaunch: Boolean,
        supported: Boolean = true,
    ) = permissionBlocker(
        availability = availability,
        permissions = permissions,
        missingAtLaunch = missingAtLaunch,
        supported = supported,
        onGrant = {},
        onRestart = {},
    )

    private val needsBoth = HotkeyAvailability.NeedsPermission("needs them", "or nothing happens")

    @Test
    fun `a missing permission is listed with a way to grant it`() {
        val result = blocker(
            availability = needsBoth,
            permissions = MacPermissionState(accessibility = true, inputMonitoring = false),
            missingAtLaunch = true,
        )

        assertEquals(1, result?.fixes?.size)
        assertEquals(MacPermission.InputMonitoring.label, result?.fixes?.first()?.label)
        assertEquals("Grant", result?.fixes?.first()?.action)
    }

    @Test
    fun `both missing are listed separately`() {
        val result = blocker(
            availability = needsBoth,
            permissions = MacPermissionState(accessibility = false, inputMonitoring = false),
            missingAtLaunch = true,
        )

        assertEquals(2, result?.fixes?.size)
    }

    /** Granted during this run: the listener still has not seen them. */
    @Test
    fun `granting everything asks for a restart`() {
        val result = blocker(
            availability = HotkeyAvailability.Ready,
            permissions = MacPermissionState(accessibility = true, inputMonitoring = true),
            missingAtLaunch = true,
        )

        assertTrue(result != null, "nothing shown, so the shortcuts stay dead silently")
        assertEquals("Restart", result.fixes.single().action)
        assertTrue(result.summary.contains("Restart"), result.summary)
    }

    /** Granted before launch: the listener already has them, so there is nothing to say. */
    @Test
    fun `a session that started with permissions shows nothing`() {
        assertNull(
            blocker(
                availability = HotkeyAvailability.Ready,
                permissions = MacPermissionState(accessibility = true, inputMonitoring = true),
                missingAtLaunch = false,
            ),
        )
    }

    /** Linux and Windows have no such privileges, and must not be asked to restart. */
    @Test
    fun `systems without these permissions are left alone`() {
        assertNull(
            blocker(
                availability = HotkeyAvailability.Ready,
                permissions = MacPermissionState(accessibility = true, inputMonitoring = true),
                missingAtLaunch = true,
                supported = false,
            ),
        )
    }

    /** Wayland reports the same shape but has nothing to open, so it carries no rows. */
    @Test
    fun `a blocker with nothing to click still explains itself`() {
        val result = blocker(
            availability = HotkeyAvailability.NeedsPermission("input group", "run usermod"),
            permissions = MacPermissionState(accessibility = true, inputMonitoring = true),
            missingAtLaunch = false,
            supported = false,
        )

        assertEquals("input group", result?.summary)
        assertEquals(emptyList(), result?.fixes)
    }
}
