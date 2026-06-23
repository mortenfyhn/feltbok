package io.github.mortenfyhn.feltbok

import kotlin.math.floor
import kotlin.math.ln

/**
 * The commonness model: how likely a species is, turned into a weight the search core ([rankSpecies])
 * multiplies in. This is the app-specific half - it knows about observation counts, calendars and a
 * location grid - kept out of the search file so the ranker stays a pure, portable function that just
 * takes a `(Species) -> Double`. The app builds that lambda from [ContextualFrequency.weight] (plus a
 * personal-use nudge); the benchmark and tests supply their own.
 */

/** Commonness from the real observation [Species.count], log-scaled (the distribution is Zipfian - the
 *  top bird outnumbers the median ~1000:1) and normalized to [0,1]. Degrades gracefully: if the CSV
 *  carried no counts (all 0), every weight is 0, so the scorer falls back to frequency-neutral. */
class NumericFrequency(species: List<Species>) {
    private val maxLog = ln(1.0 + (species.maxOfOrNull { it.count } ?: 0).toDouble())
    fun weight(species: Species): Double =
        if (maxLog <= 0.0) 0.0 else ln(1.0 + species.count.toDouble()) / maxLog
}

/**
 * Context-aware commonness for ranking: blends how often a species is reported (a) in the current
 * calendar [month], (b) near the current location (a coarse lat/lon grid cell), and (c) all-time. So
 * search surfaces "what's reported here, now" - spring migrants in May, southern birds in the south -
 * while the all-time term keeps a generally-common bird from vanishing where/when it's scarce (it
 * ranks lower but stays findable; tiers/folding still match). Location is optional: with no GPS fix
 * the region term drops and its weight folds into all-time.
 *
 * Pure (no Android): the app feeds today's month and the live fix per query. [monthly] is latin -> 12
 * monthly counts (species_months.csv); [regionCounts] is grid-cell -> latin -> count, cells being
 * 1deg lat x 2deg lon (see [cellKey] / build_species_regions.py). With neither it degrades to all-time
 * frequency. Weights are a prototype default - tune seasonW/regionW/baseW against field use.
 */
class ContextualFrequency(
    species: List<Species>,
    private val monthly: Map<String, IntArray>,
    private val regionCounts: Map<Int, Map<String, Int>>,
    private val seasonW: Double = 0.45,
    private val regionW: Double = 0.35,
    private val baseW: Double = 0.20,
) {
    private val allTime = NumericFrequency(species)
    private val monthMaxLog = DoubleArray(12) { m ->
        ln(1.0 + (monthly.values.maxOfOrNull { it.getOrElse(m) { 0 } } ?: 0).toDouble())
    }
    private val regionMaxLog = regionCounts.mapValues { (_, m) -> ln(1.0 + (m.values.maxOrNull() ?: 0).toDouble()) }

    /** [month] is 1..12; [lat]/[lon] are the live fix, or null when location is unknown. */
    fun weight(species: Species, month: Int, lat: Double?, lon: Double?): Double {
        val base = allTime.weight(species)
        val mi = (month - 1).coerceIn(0, 11) // month is 1..12 from the caller; guard the array index anyway
        val season = norm(monthly[species.latin]?.getOrElse(mi) { 0 } ?: 0, monthMaxLog[mi])
        val cell = if (lat != null && lon != null) cellKey(lat, lon) else null
        val region = cell?.let { k -> regionMaxLog[k]?.let { norm(regionCounts[k]?.get(species.latin) ?: 0, it) } }
        // DELIBERATE: with no location, region's share is *redistributed* across the remaining terms
        // (renormalize), not dropped. So this is NOT equivalent to `region = 0` / `region ?: 0.0` -
        // that would deflate every weight to a 0.65 ceiling. Don't "simplify" the two branches into one.
        return if (region == null) {
            (seasonW * season + (regionW + baseW) * base) / (seasonW + regionW + baseW)
        } else {
            seasonW * season + regionW * region + baseW * base
        }
    }

    private fun norm(count: Int, maxLog: Double) = if (maxLog <= 0.0) 0.0 else ln(1.0 + count.toDouble()) / maxLog

    companion object {
        /** Grid cell key for a coordinate: SW corner as latS*100 + lonW (1deg lat, 2deg lon). */
        fun cellKey(lat: Double, lon: Double) = floor(lat).toInt() * 100 + floor(lon / 2).toInt() * 2
    }
}
