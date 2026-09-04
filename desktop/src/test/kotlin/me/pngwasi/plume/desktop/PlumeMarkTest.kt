package me.pngwasi.plume.desktop

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import me.pngwasi.plume.ui.icons.PlumeMark
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The quill mark, which is what the tray shows.
 *
 * Two things have gone wrong here and neither failed a build. It rendered as a blank square when
 * drawn through a painter that needed a composition; and it rendered small and weak because the
 * artwork was drawn for Android's adaptive icon, which reserves a wide margin the launcher masks
 * away — on a tray there is no mask, so half the cell was empty. Both are only visible by
 * rasterising it and measuring.
 */
class PlumeMarkTest {

    private fun rasterise(size: Int): ImageBitmap {
        val bitmap = ImageBitmap(size, size)
        val path = PlumeMark.path()
        CanvasDrawScope().draw(
            Density(1f),
            LayoutDirection.Ltr,
            Canvas(bitmap),
            Size(size.toFloat(), size.toFloat()),
        ) {
            val factor = size / PlumeMark.VIEWPORT
            scale(scaleX = factor, scaleY = factor, pivot = Offset.Zero) {
                drawPath(path, Color.Black)
            }
        }
        return bitmap
    }

    private fun coverage(size: Int): Double {
        val pixels = rasterise(size).toPixelMap()
        var painted = 0
        for (y in 0 until size) {
            for (x in 0 until size) {
                if (pixels[x, y].alpha > 0.1f) painted++
            }
        }
        return painted.toDouble() / (size * size)
    }

    @Test
    fun `the mark paints pixels rather than nothing`() {
        assertTrue(coverage(64) > 0.05, "the mark drew almost nothing")
    }

    @Test
    fun `the mark renders at every size a tray might ask for`() {
        listOf(16, 22, 24, 32, 48, 64, 128).forEach { size ->
            assertTrue(coverage(size) > 0.05, "the mark was blank at ${size}px")
        }
    }

    /**
     * The regression that made it look weak on a panel: the quill covered under a tenth of its
     * canvas, so at 22px it was a smudge surrounded by nothing.
     */
    @Test
    fun `the mark fills its canvas rather than sitting small inside it`() {
        val coverage = coverage(128)

        // A slim diagonal quill will never fill a square, but a fitted one covers far more than
        // the 8% the unfitted artwork managed.
        assertTrue(coverage > 0.15, "the mark covers only ${(coverage * 100).toInt()}% of its canvas")
    }

    /** Fitted means centred, or it drifts into one corner of the tray cell. */
    @Test
    fun `the mark is centred in its viewport`() {
        val bounds = PlumeMark.path().getBounds()

        val leftGap = bounds.left
        val rightGap = PlumeMark.VIEWPORT - bounds.right
        val topGap = bounds.top
        val bottomGap = PlumeMark.VIEWPORT - bounds.bottom

        assertTrue(abs(leftGap - rightGap) < 1f, "off centre horizontally: $leftGap against $rightGap")
        assertTrue(abs(topGap - bottomGap) < 1f, "off centre vertically: $topGap against $bottomGap")
    }

    /** And it keeps a margin, so it does not touch the edge of the cell. */
    @Test
    fun `the mark keeps a margin from the edge`() {
        val bounds = PlumeMark.path().getBounds()

        assertTrue(bounds.top > 1f, "the mark touches the top edge")
        assertTrue(bounds.bottom < PlumeMark.VIEWPORT - 1f, "the mark touches the bottom edge")
    }
}
