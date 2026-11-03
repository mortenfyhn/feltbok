package io.github.mortenfyhn.feltbok

import android.view.MotionEvent
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView

/**
 * A fast pinch must zoom by the full span ratio (#61). osmdroid's own multi-touch controller
 * drops any move where the fingers jump more than 30 px or the span more than 80 px between
 * events, so a quick pinch barely zoomed. Synthetic two-finger events with big jumps reproduce
 * that without a hand on the screen.
 */
@RunWith(AndroidJUnit4::class)
class PinchZoomTest {
    private val instr = InstrumentationRegistry.getInstrumentation()

    @Test
    fun fastPinchZoomsByTheSpanRatio() {
        // The span grows 200 -> 260 -> 460 -> 660 -> 800: two zoom levels.
        assertEquals(2.0, pinchZoomDelta { PickerMapView(instr.targetContext) }, 0.01)
    }

    /** Lays the map out at 1000x1000, zoom 10, pinches p1 away from p0 in 200 px jumps, and
     *  returns the zoom change. Events are 40 ms apart: a real fast pinch, not a glitch. */
    private fun pinchZoomDelta(build: () -> MapView): Double {
        Configuration.getInstance().userAgentValue = instr.targetContext.packageName
        var delta = 0.0
        instr.runOnMainSync {   // views (and their gesture detectors) need the main Looper
            val map = build()
            map.setTileSource(TileSourceFactory.MAPNIK)
            map.controller.setZoom(10.0)
            map.controller.setCenter(GeoPoint(63.43, 10.39))
            map.measure(
                View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
            )
            map.layout(0, 0, 1000, 1000)
            val before = map.zoomLevelDouble
            val t0 = 1_000L
            var t = t0
            fun send(action: Int, vararg xs: Float) {
                val props = Array(xs.size) { MotionEvent.PointerProperties().apply { id = it; toolType = MotionEvent.TOOL_TYPE_FINGER } }
                val coords = Array(xs.size) { MotionEvent.PointerCoords().apply { x = xs[it]; y = 500f; pressure = 1f; size = 1f } }
                val e = MotionEvent.obtain(t0, t, action, xs.size, props, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
                map.dispatchTouchEvent(e)
                e.recycle()
                t += 40
            }
            val second = 1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT
            send(MotionEvent.ACTION_DOWN, 100f)
            send(MotionEvent.ACTION_POINTER_DOWN or second, 100f, 300f)
            for (x in floatArrayOf(360f, 560f, 760f, 900f)) send(MotionEvent.ACTION_MOVE, 100f, x)
            send(MotionEvent.ACTION_POINTER_UP or second, 100f, 900f)
            send(MotionEvent.ACTION_UP, 100f)
            delta = map.zoomLevelDouble - before
        }
        return delta
    }
}
