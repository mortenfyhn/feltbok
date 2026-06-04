package com.feltbok

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A GPS fix plus enough info for the UI to judge whether it has settled. */
data class GpsFix(
    val lat: Double,
    val lon: Double,
    val accuracyM: Float,
    val elapsedRealtimeNanos: Long,
) {
    /** Seconds since this fix was taken, so the UI can flag stale positions. */
    fun ageSeconds(): Long =
        (SystemClock.elapsedRealtimeNanos() - elapsedRealtimeNanos) / 1_000_000_000L
}

/**
 * Streams live GPS fixes while [start]ed. We keep updates running the whole
 * time the screen is visible (not just at record time) so the receiver stays
 * warm: when you switch back from another app the position reacquires within a
 * second or two, and the UI can show it settling instead of silently stamping
 * a stale coordinate.
 */
class LocationTracker(context: Context) {
    private val manager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val _fix = MutableStateFlow<GpsFix?>(null)
    val fix: StateFlow<GpsFix?> = _fix

    private var best: Location? = null

    private val listener = LocationListener { loc ->
        if (isBetter(loc, best)) {
            best = loc
            _fix.value = loc.toFix()
        }
    }

    @SuppressLint("MissingPermission") // caller checks ACCESS_FINE_LOCATION first
    fun start() {
        best = null
        // Network gives a fast, coarse fix (works indoors); GPS refines it to a
        // precise one outdoors. We request both and keep whichever is best.
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            if (!manager.isProviderEnabled(provider)) continue
            manager.getLastKnownLocation(provider)?.let { listener.onLocationChanged(it) }
            manager.requestLocationUpdates(provider, 1000L, 0f, listener)
        }
    }

    fun stop() {
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

/** ~60 s; past this the current fix is treated as stale enough to take whatever arrives next. */
private const val STALE_NANOS = 60_000_000_000L

/**
 * Should a new fix replace the current one? Pure (no Android types) so it's unit-testable;
 * accuracy is metres, [Float.NaN] when the provider didn't report it, and the nanos are
 * elapsed-realtime timestamps.
 *
 * Keep comparable-or-better accuracy so a precise fix tracks your movement, but reject a much
 * *worse* one (a coarse network/cell fix, or a wild GPS outlier) unless the current fix has
 * gone stale - otherwise an occasional bad fix yanks the position kilometres away (issue #32).
 */
fun preferNewFix(candAccuracyM: Float, candNanos: Long, curAccuracyM: Float, curNanos: Long): Boolean {
    val newerByNanos = candNanos - curNanos
    if (newerByNanos < 0) return false                       // older fix
    val veryStale = newerByNanos > STALE_NANOS
    if (candAccuracyM.isNaN()) return veryStale              // can't judge it: only when desperate
    if (curAccuracyM.isNaN()) return true                    // anything beats an unmeasured fix
    if (candAccuracyM <= curAccuracyM + 20f) return true     // comparable-or-better: track it
    return veryStale                                         // much worse: only to recover a dead fix
}
