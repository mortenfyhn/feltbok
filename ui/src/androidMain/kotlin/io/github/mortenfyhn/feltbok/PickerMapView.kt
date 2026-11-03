package io.github.mortenfyhn.feltbok

import android.content.Context
import android.view.MotionEvent
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
    private var pinching = false
    private var startSpan = 1f

    init {
        setMultiTouchControls(false)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> { end(); begin(event) }
            MotionEvent.ACTION_MOVE -> if (event.pointerCount >= 2) { if (pinching) move(event) else begin(event) }
            else -> end()   // DOWN, UP, POINTER_UP, CANCEL; after a 3->2 finger change the next MOVE re-begins
        }
        // Super's own gesture detector still sees every event: single-finger pan, fling and taps.
        // Its scroll during the pinch is overridden by the focus-point pin above.
        return super.dispatchTouchEvent(event)
    }

    private fun begin(e: MotionEvent) {
        if (isAnimating) return          // a zoom animation is using the same scale state
        startSpan = span(e)
        setMultiTouchScaleInitPoint(focusX(e), focusY(e))
        startAnimation()                 // records the zoom the scale factor is relative to
        pinching = true
    }

    private fun move(e: MotionEvent) {
        setMultiTouchScaleCurrentPoint(focusX(e), focusY(e))
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
