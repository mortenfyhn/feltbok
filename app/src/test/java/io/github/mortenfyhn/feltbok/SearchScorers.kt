package io.github.mortenfyhn.feltbok

/**
 * Benchmark-only search scaffolding: the pluggable [BirdSearchScorer] interface, the shipping ranker
 * wrapped as one candidate ([TieredScorer]), and the comparison candidates it's A/B'd against
 * (baseline tiers, Jaro-Winkler). None of this ships - the app calls [rankSpecies] directly; this
 * exists so `SearchBenchmark` can enumerate, select, and score N strategies against each other.
 */

/** A named ranking strategy. Each implementation scores all [candidates] for [query] and returns them
 *  best-first (callers take the top N). Any frequency signal is baked in at construction (so two
 *  configs can differ only by their commonness weight). */
interface BirdSearchScorer {
    val name: String
    fun search(query: String, candidates: List<PreparedSpecies>): List<Ranked>
}

/**
 * The shipping ranker ([rankSpecies]) as a benchmark candidate. [suffixes] is the optional
 * suffix-morpheme dictionary; pre-folded once here so the per-keystroke cost stays flat, the way the
 * app would precompute it.
 */
class TieredScorer(
    private val freq: (Species) -> Double,
    private val w: TierWeights = TierWeights(),
    suffixes: Set<String> = emptySet(),
    override val name: String = "tiered",
) : BirdSearchScorer {
    private val suffixes = suffixes.map { stripSep(fold(it)) }.toSet()
    override fun search(query: String, candidates: List<PreparedSpecies>): List<Ranked> =
        rankSpecies(query, candidates, freq, w, suffixes)
}

/**
 * The app's *old* behavior, kept as a benchmark baseline: [fuzzyRank] tiers (prefix > initials >
 * substring > subsequence), ties broken by the user's own [useCount] then the most-common-first source
 * order. Frequency here is implicit in the candidate order.
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
 * Rank an already-folded [q] (see [foldQuery]) against an already-folded [t] (see [fold]). Lower is
 * better, or null for no match:
 *  0 = prefix (name starts with the query)
 *  1 = name's first letter matches and the rest is a subsequence - the "initials" case, so
 *      "pf" -> Pilfink beats a mid-word match like Lappfiskand
 *  2 = the query is a contiguous substring elsewhere in the name
 *  3 = the query is a scattered subsequence elsewhere
 */
fun fuzzyRank(q: String, t: String): Int? {
    if (q.isEmpty()) return 0
    if (t.startsWith(q)) return 0
    var qi = 0
    for (c in t) {
        if (c == q[qi]) qi++
        if (qi == q.length) break
    }
    val isSubseq = qi == q.length
    val anchored = q[0] == t.firstOrNull()
    return when {
        anchored && isSubseq -> 1
        t.contains(q) -> 2
        isSubseq -> 3
        else -> null
    }
}

/** Commonness from the source list's most-common-first ordering: rank 0 -> ~1.0, last -> ~0. Ignores
 *  the real (very skewed) magnitudes; [NumericFrequency] uses those when available. */
class RowOrderFrequency(species: List<Species>) {
    private val n = species.size.coerceAtLeast(1)
    private val rank = species.withIndex().associate { (i, s) -> s.norsk to i }
    fun weight(species: Species): Double {
        val i = rank[species.norsk] ?: (n - 1)
        return (n - i).toDouble() / n
    }
}

/**
 * A library-style comparison candidate: rank by Jaro-Winkler similarity (× frequency). JW has a
 * built-in common-prefix boost, which suits the prefix-heavy way people type, and it's inherently
 * typo-tolerant - so it needs no explicit tier ladder. We score against both the whole name and its
 * leading slice (so a clean prefix scores ~1.0), and take the max. The benchmark says whether this
 * single-similarity approach can rival the hand-tuned tier ladder; it has no notion of "suffix" or
 * "family", so we expect it to trail on those, and that contrast is the point of having it.
 */
class JaroWinklerScorer(private val freq: (Species) -> Double, override val name: String = "jaro-winkler") :
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
                Ranked(c.species, sim * (1.0 + freq(c.species)))
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

/** Registry of all candidates, so the benchmark enumerates a fixed set. [useCount] is the personal-
 *  frequency hook; the benchmark passes the default (no history). */
object Scorers {
    fun all(
        species: List<Species>,
        useCount: (String) -> Int = { 0 },
        suffixes: Set<String> = emptySet(),
    ): List<BirdSearchScorer> = buildList {
        add(BaselineScorer(useCount))
        add(TieredScorer(RowOrderFrequency(species)::weight, name = "tiered"))
        add(TieredScorer(NumericFrequency(species)::weight, name = "tiered-freq"))
        add(JaroWinklerScorer(NumericFrequency(species)::weight))
        // The morpheme-dictionary candidate: only meaningful with a suffixes list supplied.
        if (suffixes.isNotEmpty()) {
            add(TieredScorer(NumericFrequency(species)::weight, suffixes = suffixes, name = "morpheme"))
        }
    }

    fun byName(
        name: String,
        species: List<Species>,
        useCount: (String) -> Int = { 0 },
        suffixes: Set<String> = emptySet(),
    ): BirdSearchScorer = all(species, useCount, suffixes).first { it.name == name }
}
