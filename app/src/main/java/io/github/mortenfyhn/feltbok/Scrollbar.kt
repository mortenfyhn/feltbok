package io.github.mortenfyhn.feltbok

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/** A thin scroll-position indicator for a LazyColumn (#153): the day-grouped lists otherwise give
 *  no sense of where you are in a long history. An indicator only, not a drag handle - it fades in
 *  while scrolling and back out after a beat of rest. Position/size are approximated by item index
 *  (rows vary a little in height - day headers vs notes), which is plenty close for an indicator. */
@Composable
fun Modifier.scrollIndicator(state: LazyListState): Modifier {
    // The fade is driven from a coroutine, not animateFloatAsState. This composable returns a value,
    // so Compose inlines it into the caller's recompose scope; reading state.isScrollInProgress
    // during composition would therefore subscribe the whole list screen to it. The drag-select
    // auto-scroll scrolls with a discrete scrollBy() per frame, so isScrollInProgress flips every
    // frame and the whole screen recomposed each frame. Observing it in a flow keeps the screen out
    // of that loop, and the draw reads only the animation value.
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(state) {
        snapshotFlow { state.isScrollInProgress }.collectLatest { scrolling ->
            if (scrolling) {
                alpha.animateTo(1f, tween(durationMillis = 100))
            } else {
                delay(700)  // a beat of rest before fading out, so a pause doesn't blink it away
                alpha.animateTo(0f, tween(durationMillis = 500))
            }
        }
    }
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    return drawWithContent {
        drawContent()
        val a = alpha.value
        if (a == 0f) return@drawWithContent
        val info = state.layoutInfo
        val total = info.totalItemsCount
        val visible = info.visibleItemsInfo
        // Everything on screen at once = nothing to indicate.
        if (visible.isEmpty() || visible.size >= total) return@drawWithContent
        val thumbH = (size.height * visible.size / total).coerceAtLeast(24.dp.toPx())
        // How far down the list we are: whole items scrolled past, plus the scrolled-off
        // fraction of the first visible one so the thumb moves smoothly, not in item steps.
        val first = visible.first()
        val intoFirst = if (first.size > 0) -first.offset.toFloat() / first.size else 0f
        val progress = ((first.index + intoFirst) / (total - visible.size)).coerceIn(0f, 1f)
        val w = 3.dp.toPx()
        drawRoundRect(
            color = color,
            topLeft = Offset(size.width - w - 2.dp.toPx(), (size.height - thumbH) * progress),
            size = Size(w, thumbH),
            cornerRadius = CornerRadius(w / 2),
            alpha = a * 0.6f,
        )
    }
}
