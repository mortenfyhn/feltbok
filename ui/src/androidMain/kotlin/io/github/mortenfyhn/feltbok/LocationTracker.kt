package io.github.mortenfyhn.feltbok

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Streams live GPS fixes while [start]ed. We keep updates running the whole
 * time the screen is visible (not just at record time) so the receiver stays
 * warm: when you switch back from another app the position reacquires within a
 * second or two, and the UI can show it settling instead of silently stamping
 * a stale coordinate.
 */
class LocationTracker(context: Context) : LocationSource {
    private val manager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val _fix = MutableStateFlow<GpsFix?>(null)
    override val fix: StateFlow<GpsFix?> = _fix

    private var best: Location? = null

    private val listener = LocationListener { loc ->
        if (isBetter(loc, best)) {
            best = loc
            _fix.value = loc.toFix()
        }
    }

    @SuppressLint("MissingPermission") // caller checks ACCESS_FINE_LOCATION first
    override fun start() {
        best = null
        // Network gives a fast, coarse fix (works indoors); GPS refines it to a
        // precise one outdoors. We request both and keep whichever is best.
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            if (!manager.isProviderEnabled(provider)) continue
            manager.getLastKnownLocation(provider)?.let { listener.onLocationChanged(it) }
            manager.requestLocationUpdates(provider, 1000L, 0f, listener)
        }
    }

    override fun stop() {
        manager.removeUpdates(listener)
    }

    private fun isBetter(candidate: Location, current: Location?): Boolean {
        if (current == null) return true
        return preferNewFix(
            candAccuracyM = if (candidate.hasAccuracy()) candidate.accuracy else Float.NaN,
            candNanos = candidate.elapsedRealtimeNanos,
            curAccuracyM = if (current.hasAccuracy()) current.accuracy else Float.NaN,
            curNanos = current.elapsedRealtimeNanos,
        )
    }

    private fun Location.toFix() = GpsFix(
        lat = latitude,
        lon = longitude,
        // hasAccuracy() is false on some devices; surface that as "unknown".
        accuracyM = if (hasAccuracy()) accuracy else Float.NaN,
        elapsedRealtimeNanos = elapsedRealtimeNanos,
    )
}
