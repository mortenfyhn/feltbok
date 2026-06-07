package com.feltbok

import kotlin.math.abs
import kotlin.math.ln

/**
 * Pluggable, benchmarkable species search.
 *
 * The search is *scoring, not filtering*: every candidate gets ranked for the current query and the
 * best few are shown. This file holds the swappable pieces so the app and the JVM benchmark
 * ([app/src/test] `SearchBenchmark`) can enumerate, select, and A/B ranking strategies against each
 * other. It is deliberately free of Android imports so the ranking logic stays testable without an
 * emulator.
 *
 * The dataset is tiny (~600 species), so a flat scan-and-sort per keystroke is sub-millisecond - no
 * trie/index/search-engine. Speed comes from normalizing once at load ([prepare]) and keeping the
 * per-keystroke loop allocation-light.
 */

/** A species with its search fields normalized once at load, so the hot loop never re-folds. */
data class PreparedSpecies(
    val species: Species,
    /** Lowercased + diacritic-folded name, spaces kept (see [fold]); the baseline's match target. */
    val folded: String,
    /** Position in the source list, which is sorted most-common-first; the stable-order tiebreak. */
    val index: Int,
    /** [folded] with spaces/hyphens stripped - the tiered scorer matches against this so compound
     *  names with the rare space (svarthvit fluesnapper) behave like the single-token majority. */
    val foldedTight: String,
    /** Lowercased name, diacritics kept, spaces/hyphens stripped - lets the scorer notice when the
     *  user typed æ/ø/å correctly and award the diacritic-exact bonus. */
    val lowerTight: String,
)

internal fun stripSep(s: String) = s.filterNot { it == ' ' || it == '-' }

fun prepare(species: List<Species>): List<PreparedSpecies> =
    species.mapIndexed { i, s ->
        val folded = fold(s.norsk)
        PreparedSpecies(s, folded, i, stripSep(folded), stripSep(s.norsk.lowercase()))
    }

/** One ranked result. [score] is descending-best (higher = better) within a scorer; not comparable
 *  across scorers. [match] is the matched span in the *original* `norsk`, for highlighting (null
 *  when the scorer doesn't report one). */
data class Ranked(val species: Species, val score: Double, val match: IntRange? = null)

/**
 * Relative commonness of a species, used both as a scorer's frequency signal and as the benchmark's
 * per-target weighting (common birds matter more). Pluggable so a future region/season-aware
 * provider can slot in behind the same interface without touching the scorers.
 */
fun interface FrequencyProvider {
    /** Commonness weight in roughly [0,1], larger = more common. */
    fun weight(species: Species): Double
}

/** Commonness from the source list's most-common-first ordering: rank 0 -> ~1.0, last -> ~0.
 *  Ignores the real (very skewed) magnitudes; [NumericFrequency] uses those when available. */
class RowOrderFrequency(species: List<Species>) : FrequencyProvider {
    private val n = species.size.coerceAtLeast(1)
    private val rank = species.withIndex().associate { (i, s) -> s.norsk to i }
    override fun weight(species: Species): Double {
        val i = rank[species.norsk] ?: (n - 1)
        return (n - i).toDouble() / n
    }
}

/** Commonness from the real observation [Species.count], log-scaled (the distribution is Zipfian -
 *  the top bird outnumbers the median ~1000:1) and normalized to [0,1]. Degrades gracefully: if the
 *  CSV carried no counts (all 0), every weight is 0, so the scorer falls back to frequency-neutral. */
class NumericFrequency(species: List<Species>) : FrequencyProvider {
    private val maxLog = ln(1.0 + (species.maxOfOrNull { it.count } ?: 0).toDouble())
    override fun weight(species: Species): Double =
        if (maxLog <= 0.0) 0.0 else ln(1.0 + species.count.toDouble()) / maxLog
}

/**
 * Season-aware commonness: how often a species is reported in the *current* [month] (1..12),
 * log-normalized, blended with all-time so a generally-common bird never vanishes off-season - it
 * just ranks lower, still fully findable (folding/tiers still match; only the frequency boost
 * shrinks). [monthly] maps latin -> 12 monthly counts (species_months.csv). With no monthly data it
 * degrades to plain all-time frequency. This is the "time of year" provider; it slots behind the same
 * [FrequencyProvider] interface, so the scorer is unchanged.
 */
class SeasonalFrequency(
    species: List<Species>,
    private val monthly: Map<String, IntArray>,
    private val month: Int,
    private val seasonWeight: Double = 0.8,
) : FrequencyProvider {
    private val allTime = NumericFrequency(species)
    private val maxLog = ln(1.0 + (monthly.values.maxOfOrNull { it.getOrElse(month - 1) { 0 } } ?: 0).toDouble())
    override fun weight(species: Species): Double {
        val c = monthly[species.latin]?.getOrElse(month - 1) { 0 } ?: 0
        val season = if (maxLog <= 0.0) 0.0 else ln(1.0 + c.toDouble()) / maxLog
        return seasonWeight * season + (1.0 - seasonWeight) * allTime.weight(species)
    }
}

/** A named ranking strategy. Each implementation scores all [candidates] for [query] and returns
 *  them best-first (callers take the top N). Any frequency signal is baked into the scorer at
 *  construction (so two configs can differ only by their [FrequencyProvider]). */
interface BirdSearchScorer {
    val name: String
    fun search(query: String, candidates: List<PreparedSpecies>): List<Ranked>
}

/**
 * The app's current behavior, as a benchmark baseline: [fuzzyRank] tiers (prefix > initials >
 * substring > subsequence), ties broken by the user's own [useCount] then the most-common-first
 * source order. Ignores [freq] - frequency here is implicit in the candidate order.
 */
class BaselineScorer(private val useCount: (String) -> Int = { 0 }) : BirdSearchScorer {
    override val name = "baseline"
    override fun search(query: String, candidates: List<PreparedSpecies>): List<Ranked> {
        val fq = foldQuery(query)
        return candidates
            .mapNotNull { c -> fuzzyRank(fq, c.folded)?.let { c to it } }
            // Lower tier = better; then more-used; then earlier (= more common) in the source order.
            .sortedWith(compareBy({ it.second }, { -useCount(it.first.species.norsk) }, { it.first.index }))
            // Score is just the negated final position so callers see descending-best, like the others.
            .mapIndexed { pos, (c, _) -> Ranked(c.species, -pos.toDouble()) }
    }
}

/**
 * Tunable weights for [TieredScorer]. A `data class` so the benchmark can grid/random-search these
 * without touching code. Tier bases are spaced widely enough that, with [freqStrength] capped,
 * a lower tier can't overtake a higher one - so "suffix beats interior" and "prefix beats suffix"
 * hold no matter how common the bird. Frequency and the quality factors only reorder *within* a tier.
 *
 * These were grid-searched (see `SearchBenchmark.tuneWeights`): blind tuning beat the originals by
 * only ~0.3 pp top-3 (noise), but it agreed on completeness=0.2, and field testing then forced two
 * value changes (completeness 0.4->0.2 and a stronger diacriticExact) to fix specific real cases -
 * see those fields. The remaining ceilings (family queries with many members, sparse subsequences)
 * are structural, not weight-fixable.
 */
data class TierWeights(
    val exact: Double = 100_000.0,
    val prefix: Double = 10_000.0,
    /** query is a known suffix morpheme (from the dictionary) and the name ends with it - the most
     *  confident suffix match. Inert unless a suffixes set is supplied (then "and" -> ducks, etc.). */
    val knownSuffix: Double = 5_000.0,
    val suffix: Double = 2_000.0, // generic word-ending; > substring so a suffix beats an interior hit
    /** initialism: a subsequence anchored at the first letter ("pf" -> Pilfink). Its own tier above
     *  substring, the way the old baseline ranked initials - this is what fixes the subq regression. */
    val anchoredSubseq: Double = 1_000.0,
    val substring: Double = 500.0,
    val subseq: Double = 100.0, // scattered subsequence: the always-returns-something net
    val typo: Double = 20.0,
    /** freqMult = 1 + freqStrength * weight, weight in [0,1]; so the multiplier stays in [1, 2]. */
    val freqStrength: Double = 1.0,
    /** completeness factor = (1 - completeness) + completeness * (queryLen / nameLen); rewards a
     *  query that covers more of a (shorter) name, mildly, so it never dominates frequency. Kept low
     *  (0.2) so a much-more-common long name still beats a short rarity ("stor" -> Storspove 218k,
     *  not Storjo 16k); grid search agreed 0.2 over 0.4. */
    val completeness: Double = 0.2,
    /** ×(1 + diacriticExact) when the query matches the real letters (no folding needed) - precision
     *  rewarded, not required. Big enough to lift an exact-letter match over a folded one even across
     *  a frequency gap ("a" -> Alke over Ærfugl). */
    val diacriticExact: Double = 0.15,
)

/**
 * The full tiered scorer: exact > prefix(modifier) > known-suffix > suffix > initialism > interior
 * substring > subsequence > capped typo, each scaled by a within-tier quality factor and a frequency
 * multiplier, with a small diacritic-exact bonus. Single continuous score per species
 * (`tier × quality × freqMult × diacriticFactor`), so it doubles as a highlight/threshold signal.
 *
 * [suffixes] is the optional, swappable suffix-morpheme dictionary: when supplied, a query that *is*
 * one of these suffix morphemes and matches a name's ending is promoted to the [TierWeights.knownSuffix]
 * tier. Empty by default, so the plain "tiered" config behaves exactly as before.
 */
class TieredScorer(
    private val freq: FrequencyProvider,
    private val w: TierWeights = TierWeights(),
    suffixes: Set<String> = emptySet(),
    override val name: String = "tiered",
) : BirdSearchScorer {
    private val suffixes = suffixes.map { stripSep(fold(it)) }.toSet() // fold to match the query/name space

    override fun search(query: String, candidates: List<PreparedSpecies>): List<Ranked> {
        val fq = stripSep(foldQuery(query))
        val lq = stripSep(query.lowercase())
        return candidates
            .mapNotNull { c ->
                val tq = tierAndQuality(fq, c.foldedTight) ?: return@mapNotNull null
                val freqMult = 1.0 + w.freqStrength * freq.weight(c.species)
                // Diacritic-exact: reward a match that holds on the real letters, i.e. without leaning
                // on folding. So "a" prefers Alke (real 'a') over Ærfugl (only via æ->ae), and a query
                // that didn't reproduce a name's æ/ø/å (or matched a part that has none) isn't penalised
                // - it just doesn't get the nudge. Keeps folding free while rewarding precision.
                val diacritic = if (matchesExact(lq, c.lowerTight)) 1.0 + w.diacriticExact else 1.0
                Ranked(c.species, tq.first * tq.second * freqMult * diacritic)
            }
            .sortedWith(compareByDescending<Ranked> { it.score }.thenBy { it.species.norsk })
    }

    /** (tier base, quality factor in ~(0,1.x]) for [fq] against the folded name [fn], or null for
     *  no match. Cheaper structural tiers first; the capped typo tier is a last-resort net. */
    private fun tierAndQuality(fq: String, fn: String): Pair<Double, Double>? {
        if (fq.isEmpty()) return 1.0 to 1.0 // blank query -> rank by frequency alone
        if (fn == fq) return w.exact to 1.0
        val comp = fq.length.toDouble() / fn.length
        val compFactor = (1.0 - w.completeness) + w.completeness * comp
        if (fn.startsWith(fq)) return w.prefix to compFactor
        if (fn.endsWith(fq)) {
            // With a dictionary: only a real suffix morpheme earns the known-suffix tier; a coincidental
            // word-ending falls through to substring (this is what the dictionary buys us). Without
            // one: the generic "suffix beats interior" heuristic boosts every word-ending.
            if (fq in suffixes) return w.knownSuffix to compFactor
            if (suffixes.isEmpty()) return w.suffix to compFactor
        }
        if (fn.contains(fq)) return w.substring to compFactor
        val span = subsequenceSpan(fq, fn)
        if (span > 0) {
            // Anchored at the first letter = an initialism (pf -> Pilfink); its own tier above
            // substring. Scattered subsequences stay the lowest net.
            val base = if (fq[0] == fn[0]) w.anchoredSubseq else w.subseq
            return base to fq.length.toDouble() / span
        }
        if (fq.length >= 4) { // typo net only once a query is long enough to be distinctive
            val cap = if (fq.length <= 5) 1 else 2
            // Treat the query as a typo'd *prefix*: compare to the same-length name slice (catches
            // substitution/transposition without charging for the untyped rest, e.g. "tybj" -> tyvjo)
            // and to a slightly longer slice (so a deletion still aligns). Take the closer.
            val dist = minOf(osaCapped(fq, fn.take(fq.length), cap), osaCapped(fq, fn.take(fq.length + cap), cap))
            if (dist <= cap) return w.typo to (1.0 - dist.toDouble() / fq.length)
        }
        return null
    }

    /** Whether the query matches on the real (diacritic-preserving) letters, so a match that doesn't
     *  need folding can be rewarded over one that does. */
    private fun matchesExact(lq: String, lower: String) = lq.isNotEmpty() && lower.contains(lq)
}

/** Length of the left-greedy subsequence match of [q] in [t] (last index - first index + 1), or -1
 *  if [q] isn't a subsequence. Smaller span = tighter (fewer gaps) = a better subsequence match. */
fun subsequenceSpan(q: String, t: String): Int {
    var qi = 0
    var start = -1
    var end = -1
    for (i in t.indices) {
        if (qi < q.length && t[i] == q[qi]) {
            if (qi == 0) start = i
            end = i
            qi++
            if (qi == q.length) break
        }
    }
    return if (qi == q.length) end - start + 1 else -1
}

/** Optimal String Alignment distance (Damerau-Levenshtein restricted to adjacent transpositions -
 *  the most common typo), capped: returns the true distance if <= [cap], else [cap] + 1. Early-exits
 *  when an entire row exceeds the cap. The dataset is tiny and these strings are short, so the plain
 *  matrix is fast enough; the cap is what keeps it cheap. */
fun osaCapped(a: String, b: String, cap: Int): Int {
    val n = a.length
    val m = b.length
    if (abs(n - m) > cap) return cap + 1
    val d = Array(n + 1) { IntArray(m + 1) }
    for (i in 0..n) d[i][0] = i
    for (j in 0..m) d[0][j] = j
    for (i in 1..n) {
        var rowMin = Int.MAX_VALUE
        for (j in 1..m) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            var v = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
            if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                v = minOf(v, d[i - 2][j - 2] + 1)
            }
            d[i][j] = v
            if (v < rowMin) rowMin = v
        }
        if (rowMin > cap) return cap + 1
    }
    return if (d[n][m] <= cap) d[n][m] else cap + 1
}

/**
 * A library-style comparison candidate: rank by Jaro-Winkler similarity (× frequency). JW has a
 * built-in common-prefix boost, which suits the prefix-heavy way people type, and it's inherently
 * typo-tolerant - so it needs no explicit tier ladder. We score against both the whole name and its
 * leading slice (so a clean prefix scores ~1.0), and take the max. The benchmark says whether this
 * single-similarity approach can rival the hand-tuned tier ladder; it has no notion of "suffix" or
 * "family", so we expect it to trail on those, and that contrast is the point of having it.
 */
class JaroWinklerScorer(private val freq: FrequencyProvider, override val name: String = "jaro-winkler") :
    BirdSearchScorer {
    override fun search(query: String, candidates: List<PreparedSpecies>): List<Ranked> {
        val fq = stripSep(foldQuery(query))
        return candidates
            .map { c ->
                val sim = if (fq.isEmpty()) {
                    0.0
                } else {
                    maxOf(jaroWinkler(fq, c.foldedTight), jaroWinkler(fq, c.foldedTight.take(fq.length)))
                }
                Ranked(c.species, sim * (1.0 + freq.weight(c.species)))
            }
            .sortedWith(compareByDescending<Ranked> { it.score }.thenBy { it.species.norsk })
    }
}

/** Jaro-Winkler similarity in [0,1]: Jaro plus a boost for a shared leading prefix (the most common
 *  query shape). [p] is the per-char prefix weight, [maxPrefix] the boost cap - the usual 0.1 / 4. */
fun jaroWinkler(a: String, b: String, p: Double = 0.1, maxPrefix: Int = 4): Double {
    val j = jaro(a, b)
    var prefix = 0
    val cap = minOf(maxPrefix, a.length, b.length)
    while (prefix < cap && a[prefix] == b[prefix]) prefix++
    return j + prefix * p * (1.0 - j)
}

/** Jaro similarity in [0,1]: shared characters within a sliding window, discounted by transpositions. */
fun jaro(a: String, b: String): Double {
    if (a == b) return 1.0
    val la = a.length
    val lb = b.length
    if (la == 0 || lb == 0) return 0.0
    val window = (maxOf(la, lb) / 2 - 1).coerceAtLeast(0)
    val aMatched = BooleanArray(la)
    val bMatched = BooleanArray(lb)
    var matches = 0
    for (i in 0 until la) {
        val start = (i - window).coerceAtLeast(0)
        val end = minOf(i + window + 1, lb)
        for (k in start until end) {
            if (!bMatched[k] && a[i] == b[k]) {
                aMatched[i] = true
                bMatched[k] = true
                matches++
                break
            }
        }
    }
    if (matches == 0) return 0.0
    var transpositions = 0
    var k = 0
    for (i in 0 until la) {
        if (aMatched[i]) {
            while (!bMatched[k]) k++
            if (a[i] != b[k]) transpositions++
            k++
        }
    }
    val m = matches.toDouble()
    return (m / la + m / lb + (m - transpositions / 2.0) / m) / 3.0
}

/** Registry of all candidates, so the app and the benchmark enumerate the same set.
 *  [useCount] is the app's personal-frequency hook; the benchmark passes the default (no history). */
object Scorers {
    fun all(
        species: List<Species>,
        useCount: (String) -> Int = { 0 },
        suffixes: Set<String> = emptySet(),
    ): List<BirdSearchScorer> = buildList {
        add(BaselineScorer(useCount))
        add(TieredScorer(RowOrderFrequency(species), name = "tiered"))
        add(TieredScorer(NumericFrequency(species), name = "tiered-freq"))
        add(JaroWinklerScorer(NumericFrequency(species)))
        // The morpheme-dictionary candidate: only meaningful with a suffixes list supplied.
        if (suffixes.isNotEmpty()) {
            add(TieredScorer(NumericFrequency(species), suffixes = suffixes, name = "morpheme"))
        }
    }

    fun byName(
        name: String,
        species: List<Species>,
        useCount: (String) -> Int = { 0 },
        suffixes: Set<String> = emptySet(),
    ): BirdSearchScorer = all(species, useCount, suffixes).first { it.name == name }
}
