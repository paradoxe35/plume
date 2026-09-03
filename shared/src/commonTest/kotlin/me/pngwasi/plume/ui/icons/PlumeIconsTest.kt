package me.pngwasi.plume.ui.icons

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorNode
import androidx.compose.ui.graphics.vector.VectorPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The icons are generated path data, so the failure mode is a silently truncated or malformed
 * path: it still compiles, and renders as nothing or as a smear. These build every icon and check
 * it actually contains geometry.
 */
class PlumeIconsTest {

    private val all: List<Pair<String, ImageVector>> = listOf(
        "Add" to PlumeIcons.Add,
        "ArrowBack" to PlumeIcons.ArrowBack,
        "AutoFixHigh" to PlumeIcons.AutoFixHigh,
        "Backspace" to PlumeIcons.Backspace,
        "Check" to PlumeIcons.Check,
        "CheckCircle" to PlumeIcons.CheckCircle,
        "ContentCopy" to PlumeIcons.ContentCopy,
        "ContentPaste" to PlumeIcons.ContentPaste,
        "DarkMode" to PlumeIcons.DarkMode,
        "Delete" to PlumeIcons.Delete,
        "ErrorOutline" to PlumeIcons.ErrorOutline,
        "Hub" to PlumeIcons.Hub,
        "Info" to PlumeIcons.Info,
        "Keyboard" to PlumeIcons.Keyboard,
        "KeyboardArrowRight" to PlumeIcons.KeyboardArrowRight,
        "KeyboardReturn" to PlumeIcons.KeyboardReturn,
        "LightMode" to PlumeIcons.LightMode,
        "OpenInNew" to PlumeIcons.OpenInNew,
        "PhoneAndroid" to PlumeIcons.PhoneAndroid,
        "PushPin" to PlumeIcons.PushPin,
        "RadioButtonUnchecked" to PlumeIcons.RadioButtonUnchecked,
        "Refresh" to PlumeIcons.Refresh,
        "Search" to PlumeIcons.Search,
        "Settings" to PlumeIcons.Settings,
        "SwapHoriz" to PlumeIcons.SwapHoriz,
        "Translate" to PlumeIcons.Translate,
        "Tune" to PlumeIcons.Tune,
        "Visibility" to PlumeIcons.Visibility,
        "VisibilityOff" to PlumeIcons.VisibilityOff,
    )

    private fun pathNodeCount(node: VectorNode): Int = when (node) {
        is VectorPath -> node.pathData.size
        is VectorGroup -> node.sumOf { pathNodeCount(it) }
        else -> 0
    }

    @Test
    fun `every icon parses into real geometry`() {
        all.forEach { (name, icon) ->
            // A malformed path parses to an empty node list rather than throwing.
            assertTrue(pathNodeCount(icon.root) > 2, "$name has no usable path data")
        }
    }

    @Test
    fun `every icon uses the 24dp Material viewport`() {
        all.forEach { (name, icon) ->
            assertEquals(24f, icon.viewportWidth, "$name viewport width")
            assertEquals(24f, icon.viewportHeight, "$name viewport height")
        }
    }

    /** Directional icons have to flip in right-to-left layouts; symmetric ones must not. */
    @Test
    fun `directional icons are auto-mirrored`() {
        val mirrored = all.filter { it.second.autoMirror }.map { it.first }.toSet()

        assertEquals(
            setOf("ArrowBack", "Backspace", "KeyboardArrowRight", "KeyboardReturn", "OpenInNew"),
            mirrored,
        )
    }

    @Test
    fun `the set covers every icon the app asks for`() {
        assertEquals(29, all.size)
        assertEquals(all.size, all.map { it.first }.distinct().size)
    }
}
