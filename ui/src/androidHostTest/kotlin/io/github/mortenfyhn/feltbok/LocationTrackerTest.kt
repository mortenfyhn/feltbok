package io.github.mortenfyhn.feltbok

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val SEC = 1_000_000_000L

/** The fix accept/reject rule (issue #32): track good fixes, but don't let a much worse one
 *  jump the position unless the current fix has gone stale. */
class LocationTrackerTest {

    @Test fun acceptsComparableOrBetterAccuracy() {
        assertTrue(preferNewFix(candAccuracyM = 8f, candNanos = 2 * SEC, curAccuracyM = 10f, curNanos = SEC))
        // a little worse is still fine (GPS jitter) - within the 20 m tolerance
        assertTrue(preferNewFix(candAccuracyM = 25f, candNanos = 2 * SEC, curAccuracyM = 10f, curNanos = SEC))
    }

    @Test fun rejectsMuchWorseFreshFix() {
        // a 1200 m cell/network fix arriving 2 s after an 8 m GPS fix must not move us
        assertFalse(preferNewFix(candAccuracyM = 1200f, candNanos = 3 * SEC, curAccuracyM = 8f, curNanos = SEC))
    }

    @Test fun takesMuchWorseFixOnlyOnceStale() {
        // same bad fix, but the good one is now >60 s old: recover rather than stay stuck
        assertTrue(preferNewFix(candAccuracyM = 1200f, candNanos = 100 * SEC, curAccuracyM = 8f, curNanos = SEC))
    }

    @Test fun rejectsOlderFix() {
        assertFalse(preferNewFix(candAccuracyM = 5f, candNanos = SEC, curAccuracyM = 50f, curNanos = 2 * SEC))
    }

    @Test fun unmeasuredCandidateTakenOnlyWhenStale() {
        assertFalse(preferNewFix(candAccuracyM = Float.NaN, candNanos = 3 * SEC, curAccuracyM = 8f, curNanos = SEC))
        assertTrue(preferNewFix(candAccuracyM = Float.NaN, candNanos = 100 * SEC, curAccuracyM = 8f, curNanos = SEC))
    }

    // ~1 m of latitude, so jitter and moves can be written in metres.
    private val m = 1.0 / 111_195
    private val here = GpsFix(lat = 63.4, lon = 10.4, accuracyM = 8f, elapsedRealtimeNanos = SEC)

    @Test fun standingStillIsNotWorthPublishing() {
        // the once-a-second stream while you stand still must not trigger a rescan every second
        assertFalse(worthPublishing(here.copy(lat = here.lat + 2 * m, accuracyM = 7f, elapsedRealtimeNanos = 2 * SEC), here))
    }

    @Test fun movingSharperOrStaleIsWorthPublishing() {
        assertTrue(worthPublishing(here.copy(lat = here.lat + 6 * m, elapsedRealtimeNanos = 2 * SEC), here))
        assertTrue(worthPublishing(here.copy(accuracyM = 4f, elapsedRealtimeNanos = 2 * SEC), here))
        assertTrue(worthPublishing(here.copy(elapsedRealtimeNanos = 17 * SEC), here))
    }
}
