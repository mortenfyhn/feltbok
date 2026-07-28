package io.github.mortenfyhn.feltbok

import org.junit.Assert.assertEquals
import org.junit.Test

/** Regression tests for #162: ub. (ubestemt/unidentified) entries are searchable like any other
 *  species, but shouldn't get the "how likely is this species here and now" boost real species get
 *  - they carry no observation count and no season/region data, so [NumericFrequency] (and, in the
 *  app, [ContextualFrequency]) already scores them at 0 with zero code changes to Search.kt or
 *  Frequency.kt. These tests pin that behavior so it can't silently regress. */
class UbestemtRankingTest {

    /** Same match tier (substring) for both names - padded on both sides so the query neither
     *  starts nor ends either name - isolates the likelihood term from the tier system. */
    private val real = Species(latin = "Realis testus", norsk = "xxblåstjertxx", count = 1000)
    private val ub = Species(latin = "Ub testus", norsk = "ub. xxblåstjertxx", count = 0)

    private fun likelihoodOf(vararg species: Species): (Species) -> Double {
        val freq = NumericFrequency(species.toList())
        return { s -> freq.weight(s) }
    }

    @Test
    fun realSpeciesOutranksSameTierUbestemtEntry() {
        val prepared = prepare(listOf(real, ub), setOf(Lang.NORSK))
        val ranked = rankSpecies("blåstjert", prepared, likelihoodOf(real, ub))
        assertEquals(listOf(real.latin, ub.latin), ranked.map { it.species.latin })
    }

    @Test
    fun ubestemtEntryStillWinsOnABetterMatchTier() {
        // "ub. exacttest" is an exact match for the ub. entry; the real species only contains it as
        // a substring (padded), a lower tier - so the ub. entry wins despite having no likelihood at
        // all, proving tier still dominates frequency exactly as it does for two real species.
        val exactUb = Species(latin = "Ub exact test", norsk = "ub. exacttest", count = 0)
        val paddedReal = Species(latin = "Realis padded", norsk = "yub. exacttesty", count = 1000)
        val prepared = prepare(listOf(exactUb, paddedReal), setOf(Lang.NORSK))
        val ranked = rankSpecies("ub. exacttest", prepared, likelihoodOf(exactUb, paddedReal))
        assertEquals(listOf(exactUb.latin, paddedReal.latin), ranked.map { it.species.latin })
    }

    /** The app builds its likelihood lambda from [ContextualFrequency], not [NumericFrequency] - so
     *  pin that directly too, with a real species that DOES have monthly/region data alongside the
     *  ub. entry (which has neither), and a non-null lat/lon so the *region* branch of `weight` runs
     *  (the null-location fallback branch takes a different formula - see the DELIBERATE comment in
     *  Frequency.kt). Proves season and region terms both genuinely resolve to 0 for a ub.-shaped
     *  entry, not just that they're absent from the maps. */
    @Test
    fun contextualFrequencyScoresUbestemtEntryAsZero() {
        val monthly = mapOf(real.latin to IntArray(12) { 50 })
        val cell = ContextualFrequency.cellKey(63.0, 10.0)
        val regionCounts = mapOf(cell to mapOf(real.latin to 50))
        val freq = ContextualFrequency(listOf(real, ub), monthly, regionCounts)
        assertEquals(0.0, freq.weight(ub, month = 5, lat = 63.0, lon = 10.0), 0.0)
    }
}
