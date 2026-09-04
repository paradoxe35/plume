package me.pngwasi.plume.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf

/**
 * The scroll state of whichever screen is on show, so a platform can draw its own edge affordance.
 *
 * Every screen owns its scrolling, so there is nothing at the window level to ask. Screens publish
 * it here instead of the host reaching in.
 */
val LocalScrollAffordance = compositionLocalOf<MutableState<ScrollableState?>> {
    mutableStateOf(null)
}

/** [rememberScrollState], published to [LocalScrollAffordance] for as long as the screen is shown. */
@Composable
fun rememberTrackedScrollState(): ScrollState = rememberScrollState().also { track(it) }

/** [rememberLazyListState], published the same way. */
@Composable
fun rememberTrackedLazyListState(): LazyListState = rememberLazyListState().also { track(it) }

@Composable
private fun track(state: ScrollableState) {
    val slot = LocalScrollAffordance.current
    DisposableEffect(state, slot) {
        slot.value = state
        // Cleared on the way out, or a screen that does not scroll inherits the last one's
        // affordance and shows an edge with nothing behind it.
        onDispose { if (slot.value === state) slot.value = null }
    }
}
