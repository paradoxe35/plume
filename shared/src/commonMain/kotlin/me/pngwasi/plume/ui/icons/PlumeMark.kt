package me.pngwasi.plume.ui.icons

import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.vector.PathParser

/**
 * Plume's own quill mark, as a single-colour silhouette.
 *
 * This is the themed-icon artwork from the Android launcher rather than the colour one: the colour
 * version's four overlapping shapes flatten into a blob the moment they share a tint, which is
 * exactly what a tray does. The vane is one `evenOdd` path with the shaft punched out as a hole, so
 * the quill keeps its shape at 16px with no colour to carry the detail.
 */
object PlumeMark {

    /** Both paths in the artwork's own 108×108 coordinate space. */
    const val VIEWPORT: Float = 108f

    private const val VANE =
        "M32,76 C32,54 44,36 74,28 C70,54 58,70 38,76 Z " +
            "M34.99,76.84 L73.99,30.84 L72.01,29.16 L33.01,75.16 Z"

    private const val NIB = "M27.5,84.5 L34.5,76.2 L36.5,78.9 Z"

    /**
     * One filled path, ready to draw. Built fresh rather than cached: [Path] is mutable, and a
     * shared instance would be drawn from several threads on the desktop.
     */
    fun path(): Path {
        val vane = PathParser().parsePathString(VANE).toPath().apply {
            fillType = PathFillType.EvenOdd
        }
        val nib = PathParser().parsePathString(NIB).toPath()
        return Path().apply {
            addPath(vane)
            addPath(nib)
        }
    }
}
