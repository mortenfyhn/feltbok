package com.feltbok

/**
 * Deterministic synthetic query generator for the search benchmark.
 *
 * For each species we emit the queries a real user might type, each auto-labeled with its source
 * species, so we get thousands of labeled cases for free. Deterministic (no RNG): every typo lands
 * at a fixed position, so benchmark runs are reproducible and diffable. The synthetic set can be
 * overfit - the hand-curated golden set (golden.csv) is the reality check.
 */

/** What kind of typing a query simulates - so the scoreboard can break results down by class.
 *  GOLDEN is not generated here; it tags the hand-curated cases so they get their own column. */
enum class QueryKind { PREFIX, MORPHEME, SUBSEQ, FOLDED, TYPO_DELETE, TYPO_SUBADJ, TYPO_TRANSPOSE, TYPO_DOUBLE, GOLDEN }

data class LabeledQuery(val text: String, val target: String, val kind: QueryKind)

/**
 * Norwegian-QWERTY (Nordic ISO) key adjacency, for realistic wrong-key substitutions. Built from the
 * three letter rows; horizontal + (approximate) vertical neighbours. Note æ/ø/å sit far from a/o/e -
 * which is exactly why diacritics are folded for free rather than spent from the typo budget.
 */
object NorwegianQwerty {
    private val rows = listOf("qwertyuiopå", "asdfghjkløæ", "zxcvbnm")
    val adjacency: Map<Char, List<Char>> = buildMap {
        rows.forEachIndexed { r, row ->
            row.forEachIndexed { c, ch ->
                val n = mutableListOf<Char>()
                if (c > 0) n += row[c - 1]
                if (c < row.length - 1) n += row[c + 1]
                for (dr in intArrayOf(-1, 1)) rows.getOrNull(r + dr)?.getOrNull(c)?.let { n += it }
                put(ch, n)
            }
        }
    }
}

/** All synthetic queries for one species. Short names yield fewer; that's fine. */
fun queriesFor(norsk: String): List<LabeledQuery> {
    val name = norsk.lowercase()
    val out = ArrayList<LabeledQuery>()
    fun add(text: String, kind: QueryKind) {
        if (text.isNotEmpty() && text != name) out += LabeledQuery(text, norsk, kind)
    }

    // Growing prefixes - the most common pattern.
    for (len in 1..minOf(5, name.length - 1)) add(name.take(len), QueryKind.PREFIX)

    // No arbitrary trailing slices ("kand", "åke"): nobody types those. Real suffix-morpheme
    // lookup ("svale", "falk") is tested by the MORPHEME kind, built from suffixes.txt.

    // Initialism / subsequence: first letter plus evenly-spaced interior letters, in order.
    if (name.length >= 4) {
        val m = name.length
        add("${name[0]}${name[m / 2]}", QueryKind.SUBSEQ)
        add("${name[0]}${name[m / 3]}${name[2 * m / 3]}", QueryKind.SUBSEQ)
    }

    // ASCII-folded full name, for non-Norwegian keyboards (rodstrupe, blameise), plus the digraph
    // spelling people use without å/ø/æ (graagaas, roedstrupe) - both should resolve.
    val folded = fold(norsk)
    if (folded != name) add(folded, QueryKind.FOLDED)
    val digraph = name.replace("å", "aa").replace("ø", "oe").replace("æ", "ae")
    if (digraph != name) add(digraph, QueryKind.FOLDED)

    // Typo injections at the middle position (deterministic).
    if (name.length >= 4) {
        val mid = name.length / 2
        add(name.removeRange(mid, mid + 1), QueryKind.TYPO_DELETE)
        NorwegianQwerty.adjacency[name[mid]]?.firstOrNull()?.let {
            add(name.substring(0, mid) + it + name.substring(mid + 1), QueryKind.TYPO_SUBADJ)
        }
        if (mid + 1 < name.length) {
            val s = name.toCharArray(); val t = s[mid]; s[mid] = s[mid + 1]; s[mid + 1] = t
            add(String(s), QueryKind.TYPO_TRANSPOSE)
        }
        add(name.substring(0, mid + 1) + name[mid] + name.substring(mid + 1), QueryKind.TYPO_DOUBLE)
    }
    return out
}
