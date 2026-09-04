package me.pngwasi.plume.desktop

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import me.pngwasi.plume.ui.components.LocalScrollAffordance

/**
 * Shows that a screen continues below the fold.
 *
 * Two affordances, because they answer different questions and desktops disagree about which they
 * provide. The scrollbar says how far down you are, and Compose Desktop draws it only while there
 * is something to scroll. The fade says there is more, which is the part a scrollbar cannot make
 * obvious on macOS, where scrollbars stay hidden until you touch the trackpad.
 *
 * Both come from the screen's own scroll state, published through [LocalScrollAffordance], and both
 * go once the content ends — an edge with nothing behind it is worse than no edge.
 */
@Composable
fun BoxScope.ScrollAffordance() {
    val state by LocalScrollAffordance.current

    // Read as state, so reaching the end fades the edge out rather than leaving it there.
    val more = state?.canScrollForward == true
    val fade by animateFloatAsState(if (more) 1f else 0f, tween(160), label = "affordance")

    if (fade > 0f) {
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(28.dp)
                .alpha(fade)
                .background(
                    // From the window's own background rather than a fixed black or white, so it
                    // reads the same in either theme.
                    Brush.verticalGradient(
                        listOf(Color.Transparent, MaterialTheme.colorScheme.background),
                    ),
                ),
        )
    }

    when (val scrollable = state) {
        is ScrollState -> VerticalScrollbar(
            adapter = rememberScrollbarAdapter(scrollable),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 2.dp),
        )

        is LazyListState -> VerticalScrollbar(
            adapter = rememberScrollbarAdapter(scrollable),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 2.dp),
        )

        else -> Unit
    }
}
