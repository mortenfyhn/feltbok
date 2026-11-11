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

    private var best: GpsFix? = null

    private val listener = LocationListener { loc ->
        val f = loc.toFix()
        val cur = best
        if (cur == null || preferNewFix(f.accuracyM, f.elapsedRealtimeNanos, cur.accuracyM, cur.elapsedRealtimeNanos) && worthPublishing(f, cur)) {
            best = f
            _fix.value = f
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

    private fun Location.toFix() = GpsFix(
        lat = latitude,
        lon = longitude,
        // hasAccuracy() is false on some devices; surface that as "unknown".
        accuracyM = if (hasAccuracy()) accuracy else Float.NaN,
        elapsedRealtimeNanos = elapsedRealtimeNanos,
    )
}
