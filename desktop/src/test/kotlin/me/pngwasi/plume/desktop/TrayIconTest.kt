package me.pngwasi.plume.desktop

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import me.pngwasi.plume.ui.icons.PlumeMark
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * That the tray icon actually draws something.
 *
 * The first version used `rememberVectorPainter`, which renders from an internal composition. The
 * tray asks its painter for an image once, outside any composition frame, so nothing was ever
 * drawn — the icon shipped as a blank white square and nothing in the build complained. This
 * rasterises the painter the same way and counts the pixels it covers.
 */
class TrayIconTest {

    /** Mirrors [rememberTrayIcon] without the composable wrapper. */
    private fun painter(tint: Color): Painter = object : Painter() {
        private val path = PlumeMark.path()
        override val intrinsicSize = Size(PlumeMark.VIEWPORT, PlumeMark.VIEWPORT)
        override fun DrawScope.onDraw() {
            val factor = size.minDimension / PlumeMark.VIEWPORT
            scale(scaleX = factor, scaleY = factor, pivot = Offset.Zero) {
                drawPath(path, tint)
            }
        }
    }

    private fun rasterise(size: Int, tint: Color): ImageBitmap {
        val bitmap = ImageBitmap(size, size)
        val canvas = Canvas(bitmap)
        CanvasDrawScope().draw(
            Density(1f),
            LayoutDirection.Ltr,
            canvas,
            Size(size.toFloat(), size.toFloat()),
        ) {
            with(painter(tint)) { draw(Size(size.toFloat(), size.toFloat())) }
        }
        return bitmap
    }

    private fun coverage(size: Int, tint: Color = Color(0xFF1E4D4A)): Double {
        val pixels = rasterise(size, tint).toPixelMap()
        var painted = 0
        for (y in 0 until size) {
            for (x in 0 until size) {
                if (pixels[x, y].alpha > 0.1f) painted++
            }
        }
        return painted.toDouble() / (size * size)
    }

    @Test
    fun `the icon paints pixels rather than nothing`() {
        // The quill is a slim diagonal, so it covers a modest share of the square — but far more
        // than the zero the blank version produced.
        assertTrue(coverage(64) > 0.05, "tray icon drew almost nothing")
    }

    /** Trays request whatever size the desktop and its scaling call for. */
    @Test
    fun `the icon scales to every size a tray might ask for`() {
        listOf(16, 22, 24, 32, 48, 64, 128).forEach { size ->
            assertTrue(coverage(size) > 0.03, "tray icon was blank at ${size}px")
        }
    }

    @Test
    fun `the icon is drawn in the requested colour`() {
        val tint = Color(0xFF7FD1C4)
        val pixels = rasterise(64, tint).toPixelMap()

        var matched = 0
        for (y in 0 until 64) {
            for (x in 0 until 64) {
                val pixel = pixels[x, y]
                if (pixel.alpha > 0.9f && pixel.red > 0.4f && pixel.blue > 0.6f) matched++
            }
        }

        assertTrue(matched > 0, "no pixel carried the tint")
    }

    /** Light and dark trays need different marks, or one of them is invisible. */
    @Test
    fun `the light and dark tints differ`() {
        val light = rasterise(32, Color(0xFF1E4D4A)).toPixelMap()
        val dark = rasterise(32, Color(0xFFE6EBE9)).toPixelMap()

        var different = 0
        for (y in 0 until 32) {
            for (x in 0 until 32) {
                if (light[x, y] != dark[x, y]) different++
            }
        }

        assertTrue(different > 0, "the two tints rasterised identically")
    }
}
