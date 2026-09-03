package me.pngwasi.plume.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import me.pngwasi.plume.ui.icons.PlumeIcons

/**
 * The tray icon, drawn from the bundled vector rather than a bitmap so it stays sharp on HiDPI —
 * the usual complaint about Compose Desktop trays on Windows.
 *
 * It carries state on purpose: a reasoning model can take a minute, and a shortcut that appears to
 * do nothing for a minute reads as broken. While the work happens inside another application's
 * window, this is the only signal the user has.
 */
@Composable
fun rememberTrayIcon(dark: Boolean, busy: Boolean): Painter {
    val icon = if (busy) PlumeIcons.AutoFixHigh else PlumeIcons.Translate
    val painter = rememberVectorPainter(icon)
    val tint = when {
        busy -> Color(0xFF7FD1C4)
        // Trays invert with the desktop theme, so the icon has to go the other way.
        dark -> Color(0xFFE6EBE9)
        else -> Color(0xFF1E4D4A)
    }
    return remember(painter, tint) { TintedPainter(painter, tint) }
}

/** The bundled icons are black fills, the same as the Material ones; this recolours at draw time. */
private class TintedPainter(
    private val delegate: Painter,
    private val tint: Color,
) : Painter() {

    override val intrinsicSize: Size get() = delegate.intrinsicSize

    override fun DrawScope.onDraw() {
        with(delegate) { draw(size, colorFilter = ColorFilter.tint(tint)) }
    }
}
