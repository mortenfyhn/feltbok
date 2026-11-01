@file:OptIn(ExperimentalForeignApi::class)

package io.github.mortenfyhn.feltbok

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import platform.CoreLocation.CLLocation
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.kCLLocationAccuracyBest
import platform.Foundation.timeIntervalSince1970
import platform.darwin.NSObject
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

/** Live GPS fixes from CoreLocation while [start]ed (the Android version uses LocationManager). */
class LocationTracker : LocationSource {
    private val _fix = MutableStateFlow<GpsFix?>(null)
    override val fix: StateFlow<GpsFix?> = _fix

    private val delegate = object : NSObject(), CLLocationManagerDelegateProtocol {
        override fun locationManager(manager: CLLocationManager, didUpdateLocations: List<*>) {
            for (loc in didUpdateLocations.filterIsInstance<CLLocation>()) {
                val f = loc.toFix()
                val cur = _fix.value
                if (cur == null || preferNewFix(f.accuracyM, f.elapsedRealtimeNanos, cur.accuracyM, cur.elapsedRealtimeNanos) && worthPublishing(f, cur)) {
                    _fix.value = f
                }
            }
        }

        // The first start() asks for permission; updates begin once it's granted.
        override fun locationManagerDidChangeAuthorization(manager: CLLocationManager) {
            manager.startUpdatingLocation()
        }
    }

    private val manager = CLLocationManager().apply {
        this.delegate = this@LocationTracker.delegate
        desiredAccuracy = kCLLocationAccuracyBest
    }

    override fun start() {
        _fix.value = null
        manager.requestWhenInUseAuthorization()
        manager.startUpdatingLocation()
    }

    override fun stop() = manager.stopUpdatingLocation()

    private fun CLLocation.toFix() = coordinate.useContents {
        GpsFix(
            lat = latitude,
            lon = longitude,
            accuracyM = if (horizontalAccuracy >= 0) horizontalAccuracy.toFloat() else Float.NaN,
            elapsedRealtimeNanos = (timestamp.timeIntervalSince1970 * 1e9).toLong(),
        )
    }
}

/** Each published fix makes the view model rescan all ~80k localities for the nearest one (tens of
 *  ms on an old iPhone), and CoreLocation reports about once a second even when you stand still - a
 *  steady stutter. So publish a fix only when it says something new: you moved a few metres, the
 *  accuracy got clearly better, or the last one is getting old. */
private fun worthPublishing(f: GpsFix, cur: GpsFix): Boolean {
    val moved = metresApart(f.lat, f.lon, cur.lat, cur.lon) >= 5.0
    val sharper = !f.accuracyM.isNaN() && (cur.accuracyM.isNaN() || f.accuracyM < cur.accuracyM - 3f)
    val old = f.elapsedRealtimeNanos - cur.elapsedRealtimeNanos > 15_000_000_000L
    return moved || sharper || old
}

private fun metresApart(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val x = (lon2 - lon1) * PI / 180 * cos((lat1 + lat2) / 2 * PI / 180)
    val y = (lat2 - lat1) * PI / 180
    return sqrt(x * x + y * y) * 6_371_000.0
}
