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
