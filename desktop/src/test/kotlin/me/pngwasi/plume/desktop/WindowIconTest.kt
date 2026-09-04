package me.pngwasi.plume.desktop

import androidx.compose.ui.graphics.toPixelMap
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The icon is drawn rather than loaded from the classpath, because ProGuard strips the resource
 * from the minified build and it goes missing only in the installed package.
 */
class WindowIconTest {

    @Test
    fun `the icon is drawn rather than left blank`() {
        val pixels = renderAppIcon(128).toPixelMap()

        var opaque = 0
        for (y in 0 until 128) {
            for (x in 0 until 128) {
                if (pixels[x, y].alpha > 0.9f) opaque++
            }
        }

        // A rounded square covers most of its bounding box.
        assertTrue(opaque > 128 * 128 * 0.7, "the icon is mostly empty")
    }

    @Test
    fun `the icon carries both the background and the quill`() {
        val pixels = renderAppIcon(128).toPixelMap()

        var teal = 0
        var pale = 0
        for (y in 0 until 128) {
            for (x in 0 until 128) {
                val pixel = pixels[x, y]
                if (pixel.alpha < 0.5f) continue
                if (pixel.red < 0.3f && pixel.green > 0.2f && pixel.blue > 0.2f) teal++
                if (pixel.red > 0.85f && pixel.green > 0.85f) pale++
            }
        }

        assertTrue(teal > 0, "the teal ground is missing")
        assertTrue(pale > 0, "the quill is missing")
    }

    @Test
    fun `the corners are rounded rather than square`() {
        val pixels = renderAppIcon(128).toPixelMap()

        assertTrue(pixels[0, 0].alpha < 0.5f, "the top-left corner is not rounded")
        assertTrue(pixels[127, 127].alpha < 0.5f, "the bottom-right corner is not rounded")
        assertTrue(pixels[64, 64].alpha > 0.9f, "the middle is not filled")
    }
}
