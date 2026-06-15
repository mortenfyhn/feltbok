package io.github.mortenfyhn.feltbok

import android.Manifest
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView

/**
 * Instrumented smoke tests on the minified releaseTest variant for the two paths most exposed
 * to R8 tree-shaking: app startup (Compose + ViewModel + osmdroid init wired together) and
 * osmdroid itself, which loads tile sources / the config provider reflectively — exactly the
 * map-strip risk #113/#117 call out. A `-keep` rule lapse would crash here, not in a shipped APK.
 */
@RunWith(AndroidJUnit4::class)
class MapAndLaunchSmokeTest {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx = instr.targetContext

    // Granted up front so MainActivity doesn't pop the runtime-permission dialog on launch, which
    // (as a full-screen activity on some OEMs) backgrounds MainActivity and stalls the RESUMED wait.
    @get:Rule
    val grantLocation: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.ACCESS_FINE_LOCATION)

    /** Launch the real activity and confirm it reaches RESUMED without crashing — this runs the
     *  whole startup path (configureOsmdroid + setContent { App(vm) } + the ViewModel's asset
     *  loads) through full R8 shrink+obfuscate, so a strip regression there surfaces here. */
    @Test
    fun appLaunches() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.onActivity { assertTrue(!it.isFinishing) }
        }
    }

    /** Build a MapView with the Mapnik tile source and round-trip a coordinate through the
     *  Projection (geo → pixel → geo). This forces osmdroid's reflective tile-source / config
     *  loading and the pixel↔geo Projection — the integration unit tests can't reach. If R8
     *  stripped an osmdroid class the keep rules miss, this throws instead of round-tripping.
     *  osmdroid is set up inline (not via the app's configureOsmdroid) so the map module keeps
     *  its full R8 treatment rather than being pinned by a keep rule. */
    @Test
    fun osmdroidProjectionRoundTrips() {
        Configuration.getInstance().userAgentValue = ctx.packageName // osmdroid refuses to load tiles without it
        val target = GeoPoint(63.43, 10.39) // Trondheim
        lateinit var back: GeoPoint
        instr.runOnMainSync {
            val map = MapView(ctx).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                controller.setZoom(16.0)
                controller.setCenter(target)
            }
            // Give it a viewport so the Projection has dimensions to map against.
            map.measure(
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
            )
            map.layout(0, 0, 800, 800)
            val px = map.projection.toPixels(target, null)
            back = map.projection.fromPixels(px.x, px.y) as GeoPoint
        }
        // Round-trip is pixel-quantised, so allow a tile-resolution tolerance.
        assertEquals(target.latitude, back.latitude, 0.001)
        assertEquals(target.longitude, back.longitude, 0.001)
    }
}
