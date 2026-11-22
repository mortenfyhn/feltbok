package io.github.mortenfyhn.feltbok

import android.app.Application
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.osmdroid.util.GeoPoint
import kotlin.math.abs

/**
 * GPS follow mode in the locality picker: the map follows new fixes until you pan, a pinch keeps
 * it following, and the GPS button turns it back on. Fixes come from a fake [LocationSource].
 */
@RunWith(AndroidJUnit4::class)
class FollowGpsTest {
    @get:Rule(order = 0)
    val clearData = ClearAppDataRule()

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val fixes = MutableStateFlow<GpsFix?>(null)
    private val instr = InstrumentationRegistry.getInstrumentation()

    @Test
    fun followsUntilPannedAndAgainAfterTheButton() {
        val map = openPicker()

        // Opened on your position, so it already follows.
        fixFor(63.44, 10.41)
        waitUntilCentredOn(map, 63.44, 10.41)

        // A pinch zooms around the fix, not between the fingers, and keeps following.
        pinch(map)
        rule.runOnUiThread { assertTrue("${map.mapCenter} z${map.zoomLevelDouble} ${map.width}x${map.height}", isCentredOn(map, 63.44, 10.41)) }
        fixFor(63.45, 10.42)
        waitUntilCentredOn(map, 63.45, 10.42)

        // A pan ends it: the next fix doesn't move the map.
        drag(map)
        fixFor(63.46, 10.43)
        rule.waitForIdle()
        SystemClock.sleep(1000)   // longer than the pan animation
        rule.runOnUiThread { assertFalse(isCentredOn(map, 63.46, 10.43)) }

        // The GPS button turns it back on.
        rule.onNodeWithContentDescription(Strings.Picker.followGps).performClick()
        waitUntilCentredOn(map, 63.46, 10.43)
        rule.waitUntil { rule.onAllNodesWithContentDescription(Strings.Picker.followGps).fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun newSpotPanelRecentresOnceThenStopsFollowing() {
        val map = openPicker()
        fixFor(63.44, 10.41)
        waitUntilCentredOn(map, 63.44, 10.41)

        // Opening the panel re-centres above it once, then stops following, so the GPS button shows.
        rule.onNodeWithText(Strings.Picker.newButton).performClick()
        rule.waitUntil { rule.onAllNodesWithContentDescription(Strings.Picker.followGps).fetchSemanticsNodes().isNotEmpty() }
        rule.waitUntil(5_000) { var idle = false; rule.runOnUiThread { idle = !map.isAnimating }; idle }
        var before = GeoPoint(0.0, 0.0)
        rule.runOnUiThread { before = GeoPoint(map.mapCenter) }

        // A new fix leaves the spot where it is.
        fixFor(63.45, 10.42)
        rule.waitForIdle()
        SystemClock.sleep(1000)   // longer than the pan animation
        rule.runOnUiThread { assertTrue("${map.mapCenter} != $before", isCentredOn(map, before.latitude, before.longitude)) }
    }

    private fun openPicker(): PickerMapView {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val platform = object : Platform by AndroidPlatform(app) {
            override val location = object : LocationSource {
                override val fix = fixes
                override fun start() = Unit
                override fun stop() = Unit
            }
        }
        fixFor(63.43, 10.39)
        val vm = MainViewModel(platform)
        rule.waitUntil { vm.fix != null }
        rule.runOnUiThread { vm.openLocalityPicker() }
        rule.setContent { LocalityScreen(vm) }
        return waitForMap()
    }

    private fun fixFor(lat: Double, lon: Double) {
        fixes.value = GpsFix(lat, lon, accuracyM = 5f, elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos())
    }

    private fun waitForMap(): PickerMapView {
        var map: PickerMapView? = null
        rule.waitUntil(5_000) {
            rule.runOnUiThread {
                map = findPickerMap(rule.activity.window.decorView)?.takeIf { it.width > 0 }
            }
            map != null
        }
        return map!!
    }

    private fun isCentredOn(map: PickerMapView, lat: Double, lon: Double) =
        abs(map.mapCenter.latitude - lat) < 1e-4 && abs(map.mapCenter.longitude - lon) < 1e-4

    private fun waitUntilCentredOn(map: PickerMapView, lat: Double, lon: Double) {
        rule.waitUntil(5_000) {
            var ok = false
            rule.runOnUiThread { ok = !map.isAnimating && isCentredOn(map, lat, lon) }
            ok
        }
    }

    /** One finger, 300 px straight down. */
    private fun drag(map: PickerMapView) = touch(map) { send ->
        send(MotionEvent.ACTION_DOWN, floatArrayOf(500f), 500f)
        for (y in 550..800 step 50) send(MotionEvent.ACTION_MOVE, floatArrayOf(500f), y.toFloat())
        send(MotionEvent.ACTION_UP, floatArrayOf(500f), 800f)
    }

    /** Two fingers spreading apart off-centre, so a pinch around the fingers would shift the map. */
    private fun pinch(map: PickerMapView) = touch(map) { send ->
        val second = 1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT
        send(MotionEvent.ACTION_DOWN, floatArrayOf(100f), 200f)
        send(MotionEvent.ACTION_POINTER_DOWN or second, floatArrayOf(100f, 200f), 200f)
        for (x in 250..450 step 50) send(MotionEvent.ACTION_MOVE, floatArrayOf(100f, x.toFloat()), 200f)
        send(MotionEvent.ACTION_POINTER_UP or second, floatArrayOf(100f, 450f), 200f)
        send(MotionEvent.ACTION_UP, floatArrayOf(100f), 200f)
    }

    private fun touch(map: PickerMapView, gesture: ((Int, FloatArray, Float) -> Unit) -> Unit) {
        instr.runOnMainSync {
            val t0 = SystemClock.uptimeMillis()
            var t = t0
            gesture { action, xs, y ->
                val props = Array(xs.size) { MotionEvent.PointerProperties().apply { id = it; toolType = MotionEvent.TOOL_TYPE_FINGER } }
                val coords = Array(xs.size) { MotionEvent.PointerCoords().apply { x = xs[it]; this.y = y; pressure = 1f; size = 1f } }
                val e = MotionEvent.obtain(t0, t, action, xs.size, props, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
                map.dispatchTouchEvent(e)
                e.recycle()
                t += 40
            }
        }
    }

    private fun findPickerMap(v: View): PickerMapView? = when (v) {
        is PickerMapView -> v
        is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { findPickerMap(v.getChildAt(it)) }
        else -> null
    }
}
