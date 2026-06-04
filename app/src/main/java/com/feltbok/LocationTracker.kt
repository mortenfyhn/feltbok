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

    /**
     * Keep [candidate] over [current] if it's clearly fresher or better: a much
     * newer fix wins outright (the old one has gone stale), otherwise we only
     * replace with something of comparable-or-better accuracy so a coarse
     * network fix doesn't clobber a precise GPS one.
     */
    private fun isBetter(candidate: Location, current: Location?): Boolean {
        if (current == null) return true
        val newerByNanos = candidate.elapsedRealtimeNanos - current.elapsedRealtimeNanos
        if (newerByNanos > 10_000_000_000L) return true   // current >10s stale
        if (newerByNanos < 0) return false                // older fix
        if (!candidate.hasAccuracy()) return false
        if (!current.hasAccuracy()) return true
        return candidate.accuracy <= current.accuracy + 20f
    }

    private fun Location.toFix() = GpsFix(
        lat = latitude,
        lon = longitude,
        // hasAccuracy() is false on some devices; surface that as "unknown".
        accuracyM = if (hasAccuracy()) accuracy else Float.NaN,
        elapsedRealtimeNanos = elapsedRealtimeNanos,
    )
}
