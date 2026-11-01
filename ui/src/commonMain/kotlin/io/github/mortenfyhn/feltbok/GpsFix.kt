package io.github.mortenfyhn.feltbok

/** A GPS fix plus enough info for the UI to judge whether it has settled. */
data class GpsFix(
    val lat: Double,
    val lon: Double,
    val accuracyM: Float,
    val elapsedRealtimeNanos: Long,
)

/** ~60 s; past this the current fix is treated as stale enough to take whatever arrives next. */
private const val STALE_NANOS = 60_000_000_000L

/**
 * Should a new fix replace the current one? Pure (no platform types) so it's unit-testable;
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
