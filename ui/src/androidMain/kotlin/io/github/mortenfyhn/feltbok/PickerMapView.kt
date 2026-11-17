package io.github.mortenfyhn.feltbok

import android.content.Context
import android.graphics.PointF
import android.view.MotionEvent
import android.view.ViewConfiguration
import org.osmdroid.views.MapView
import kotlin.math.hypot
import kotlin.math.max

/**
 * A MapView that tracks the pinch itself instead of using osmdroid's bundled MultiTouchController
 * (#61). That controller treats a between-events finger jump of more than 30 px, or a span change
 * of more than 80 px, as bad input: it re-anchors the gesture (dropping that move's zoom) and
 * ignores the next 20 ms. A fast pinch trips that on every event, so the map zooms far less than
 * the fingers moved. Android's ScaleGestureDetector is no good either: it won't scale below a
 * 27 mm span, which cuts a pinch-to-zoom-out short. So: span and focus straight from the event,
 * fed into osmdroid's own mid-gesture scale hooks, so panning around the focus point and the
 * hand-off to the rest of osmdroid are unchanged.
 */
class PickerMapView(ctx: Context) : MapView(ctx) {
    var pinching = false
        private set
    private var startSpan = 1f

    /** Called on each move of a one-finger drag past the touch slop. The scroll listener can't tell
     *  that apart from our own animateTo, so GPS follow mode ends here instead. */
    var onUserPan: () -> Unit = {}

    /** When set, a pinch zooms around this view point instead of between the fingers, so GPS follow
     *  mode keeps the fix in place. */
    var pinchPivot: (() -> PointF)? = null

    private val touchSlop = ViewConfiguration.get(ctx).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f

    init {
        setMultiTouchControls(false)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { end(); downX = event.x; downY = event.y }
            MotionEvent.ACTION_POINTER_DOWN -> { end(); begin(event) }
            MotionEvent.ACTION_POINTER_UP -> {
                end()   // after a 3->2 finger change the next MOVE re-begins
                // Back to one finger: measure a pan from where that finger is now, not from DOWN.
                if (event.pointerCount == 2) (1 - event.actionIndex).let { downX = event.getX(it); downY = event.getY(it) }
            }
            MotionEvent.ACTION_MOVE ->
                if (event.pointerCount >= 2) { if (pinching) move(event) else begin(event) } else if (hypot(event.x - downX, event.y - downY) > touchSlop) onUserPan()
            else -> end()   // UP, CANCEL
        }
        // With a pivot, a pinch must not pan: keep its moves from super's gesture detector, which
        // scrolls by the fingers' midpoint.
        if (pinching && pinchPivot != null && event.actionMasked == MotionEvent.ACTION_MOVE) return true
        // Super's own gesture detector still sees every event: single-finger pan, fling and taps.
        // Its scroll during the pinch is overridden by the focus-point pin above.
        val handled = super.dispatchTouchEvent(event)
        // osmdroid starts a fling without asking for a redraw, and its computeScroll only steps the
        // fling when something else redraws. Until then the map stood still, and the first step then
        // jumped most of the fling at once.
        if (event.actionMasked == MotionEvent.ACTION_UP && mIsFlinging) postInvalidateOnAnimation()
        return handled
    }

    private fun begin(e: MotionEvent) {
        if (isAnimating) return          // a zoom animation is using the same scale state
        startSpan = span(e)
        val pivot = pinchPivot?.invoke()
        setMultiTouchScaleInitPoint(pivot?.x ?: focusX(e), pivot?.y ?: focusY(e))
        startAnimation()                 // records the zoom the scale factor is relative to
        pinching = true
    }

    private fun move(e: MotionEvent) {
        val pivot = pinchPivot?.invoke()
        setMultiTouchScaleCurrentPoint(pivot?.x ?: focusX(e), pivot?.y ?: focusY(e))
        setMultiTouchScale(span(e) / startSpan)   // calls setZoomLevel: redraw + layout
    }

    private fun end() {
        if (!pinching) return
        pinching = false
        resetMultiTouchScale()
    }

    private fun focusX(e: MotionEvent) = (e.getX(0) + e.getX(1)) / 2
    private fun focusY(e: MotionEvent) = (e.getY(0) + e.getY(1)) / 2
    private fun span(e: MotionEvent) = max(hypot(e.getX(1) - e.getX(0), e.getY(1) - e.getY(0)), 30f)   // floor like osmdroid's, so touching fingers can't zoom to -inf
}
