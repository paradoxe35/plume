package me.pngwasi.plume.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.FrameWindowScope
import java.awt.GraphicsDevice
import java.awt.GraphicsEnvironment

/**
 * Whether this machine can draw a window with rounded corners.
 *
 * Rounding means drawing the window ourselves, so the corners outside the radius have to be
 * transparent — and per-pixel transparency needs a compositor. Without one AWT throws rather than
 * degrading, so the system-decorated window stays.
 */
val roundedWindowSupported: Boolean by lazy {
    runCatching {
        GraphicsEnvironment.getLocalGraphicsEnvironment()
            .defaultScreenDevice
            .isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSPARENT)
    }.getOrDefault(false)
}

/** What GNOME and Windows draw on the top corners, so all four match. */
private val CORNER = 12.dp

/** The app bar's height: the strip the window is dragged by. */
private val TITLE_STRIP = 56.dp

/**
 * The window's own frame, for when the system is not drawing one.
 *
 * Losing the title bar loses what it did — dragging, and a way to close — so both are put back.
 * Resizing is not, because the window is a fixed size.
 */
@Composable
fun FrameWindowScope.RoundedWindowFrame(onClose: () -> Unit, content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        shape = RoundedCornerShape(CORNER),
        color = MaterialTheme.colorScheme.background,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            content()

            // Only as tall as the app bar, so everything below still takes clicks, and it starts
            // past the back button that shares the strip.
            Row(modifier = Modifier.fillMaxWidth().height(TITLE_STRIP)) {
                Box(modifier = Modifier.size(TITLE_STRIP))
                WindowDraggableArea(modifier = Modifier.weight(1f).fillMaxSize()) {}
                IconButton(onClick = onClose, modifier = Modifier.padding(end = 4.dp)) {
                    Icon(
                        imageVector = CloseIcon,
                        contentDescription = "Close",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private val CloseIcon: ImageVector = ImageVector.Builder(
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    addPath(
        pathData = PathParser().parsePathString("M6,6 L18,18 M18,6 L6,18").toNodes(),
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 2f,
        strokeLineCap = StrokeCap.Round,
    )
}.build()
