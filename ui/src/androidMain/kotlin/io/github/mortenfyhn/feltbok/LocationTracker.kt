package io.github.mortenfyhn.feltbok

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Streams live GPS fixes while [start]ed. We keep updates running the whole
 * time the screen is visible (not just at record time) so the receiver stays
 * warm: when you switch back from another app the position reacquires within a
 * second or two, and the UI can show it settling instead of silently stamping
 * a stale coordinate.
 */
class LocationTracker(private val context: Context) : LocationSource {
    private val manager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val _fix = MutableStateFlow<GpsFix?>(null)
    override val fix: StateFlow<GpsFix?> = _fix

    private val _off = MutableStateFlow(false)
    override val off: StateFlow<Boolean> = _off

    private var best: GpsFix? = null

    private val listener = LocationListener { loc ->
        val f = loc.toFix()
        val cur = best
        if (cur == null || preferNewFix(f.accuracyM, f.elapsedRealtimeNanos, cur.accuracyM, cur.elapsedRealtimeNanos) && worthPublishing(f, cur)) {
            best = f
            _fix.value = f
        }
    }

    // Location switched on or off in the system settings: request again from what's enabled now.
    private val providersChanged = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = request()
    }
    private var watchingProviders = false

    override fun start() {
        best = null
        if (!watchingProviders) {
            ContextCompat.registerReceiver(context, providersChanged,
                IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
            watchingProviders = true
        }
        request()
    }

    @SuppressLint("MissingPermission") // checked just above the request
    private fun request() {
        manager.removeUpdates(listener)
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        // Network gives a fast, coarse fix (works indoors); GPS refines it to a
        // precise one outdoors. We request both and keep whichever is best.
        val providers = if (!granted) emptyList()
        else listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter { manager.isProviderEnabled(it) }
        _off.value = providers.isEmpty()
        if (providers.isEmpty()) { best = null; _fix.value = null }   // no stale position while off
        for (provider in providers) {
            manager.getLastKnownLocation(provider)?.let { listener.onLocationChanged(it) }
            manager.requestLocationUpdates(provider, 1000L, 0f, listener)
        }
    }

    override fun stop() {
        manager.removeUpdates(listener)
        if (watchingProviders) { context.unregisterReceiver(providersChanged); watchingProviders = false }
    }

    private fun Location.toFix() = GpsFix(
        lat = latitude,
        lon = longitude,
        // hasAccuracy() is false on some devices; surface that as "unknown".
        accuracyM = if (hasAccuracy()) accuracy else Float.NaN,
        elapsedRealtimeNanos = elapsedRealtimeNanos,
    )
}
