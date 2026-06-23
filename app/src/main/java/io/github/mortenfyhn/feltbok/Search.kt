package io.github.mortenfyhn.feltbok

import kotlin.math.abs

/**
 * The species search, as one pure function: [rankSpecies].
 *
 * It is *scoring, not filtering*: every candidate gets a score for the query and the caller takes the
 * best few, so there's always something on screen even on a near-miss. The score is a single product
 * (`tier × quality × frequency × diacritic`), and the tier bases are spaced so widely that a better
 * match tier can never be overtaken by frequency or the quality factors - those only reorder *within*
 * a tier. So the order is predictable: a prefix match always beats a substring match, however common
 * the bird.
 *
 * The function has no Android, no I/O, no state - it takes the query, the candidates, and a
 * commonness [weight] for each species (the app blends season/location/personal-use into that lambda;
 * the search core never sees calendars or coordinates). That keeps it trivially unit-testable and easy
 * to lift out as a standalone library. The comparison scorers it's benchmarked against live in the
 * test tree (`SearchScorers.kt` / `SearchBenchmark`), not here.
 *
 * The dataset is tiny (~600 species), so a flat scan-and-sort per keystroke is sub-millisecond - no
 * trie/index/search-engine. Speed comes from normalizing each name once at load ([prepare]) so the
 * per-keystroke loop only reads.
 */

/** A species with its search fields normalized once at load, so the hot loop never re-folds. */
data class PreparedSpecies(
    val species: Species,
    /** Lowercased name, spaces kept (see [fold]). */
    val folded: String,
    /** Position in the source list, which is sorted most-common-first; the stable-order tiebreak. */
    val index: Int,
    /** [folded] with spaces/hyphens stripped - the scorer matches against this so compound names with
     *  the rare space (svarthvit fluesnapper) behave like the single-token majority. */
    val foldedTight: String,
    /** Lowercased name, spaces/hyphens stripped - lets the scorer notice when the user typed æ/ø/å
     *  (which [fold] keeps) and award the diacritic-exact bonus. */
    val lowerTight: String,
)

internal fun stripSep(s: String) = s.filterNot { it == ' ' || it == '-' }

private fun preparedFor(s: Species, name: String, index: Int): PreparedSpecies {
    val folded = fold(name)
    return PreparedSpecies(s, folded, index, stripSep(folded), stripSep(name.lowercase()))
}

fun prepare(species: List<Species>): List<PreparedSpecies> =
    species.flatMapIndexed { i, s ->
        // The primary name plus, when present, the secondary name ([Species.alt], e.g. the Norwegian
        // name in the Sweden build) as an extra alias entry pointing at the same species, so a query
        // matches either. Both share the index, so frequency/tiebreak are identical. The caller
        // dedupes the species afterwards. No alias in the Norway build (alt is blank).
        if (s.alt.isBlank()) listOf(preparedFor(s, s.norsk, i))
        else listOf(preparedFor(s, s.norsk, i), preparedFor(s, s.alt, i))
    }

/** One ranked result. [score] is descending-best (higher = better); not comparable across configs.
 *  [match] is the matched span in the original `norsk`, for highlighting (null when not reported). */
data class Ranked(val species: Species, val score: Double, val match: IntRange? = null)

/**
 * Tunable weights for [rankSpecies]. A `data class` so the benchmark can grid/random-search these
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
    val firstLetterSubseq: Double = 1_000.0,
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
 * Rank every candidate for [query], best-first. The single continuous score per species is
 * `tier × quality × likelihoodMult × diacriticFactor`, where:
 *  - tier (exact > prefix > known-suffix > suffix > initialism > interior substring > subsequence >
 *    capped typo) is the dominant term and can't be crossed by the others (see [TierWeights]),
 *  - [likelihood] is a per-species prior in [0,1] - "how likely is this the bird the user wants" -
 *    turned into a `[1, 2]` multiplier that reorders within a tier. The caller owns the recipe: plain
 *    national commonness, a season+location blend, a personal-use nudge, or a constant 1.0 when
 *    there's nothing to go on. rankSpecies itself has no notion of recents/history - to boost a user's
 *    regulars, fold that into [likelihood] (the app does, via blendedWeight).
 *  - a small diacritic-exact bonus rewards a match that holds on the real letters.
 *
 * [tierWeights] are the tunable tier bases + within-tier factors (defaults are the shipped values).
 * [suffixes] is the optional suffix-morpheme dictionary, supplied **already folded+stripped** (see
 * [fold]/[stripSep]); a query that *is* one of these and matches a name's ending is promoted to the
 * [TierWeights.knownSuffix] tier. Empty by default (the app passes none), so the plain ranking applies.
 */
fun rankSpecies(
    query: String,
    candidates: List<PreparedSpecies>,
    likelihood: (Species) -> Double,
    tierWeights: TierWeights = TierWeights(),
    suffixes: Set<String> = emptySet(),
): List<Ranked> {
    val fq = stripSep(foldQuery(query))
    val lq = stripSep(query.lowercase())
    return candidates
        .mapNotNull { c ->
            val tq = tierAndQuality(fq, c.foldedTight, tierWeights, suffixes) ?: return@mapNotNull null
            val likelihoodMult = 1.0 + tierWeights.freqStrength * likelihood(c.species)
            // Diacritic-exact: reward a match that holds on the real letters, i.e. without leaning on
            // folding. So "a" prefers Alke (real 'a') over Ærfugl, and a query that didn't reproduce a
            // name's æ/ø/å (or matched a part that has none) isn't penalised - it just doesn't get the
            // nudge. Keeps folding free while rewarding precision.
            val diacritic = if (matchesExact(lq, c.lowerTight)) 1.0 + tierWeights.diacriticExact else 1.0
            Ranked(c.species, tq.first * tq.second * likelihoodMult * diacritic)
        }
        .sortedWith(compareByDescending<Ranked> { it.score }.thenBy { it.species.norsk })
}

/** (tier base, quality factor in ~(0,1.x]) for [fq] against the folded name [fn], or null for no
 *  match. Cheaper structural tiers first; the capped typo tier is a last-resort net. */
private fun tierAndQuality(fq: String, fn: String, w: TierWeights, suffixes: Set<String>): Pair<Double, Double>? {
    if (fq.isEmpty()) return 1.0 to 1.0 // blank query -> rank by frequency alone
    if (fn == fq) return w.exact to 1.0
    val comp = fq.length.toDouble() / fn.length
    val compFactor = (1.0 - w.completeness) + w.completeness * comp
    if (fn.startsWith(fq)) return w.prefix to compFactor
    if (fn.endsWith(fq)) {
        // With a dictionary: only a real suffix morpheme earns the known-suffix tier; a coincidental
        // word-ending falls through to substring (this is what the dictionary buys us). Without one:
        // the generic "suffix beats interior" heuristic boosts every word-ending.
        if (fq in suffixes) return w.knownSuffix to compFactor
        if (suffixes.isEmpty()) return w.suffix to compFactor
    }
    if (fn.contains(fq)) return w.substring to compFactor
    val span = subsequenceSpan(fq, fn)
    if (span > 0) {
        // Anchored at the first letter = an initialism (pf -> Pilfink); its own tier above substring.
        // Scattered subsequences stay the lowest net.
        val base = if (fq[0] == fn[0]) w.firstLetterSubseq else w.subseq
        return base to fq.length.toDouble() / span
    }
    if (fq.length >= 4) { // typo net only once a query is long enough to be distinctive
        val cap = if (fq.length <= 5) 1 else 2
        // Treat the query as a typo'd *prefix*: compare to the same-length name slice (catches
        // substitution/transposition without charging for the untyped rest, e.g. "tybj" -> tyvjo) and
        // to a slightly longer slice (so a deletion still aligns). Take the closer.
        val dist = minOf(osaCapped(fq, fn.take(fq.length), cap), osaCapped(fq, fn.take(fq.length + cap), cap))
        if (dist <= cap) return w.typo to (1.0 - dist.toDouble() / fq.length)
    }
    return null
}

/** Whether the query matches on the real (diacritic-preserving) letters, so a match that doesn't need
 *  folding can be rewarded over one that does.
 *  DELIBERATE: this fires for diacritic-free names too (it's "matched without folding", NOT "name has
 *  æ/ø/å"). An earlier version required the name to carry a diacritic; that gave Rørsanger a spurious
 *  boost over the commoner Gransanger for "sanger" (the ø isn't in the matched part). The missing
 *  `foldedTight != lowerTight` guard is the fix, not a bug - don't add it back. */
private fun matchesExact(lq: String, lower: String) = lq.isNotEmpty() && lower.contains(lq)

/** Length of the left-greedy subsequence match of [q] in [t] (last index - first index + 1), or -1 if
 *  [q] isn't a subsequence. Smaller span = tighter (fewer gaps) = a better subsequence match. */
private fun subsequenceSpan(q: String, t: String): Int {
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

/** Optimal String Alignment distance (Damerau-Levenshtein restricted to adjacent transpositions - the
 *  most common typo), capped: returns the true distance if <= [cap], else [cap] + 1. Early-exits when
 *  an entire row exceeds the cap. The dataset is tiny and these strings are short, so the plain matrix
 *  is fast enough; the cap is what keeps it cheap. */
private fun osaCapped(a: String, b: String, cap: Int): Int {
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
