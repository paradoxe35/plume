package me.pngwasi.plume.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import javax.imageio.ImageIO

/**
 * The window and taskbar icon.
 *
 * jpackage's `iconFile` only reaches the launcher and the desktop entry — the running window keeps
 * whatever Compose defaults to, which is Compose's own logo. That is what shows in the window list
 * and in the alt-tab switcher, so it has to be set separately.
 *
 * Loaded through ImageIO rather than `painterResource`, which is deprecated in favour of the
 * Compose resources library; Plume does not use that library, and reading one PNG off the
 * classpath does not justify adopting it.
 */
@Composable
fun rememberWindowIcon(): Painter? = remember {
    runCatching {
        val stream = PlumeWindowIcon::class.java.getResourceAsStream("/plume.png")
            ?: return@runCatching null
        val image = stream.use { ImageIO.read(it) } ?: return@runCatching null
        BitmapPainter(image.toComposeImageBitmap())
    }.getOrNull()
}

/** Anchor for the classpath lookup above. */
private object PlumeWindowIcon
