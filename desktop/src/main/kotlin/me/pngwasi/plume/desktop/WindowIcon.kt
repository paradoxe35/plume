package me.pngwasi.plume.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection

/**
 * The window and taskbar icon, drawn rather than loaded.
 *
 * jpackage's `iconFile` only reaches the launcher and the desktop entry — the running window keeps
 * whatever Compose defaults to, which is Compose's own logo, and that is what the dock and the
 * window switcher show.
 *
 * It is drawn from path data instead of read from a resource because ProGuard dropped the PNG from
 * the minified build: the icon worked when run from Gradle and silently vanished in the installed
 * package, which is the worst way for it to fail. Nothing here can be shrunk away.
 *
 * The artwork is the Android launcher's, so the two stay in step.
 */
@Composable
fun rememberWindowIcon(): Painter = remember { BitmapPainter(renderAppIcon(256)) }

/** Background, then the quill: vane, shoulder, shaft, nib — in the order they overlap. */
private val LAYERS = listOf(
    Color(0xFFF7F6F3) to "M32,76 C32,54 44,36 74,28 C70,54 58,70 38,76 Z",
    Color(0xFF7FD1C4) to "M32,76 C40,60 54,44 74,28 C58,50 44,64 38,76 Z",
    Color(0xFFF7F6F3) to "M31,79 L74,28 L76,30 L34,80 Z",
    Color(0xFFE8A87C) to "M28,84 L34,77 L36,79 Z",
)

private const val VIEWPORT = 108f
private val BACKGROUND = Color(0xFF1E4D4A)

/** The artwork's own bounds inside the 108dp adaptive canvas, which reserves a wide margin. */
private const val CONTENT_LEFT = 28f
private const val CONTENT_TOP = 28f
private const val CONTENT_RIGHT = 76f
private const val CONTENT_BOTTOM = 84f
private const val MARGIN = 0.14f

internal fun renderAppIcon(size: Int): ImageBitmap {
    val bitmap = ImageBitmap(size, size)
    val paths = LAYERS.map { (colour, data) -> colour to PathParser().parsePathString(data).toPath() }

    // Fit the artwork rather than its adaptive canvas: a window icon has no launcher mask, so
    // drawing it at the original scale would leave the quill small in a field of teal.
    val width = CONTENT_RIGHT - CONTENT_LEFT
    val height = CONTENT_BOTTOM - CONTENT_TOP
    val fitted = maxOf(width, height) / (1f - 2f * MARGIN)
    val factor = size / fitted
    val offsetX = fitted / 2f - (CONTENT_LEFT + width / 2f)
    val offsetY = fitted / 2f - (CONTENT_TOP + height / 2f)

    CanvasDrawScope().draw(
        Density(1f),
        LayoutDirection.Ltr,
        Canvas(bitmap),
        Size(size.toFloat(), size.toFloat()),
    ) {
        drawRoundRect(
            color = BACKGROUND,
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(size * 0.18f),
        )
        scale(scaleX = factor, scaleY = factor, pivot = Offset.Zero) {
            paths.forEach { (colour, path) ->
                drawPath(Path().apply { addPath(path, Offset(offsetX, offsetY)) }, colour)
            }
        }
    }
    return bitmap
}
