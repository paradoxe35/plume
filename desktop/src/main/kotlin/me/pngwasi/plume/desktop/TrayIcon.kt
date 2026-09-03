package me.pngwasi.plume.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.painter.Painter
import me.pngwasi.plume.ui.icons.PlumeMark

/**
 * The tray icon: Plume's quill, drawn straight onto the canvas.
 *
 * It deliberately does not go through `rememberVectorPainter`. A `VectorPainter` renders from an
 * internal composition, and the tray does not draw inside a composition frame — it asks the painter
 * for an AWT image once, up front, which produced a blank white square. Drawing the path directly
 * has no such dependency.
 *
 * The icon carries state: a reasoning model can take a minute, and a shortcut that appears to do
 * nothing for a minute reads as broken. While the work happens inside another application's window,
 * this is the only signal the user gets.
 */
@Composable
fun rememberTrayIcon(dark: Boolean, busy: Boolean): Painter {
    val tint = when {
        busy -> Color(0xFF7FD1C4)
        // Trays invert with the desktop theme, so the mark has to go the other way.
        dark -> Color(0xFFE6EBE9)
        else -> Color(0xFF1E4D4A)
    }
    return remember(tint) { PlumeMarkPainter(tint) }
}

private class PlumeMarkPainter(private val tint: Color) : Painter() {

    private val path = PlumeMark.path()

    // Trays ask for a range of sizes depending on the desktop and its scaling, so this is only a
    // starting point; onDraw scales to whatever it is actually given.
    override val intrinsicSize: Size = Size(PlumeMark.VIEWPORT, PlumeMark.VIEWPORT)

    override fun DrawScope.onDraw() {
        val factor = size.minDimension / PlumeMark.VIEWPORT
        scale(scaleX = factor, scaleY = factor, pivot = androidx.compose.ui.geometry.Offset.Zero) {
            drawPath(path, tint)
        }
    }
}
