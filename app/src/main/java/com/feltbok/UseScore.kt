package com.feltbok

import kotlin.math.pow

// A bird's "how much you use it" signal: a pick count that fades with time, so species you logged
// heavily long ago sink while recent picks stay near the top. One UseEntry per species; reads are
// decayed to a given instant. Pure and time-injected (the clock is passed in) so it's unit-testable.

/** ~2 weeks: a trip's birds stay hot, last month fades, last year is essentially gone. */
const val USE_HALF_LIFE_MS = 14L * 24 * 60 * 60 * 1000

data class UseEntry(val score: Double, val lastTouched: Long)

private fun decayFactor(ageMs: Long): Double =
    if (ageMs <= 0L) 1.0 else 0.5.pow(ageMs.toDouble() / USE_HALF_LIFE_MS)

/** [entry]'s score faded to [now] - halved for every half-life since it was last touched. */
fun decayedScore(entry: UseEntry, now: Long): Double = entry.score * decayFactor(now - entry.lastTouched)

/** Register a pick at [now]: fade what was there, then add one. */
fun bumpUse(entry: UseEntry?, now: Long): UseEntry =
    UseEntry((entry?.let { decayedScore(it, now) } ?: 0.0) + 1.0, now)

/** Your decayed [score] compressed into [0,1): rises quickly over your first handful of picks, then
 *  flattens, so a heavy history (or a big migrated count) can't swamp the here-and-now signal - and,
 *  unlike a hard cap, it keeps discriminating (10 picks still beats 5). [PERSONAL_MIDPOINT] is the
 *  score at which it reaches 0.5. */
const val PERSONAL_MIDPOINT = 4.0
fun personalWeight(score: Double): Double = if (score <= 0.0) 0.0 else score / (score + PERSONAL_MIDPOINT)

// Relative pull of your history vs. what's likely here-and-now in the blank-list ranking. Leans on
// the personal signal: in practice context saturates near-flat across in-season common birds (so it
// discriminates little on the high end), and the decayed personal score already carries season/recency
// - context's non-redundant job is gating off-season/off-region birds at the low end. Tune vs field use.
const val PERSONAL_W = 0.6
const val CONTEXT_W = 0.4

/** Blend your personal signal with the here-and-now [context] weight into one ranking value in [0,1].
 *  Both contribute on the same [0,1] scale (no hard caps that saturate for heavy users), so the list
 *  keeps discriminating from the top regular all the way down. */
fun blendedWeight(context: Double, personalScore: Double): Double =
    PERSONAL_W * personalWeight(personalScore) + CONTEXT_W * context
