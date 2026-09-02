package me.pngwasi.plume.process

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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

private val SheetShape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)

/**
 * The container every Plume overlay uses: a dimmed scrim over the host app with a card anchored to
 * the bottom, within thumb reach and clear of the text the user just selected.
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f))
            .clickable(
                interactionSource = scrimInteraction,
                indication = null,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.BottomCenter,
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
