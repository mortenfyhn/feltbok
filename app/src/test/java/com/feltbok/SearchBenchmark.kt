package com.feltbok

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The search scoreboard - a first-class deliverable, not an afterthought. Runs every registered
 * [Scorers] candidate over the full labeled query set (synthetic + golden) and prints one comparison
 * table, so A/B-ing N ranking strategies is a single `./gradlew test --tests *SearchBenchmark` run.
 *
 * Pure JVM: no Android, no emulator. Reads the bundled checklist straight off disk.
 *
 * Metrics (per scorer):
 *  - K@1 / K@3: mean keystrokes-to-target - feed each query one char at a time, record the prefix
 *    length at which the target first enters top-1 / top-3. Weighted by commonness. Lower is better;
 *    this is the headline "minimal typing" number.
 *  - top1/3/5: accuracy on the full query. MRR: mean reciprocal rank.
 *  - p50/p99: scoring latency per full-query call, in microseconds.
 * All accuracy/keystroke metrics are commonness-weighted, so getting common birds right counts more.
 */
class SearchBenchmark {

    private val species: List<Species> by lazy { loadSpeciesFromDisk() }
    private val prepared: List<PreparedSpecies> by lazy { prepare(species) }

    /** Per-target weighting for every metric: the true (log-scaled) commonness, so getting common
     *  birds right counts more. Independent of which scorer is under test. */
    private val freq: FrequencyProvider by lazy { NumericFrequency(species) }
    private val names: Set<String> by lazy { species.map { it.norsk }.toSet() }

    /** The curated suffix morphemes, fed to the "morpheme" scorer config (see [loadSuffixes]). */
    private val suffixes: Set<String> by lazy { loadSuffixes() }

    /** Synthetic queries for every species, the morpheme queries (typing a real suffix from
     *  suffixes.txt, labelled to every bird that ends with it), plus the hand-curated golden rows.
     *  The MORPHEME set is the fair test for the "morpheme" scorer - unlike the synthetic SUFFIX
     *  class, these are genuine family morphemes, not arbitrary trailing slices. */
    private val cases: List<LabeledQuery> by lazy {
        val synthetic = species.flatMap { queriesFor(it.norsk) }
        val morpheme = suffixes.flatMap { suf ->
            val f = stripSep(fold(suf))
            prepared.filter { it.foldedTight != f && it.foldedTight.endsWith(f) }
                .map { LabeledQuery(suf, it.species.norsk, QueryKind.MORPHEME) }
        }
        val golden = loadGolden().mapNotNull { (q, exp) ->
            if (exp in names) LabeledQuery(q, exp, QueryKind.GOLDEN) else null
        }
        synthetic + morpheme + golden
    }

    @Test
    fun scoreboard() {
        require(species.size > 100) { "expected the real checklist; got ${species.size} species" }
        val scorers = Scorers.all(species, suffixes = suffixes)
        println(render(scorers.map { measure(it) }))
        // List the golden cases the ship candidate misses, so the checklist is actionable to curate.
        val ship = scorers.first { it.name == "tiered-freq" }
        val misses = cases.filter { it.kind == QueryKind.GOLDEN }.mapNotNull { qc ->
            val rank = ship.search(qc.text, prepared).indexOfFirst { it.species.norsk == qc.target }
            if (rank !in 0..2) "${qc.text} -> ${qc.target} (rank ${if (rank < 0) "none" else rank + 1})" else null
        }
        println("\ngolden top-3 misses for ${ship.name}:")
        println(if (misses.isEmpty()) "  none" else misses.joinToString("\n") { "  $it" })
    }

    /**
     * Grid-search tuner: tries every combination of a few [TierWeights] knobs and reports the ones
     * that maximise the prevalence-weighted top-3 (the realistic-mix objective). Heavy (~1 min), so
     * it's skipped by default - run it deliberately with (env var, so it reaches the forked test JVM):
     *   TUNE_SEARCH=1 ./gradlew testDebugUnitTest --tests "*SearchBenchmark.tuneWeights"
     * The winner is a *proposal*: eyeball its per-kind table (and golden, once seeded) before adopting.
     */
    @Test
    fun tuneWeights() {
        assumeTrue("set TUNE_SEARCH=1 to run the tuner", System.getenv("TUNE_SEARCH") != null)
        val base = TierWeights()
        val grid = ArrayList<Pair<TierWeights, Double>>()
        for (freqStrength in listOf(0.5, 1.0, 1.5, 2.0)) {
            for (completeness in listOf(0.2, 0.4, 0.6)) {
                for (anchored in listOf(500.0, 1000.0, 2000.0, 3000.0)) {
                    for (subseq in listOf(50.0, 100.0, 200.0)) {
                        val w = base.copy(
                            freqStrength = freqStrength, completeness = completeness,
                            anchoredSubseq = anchored, subseq = subseq,
                        )
                        grid += w to prevalenceTop3(TieredScorer(NumericFrequency(species), w))
                    }
                }
            }
        }
        grid.sortByDescending { it.second }
        val def = prevalenceTop3(TieredScorer(NumericFrequency(species), base))
        println("\nGrid search: ${grid.size} combos. default top3 = ${"%.3f".format(def)}")
        println("best 10:")
        for ((w, s) in grid.take(10)) {
            println(
                "  top3=%.3f  freqStrength=%.1f completeness=%.1f anchoredSubseq=%.0f subseq=%.0f".format(
                    s, w.freqStrength, w.completeness, w.anchoredSubseq, w.subseq,
                ),
            )
        }
        println("\nwinner, full metrics (verify no column regressed):")
        println(render(listOf(measure(TieredScorer(NumericFrequency(species), grid.first().first)))))
    }

    /** A correctness guard that should hold for any sane scorer: an exact full name ranks #1. */
    @Test
    fun exactNameRanksFirst() {
        val sample = listOf("Stokkand", "Gråmåke", "Rødvingetrost", "Pilfink")
            .filter { it in names }
        for (scorer in Scorers.all(species, suffixes = suffixes)) {
            for (n in sample) {
                val top = scorer.search(n, prepared).firstOrNull()?.species?.norsk
                assertEquals("${scorer.name}: exact '$n' should rank first", n, top)
            }
        }
    }

    // ---- measurement ----

    private data class Result(
        val name: String, val k1: Double, val k3: Double,
        val top1: Double, val top3: Double, val top5: Double, val mrr: Double,
        val p50us: Double, val p99us: Double,
        /** Weighted top-3 accuracy split by query kind, so we can see *where* a scorer wins/loses. */
        val perKindTop3: Map<QueryKind, Double>,
        /** Golden cases passing top-3, and the total - reported as a count, not blended in. */
        val goldPass: Int, val goldTotal: Int,
    )

    /** How much each typing pattern counts toward the headline - chosen to match real usage, NOT the
     *  accidental number of synthetic queries per kind. Each group is scored on its own accuracy, then
     *  blended by these weights, so generating more/fewer queries of a kind can't skew the headline.
     *  Tweak these to re-weight; GOLDEN is excluded (it's a separate checklist, see [render]). */
    private data class Group(val label: String, val weight: Double, val kinds: List<QueryKind>)
    private val prevalence = listOf(
        Group("start", 0.60, listOf(QueryKind.PREFIX)),
        Group(
            "mistype", 0.25,
            listOf(QueryKind.TYPO_DELETE, QueryKind.TYPO_SUBADJ, QueryKind.TYPO_TRANSPOSE, QueryKind.TYPO_DOUBLE, QueryKind.FOLDED),
        ),
        Group("initials", 0.08, listOf(QueryKind.SUBSEQ)),
        Group("suffix", 0.07, listOf(QueryKind.MORPHEME)),
    )

    /** Blend a per-kind metric into one number by prevalence: each group's metric is its own
     *  commonness-weighted average, then groups are combined by [Group.weight] (renormalized over
     *  groups that actually have cases). */
    private fun blend(perKind: Map<QueryKind, Double>, w: Map<QueryKind, Double>): Double {
        var num = 0.0
        var den = 0.0
        for (g in prevalence) {
            val gw = g.kinds.sumOf { w[it] ?: 0.0 }
            if (gw > 0.0) {
                num += g.weight * g.kinds.sumOf { perKind[it] ?: 0.0 } / gw
                den += g.weight
            }
        }
        return if (den > 0.0) num / den else 0.0
    }

    /** Fast objective for the tuner: prevalence-weighted top-3 on full queries only (no keystrokes),
     *  so it's cheap enough to evaluate hundreds of candidate weightings. */
    private fun prevalenceTop3(scorer: BirdSearchScorer): Double {
        val w = HashMap<QueryKind, Double>()
        val t3 = HashMap<QueryKind, Double>()
        for (qc in cases) {
            val ww = freq.weight(speciesByName(qc.target))
            val rank = scorer.search(qc.text, prepared).indexOfFirst { it.species.norsk == qc.target }
            w[qc.kind] = (w[qc.kind] ?: 0.0) + ww
            if (rank in 0..2) t3[qc.kind] = (t3[qc.kind] ?: 0.0) + ww
        }
        return blend(t3, w) // blend expects per-kind weighted sums (it divides by per-kind weight)
    }

    private fun measure(scorer: BirdSearchScorer): Result {
        val w = HashMap<QueryKind, Double>()
        val t1 = HashMap<QueryKind, Double>(); val t3 = HashMap<QueryKind, Double>(); val t5 = HashMap<QueryKind, Double>()
        val mrr = HashMap<QueryKind, Double>(); val kk1 = HashMap<QueryKind, Double>(); val kk3 = HashMap<QueryKind, Double>()
        var goldPass = 0; var goldTotal = 0
        val latencies = ArrayList<Long>(cases.size)
        fun add(m: HashMap<QueryKind, Double>, k: QueryKind, v: Double) { m[k] = (m[k] ?: 0.0) + v }
        for (qc in cases) {
            val ww = freq.weight(speciesByName(qc.target))
            val t0 = System.nanoTime()
            val ranked = scorer.search(qc.text, prepared)
            latencies += System.nanoTime() - t0
            val rank = ranked.indexOfFirst { it.species.norsk == qc.target } // -1 = absent
            add(w, qc.kind, ww)
            if (rank == 0) add(t1, qc.kind, ww)
            if (rank in 0..2) add(t3, qc.kind, ww)
            if (rank in 0..4) add(t5, qc.kind, ww)
            if (rank >= 0) add(mrr, qc.kind, ww / (rank + 1))
            add(kk1, qc.kind, ww * keystrokesToTop(scorer, qc, k = 1))
            add(kk3, qc.kind, ww * keystrokesToTop(scorer, qc, k = 3))
            if (qc.kind == QueryKind.GOLDEN) { goldTotal++; if (rank in 0..2) goldPass++ }
        }
        latencies.sort()
        fun pct(p: Double) = latencies[((latencies.size - 1) * p).toInt()] / 1000.0
        val perKindTop3 = QueryKind.entries.associateWith { (t3[it] ?: 0.0) / (w[it] ?: 1.0) }
        return Result(
            scorer.name, blend(kk1, w), blend(kk3, w),
            blend(t1, w), blend(t3, w), blend(t5, w), blend(mrr, w),
            pct(0.50), pct(0.99), perKindTop3, goldPass, goldTotal,
        )
    }

    /** Smallest prefix length of [qc].text at which the target enters the top [k]; a miss costs
     *  one more than the full query (so unreachable targets are penalised, not ignored). */
    private fun keystrokesToTop(scorer: BirdSearchScorer, qc: LabeledQuery, k: Int): Int {
        for (len in 1..qc.text.length) {
            val ranked = scorer.search(qc.text.take(len), prepared)
            val rank = ranked.indexOfFirst { it.species.norsk == qc.target }
            if (rank in 0 until k) return len
        }
        return qc.text.length + 1
    }

    private val byName by lazy { species.associateBy { it.norsk } }
    private fun speciesByName(norsk: String) = byName.getValue(norsk)

    private fun render(rows: List<Result>): String = buildString {
        appendLine()
        val mix = prevalence.joinToString(" ") { "${it.label}=${it.weight}" }
        appendLine("Search scoreboard  (${cases.size} queries / ${species.size} species)")
        appendLine("Headline metrics are blended by realistic typing mix: $mix  (lower K / higher acc = better)")
        appendLine("%-13s %6s %6s %6s %6s %6s %6s %8s %8s".format(
            "scorer", "K@1", "K@3", "top1", "top3", "top5", "MRR", "p50(µs)", "p99(µs)"))
        for (r in rows) appendLine("%-13s %6.2f %6.2f %5.1f%% %5.1f%% %5.1f%% %6.3f %8.1f %8.1f".format(
            r.name, r.k1, r.k3, r.top1 * 100, r.top3 * 100, r.top5 * 100, r.mrr, r.p50us, r.p99us))

        // Raw per-kind top-3 (NOT prevalence-weighted) - the diagnostic of where each scorer wins/loses.
        appendLine()
        appendLine("top-3 accuracy by query kind (% raw):")
        val abbr = linkedMapOf(
            QueryKind.PREFIX to "pre", QueryKind.MORPHEME to "morf", QueryKind.SUBSEQ to "subq",
            QueryKind.FOLDED to "fold", QueryKind.TYPO_DELETE to "del", QueryKind.TYPO_SUBADJ to "sub",
            QueryKind.TYPO_TRANSPOSE to "trsp", QueryKind.TYPO_DOUBLE to "dbl",
        )
        append("%-13s".format("scorer"))
        for (k in abbr.keys) append("%6s".format(abbr[k]))
        appendLine()
        for (r in rows) {
            append("%-13s".format(r.name))
            for (k in abbr.keys) append("%5.0f%%".format((r.perKindTop3[k] ?: 0.0) * 100))
            appendLine()
        }

        // Golden = a separate must-pass checklist, reported as a raw count so every case stays visible
        // (it's too small to move a blended aggregate - that's the point).
        appendLine()
        appendLine("golden checklist (top-3 pass, unweighted):")
        for (r in rows) appendLine("  %-13s %d/%d".format(r.name, r.goldPass, r.goldTotal))
    }

    // ---- data loading (off disk, no Android Context) ----

    private fun loadSpeciesFromDisk(): List<Species> {
        val f = listOf("src/main/assets/species.csv", "app/src/main/assets/species.csv")
            .map(::File).firstOrNull { it.exists() }
            ?: error("species.csv not found (cwd=${File(".").absolutePath})")
        return f.readLines().drop(1).filter { it.isNotBlank() }.mapNotNull { line ->
            val c = line.split(',')
            c[0].ifBlank { null }?.let {
                Species(it, c.getOrElse(1) { "" }, c.getOrElse(2) { "" }, c.getOrElse(3) { "" }.toIntOrNull() ?: 0)
            }
        }
    }

    private fun loadGolden(): List<Pair<String, String>> {
        val text = javaClass.getResource("/golden.csv")?.readText() ?: return emptyList()
        return text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { line ->
                val i = line.indexOf(',')
                if (i < 0) null else line.substring(0, i).trim() to line.substring(i + 1).trim()
            }
            .toList()
    }

    private fun loadSuffixes(): Set<String> {
        val text = javaClass.getResource("/suffixes.txt")?.readText() ?: return emptySet()
        return text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { it.split(Regex("\\s+")).first() } // first token only; allows trailing notes
            .toSet()
    }
}
