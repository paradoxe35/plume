package me.pngwasi.plume.process

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

private val SheetShape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)

private const val ScrimAlpha = 0.45f
private const val ScrimMillis = 200
private const val SlideMillis = 260

/**
 * The container every Plume overlay uses: a dimmed scrim over the host app with a card anchored to
 * the bottom, within thumb reach and clear of the text the user just selected.
 *
 * Scrim and sheet are driven by one flag so they arrive as a single motion. Animating only the
 * sheet — as an earlier version did — slams the scrim to full opacity on the first frame, which
 * reads as a black flash over the app the user is still looking at.
 *
 * Tapping the scrim cancels — the user is mid-task in another app, so leaving is always one tap away.
 */
@Composable
fun OverlaySheet(
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    val scrimInteraction = remember { MutableInteractionSource() }
    val sheetInteraction = remember { MutableInteractionSource() }

    // Both animations need a false→true edge: Compose does not animate a value that already equals
    // its target on first composition, which is why this starts false and is flipped immediately.
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }

    val scrimAlpha by animateFloatAsState(
        targetValue = if (shown) ScrimAlpha else 0f,
        animationSpec = tween(ScrimMillis),
        label = "scrim",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = scrimAlpha))
            .clickable(
                interactionSource = scrimInteraction,
                indication = null,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.BottomCenter,
    ) {
        AnimatedVisibility(
            visible = shown,
            enter = fadeIn(tween(ScrimMillis)) + slideInVertically(
                animationSpec = tween(SlideMillis, easing = FastOutSlowInEasing),
                initialOffsetY = { it },
            ),
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    // Swallows taps so they never reach the dismiss handler on the scrim behind.
                    .clickable(interactionSource = sheetInteraction, indication = null, onClick = {})
                    .navigationBarsPadding()
                    .imePadding(),
                shape = SheetShape,
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 16.dp,
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Grabber()
                    content()
                }
            }
        }
    }
}

@Composable
private fun Grabber() {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(width = 36.dp, height = 4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
    }
}
