package me.pngwasi.plume.desktop

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The window is placed from screen bounds rather than by `WindowPosition.Aligned`.
 *
 * Aligned asks AWT for the screen insets, and macOS answers that by blocking the event thread on
 * the AppKit thread, uncached, every time — at the exact moment the window is being created.
 */
class CentredPositionTest {

    private val size = DpSize(485.dp, 660.dp)

    @Test
    fun `the window lands in the middle of the screen`() {
        val position = centredPosition(size, Rectangle(0, 0, 1920, 1080))

        assertEquals(717.5f.dp, position.x)
        assertEquals(210f.dp, position.y)
    }

    /** A second monitor starts at a non-zero origin, and the window belongs on that one. */
    @Test
    fun `an offset screen is centred on itself`() {
        val position = centredPosition(size, Rectangle(1920, 0, 1280, 800))

        assertEquals((1920f + (1280f - 485f) / 2f).dp, position.x)
    }

    /** Small screens must not push the title bar off the top, where it cannot be grabbed. */
    @Test
    fun `a screen smaller than the window still starts on screen`() {
        val position = centredPosition(DpSize(485.dp, 660.dp), Rectangle(0, 0, 400, 400))

        assertTrue(position.x.value >= 0f, "${position.x}")
        assertTrue(position.y.value >= 0f, "${position.y}")
    }
}
