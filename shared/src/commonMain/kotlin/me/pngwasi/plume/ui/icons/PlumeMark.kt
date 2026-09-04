package me.pngwasi.plume.ui.icons

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * Plume's quill mark, as a single-colour silhouette.
 *
 * This is the themed-icon artwork from the Android launcher rather than the colour one: the colour
 * version's four overlapping shapes flatten into a blob the moment they share a tint, which is
 * exactly what a tray does. The vane is one `evenOdd` path with the shaft punched out as a hole, so
 * the quill keeps its shape at 16px with no colour to carry the detail.
 *
 * The artwork was drawn for Android's adaptive icon, which reserves a wide margin for the launcher
 * to mask — it covers under half of its 108dp canvas. A tray icon has no such mask, so drawing it
 * on the original canvas left the quill small and weak in a sea of nothing. The mark is fitted to
 * its own bounds here instead.
 */
object PlumeMark {

    private const val VANE =
        "M32,76 C32,54 44,36 74,28 C70,54 58,70 38,76 Z " +
            "M34.99,76.84 L73.99,30.84 L72.01,29.16 L33.01,75.16 Z"

    private const val NIB = "M27.5,84.5 L34.5,76.2 L36.5,78.9 Z"

    // Measured from the paths above rather than guessed; see PlumeMarkTest, which fails if the
    // artwork is edited and these drift.
    private const val LEFT = 27.5f
    private const val TOP = 28.0f
    private const val RIGHT = 74.0f
    private const val BOTTOM = 84.5f

    /** Breathing room, so the quill does not touch the edge of a tray cell. */
    private const val MARGIN = 0.08f

    private const val WIDTH = RIGHT - LEFT
    private const val HEIGHT = BOTTOM - TOP

    /** A square canvas fitted to the artwork, which is what callers scale against. */
    const val VIEWPORT: Float = HEIGHT / (1f - 2f * MARGIN)

    private const val OFFSET_X = VIEWPORT / 2f - (LEFT + WIDTH / 2f)
    private const val OFFSET_Y = VIEWPORT / 2f - (TOP + HEIGHT / 2f)

    /**
     * One filled path, ready to draw. Built fresh rather than cached: [Path] is mutable, and a
     * shared instance would be drawn from several threads on the desktop.
     */
    fun path(): Path = Path().apply {
        fillType = PathFillType.EvenOdd
        addPath(PathParser().parsePathString(VANE).toPath(), Offset(OFFSET_X, OFFSET_Y))
        addPath(PathParser().parsePathString(NIB).toPath(), Offset(OFFSET_X, OFFSET_Y))
    }

    /**
     * The same mark as an [ImageVector].
     *
     * The tray takes this rather than a painter, so it can tint the mark to suit the panel's own
     * background — which is routinely the opposite of the application's theme.
     */
    fun vector(): ImageVector = ImageVector.Builder(
        // 24dp intrinsic against its own viewport, the shape every other icon has. The viewport is
        // just the coordinate space the artwork is drawn in; handing over a 67dp intrinsic made
        // callers size it as though it were a picture.
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = VIEWPORT,
        viewportHeight = VIEWPORT,
    ).apply {
        addGroup(translationX = OFFSET_X, translationY = OFFSET_Y)
        addPath(
            pathData = PathParser().parsePathString(VANE).toNodes(),
            pathFillType = PathFillType.EvenOdd,
            fill = SolidColor(Color.Black),
        )
        addPath(
            pathData = PathParser().parsePathString(NIB).toNodes(),
            fill = SolidColor(Color.Black),
        )
        clearGroup()
    }.build()
}
