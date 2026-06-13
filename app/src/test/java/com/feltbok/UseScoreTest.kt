package com.feltbok

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The decayed use-score: the core of how recent picks outrank once-frequent-but-stale ones. */
class UseScoreTest {
    private val t0 = 1_000_000_000_000L

    @Test fun `a fresh pick scores one`() {
        assertEquals(1.0, bumpUse(null, t0).score, 1e-9)
    }

    @Test fun `score halves over one half-life`() {
        val e = bumpUse(null, t0)
        assertEquals(0.5, decayedScore(e, t0 + USE_HALF_LIFE_MS), 1e-9)
    }

    @Test fun `picks accumulate on top of the decayed remainder`() {
        // One pick, then another a half-life later: the first has faded to 0.5, plus the new 1.0.
        val after = bumpUse(bumpUse(null, t0), t0 + USE_HALF_LIFE_MS)
        assertEquals(1.5, after.score, 1e-9)
    }

    @Test fun `many picks long ago lose to a single recent pick`() {
        val old = UseEntry(score = 1000.0, lastTouched = t0)
        val recent = bumpUse(null, t0 + 20L * USE_HALF_LIFE_MS) // ~40 weeks later
        val now = t0 + 20L * USE_HALF_LIFE_MS
        assertTrue(decayedScore(old, now) < decayedScore(recent, now))
    }
}
