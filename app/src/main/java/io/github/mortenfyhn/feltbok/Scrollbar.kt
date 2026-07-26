package io.github.mortenfyhn.feltbok

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp

/** A thin scroll-position indicator for a LazyColumn (#153): the day-grouped lists otherwise give
 *  no sense of where you are in a long history. An indicator only, not a drag handle - it fades in
 *  while scrolling and back out after a beat of rest. Position/size are approximated by item index
 *  (rows vary a little in height - day headers vs notes), which is plenty close for an indicator. */
@Composable
fun Modifier.scrollIndicator(state: LazyListState): Modifier {
    val scrolling = state.isScrollInProgress
    // Snappy in so it tracks the finger; the fade-out waits so a pause doesn't blink it away.
    val alpha by animateFloatAsState(
        targetValue = if (scrolling) 1f else 0f,
        animationSpec = if (scrolling) tween(durationMillis = 100)
        else tween(durationMillis = 500, delayMillis = 700),
        label = "scrollIndicatorAlpha",
    )
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    return drawWithContent {
        drawContent()
        if (alpha == 0f) return@drawWithContent
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
            alpha = alpha * 0.6f,
        )
    }
}
