package com.ooustream.iptv.common.compose

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.ensureActive

/**
 * Keeps the row holding focus at the top of a Spotlight screen's row list. Keyed on the row, so
 * moving along a row doesn't restart it; retried because the focused card's own bring-into-view
 * scroll can interrupt it mid-way (fast presses left the previous row half on screen).
 */
@Composable
fun PinFocusedRow(state: LazyListState, row: Int) {
    LaunchedEffect(row) {
        if (row < 0) return@LaunchedEffect
        repeat(4) {
            awaitFrame()
            if (state.firstVisibleItemIndex == row && state.firstVisibleItemScrollOffset == 0) return@LaunchedEffect
            try {
                state.animateScrollToItem(row)
            } catch (e: CancellationException) {
                ensureActive() // our own cancellation ends here; an interrupted scroll retries
            }
        }
    }
}


/**
 * Turns off the TV default "scroll the focused item to the pivot" for the row LIST, so
 * [PinFocusedRow] is the only thing moving it vertically — otherwise every sideways move asked the
 * list to re-centre the card, the pin pulled it back, and the row above bounced in and out.
 * [content] gets the original spec to hand back to each horizontal row, which still needs it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PinnedRowList(content: @Composable (rowSpec: BringIntoViewSpec) -> Unit) {
    val rowSpec = LocalBringIntoViewSpec.current
    CompositionLocalProvider(LocalBringIntoViewSpec provides NoVerticalBringIntoView) { content(rowSpec) }
}

/** Gives a horizontal row back the default bring-into-view inside [PinnedRowList]. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WithRowScroll(rowSpec: BringIntoViewSpec, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalBringIntoViewSpec provides rowSpec, content = content)
}

@OptIn(ExperimentalFoundationApi::class)
private object NoVerticalBringIntoView : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = 0f
}
