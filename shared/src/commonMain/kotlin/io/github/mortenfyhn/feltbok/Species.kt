package io.github.mortenfyhn.feltbok

// The species model and the name folding the search matches on. Platform-free so it can be shared
// with iOS (#115).

/** A display/search name language. The user picks a primary + optional secondary (#155); each
 *  flavor's export always uses its registry's language ([Country.exportLang]), independent of that. */
enum class Lang { NORSK, SVENSK, LATIN }

// Every species carries all three names (unified schema, no per-flavor special-casing). A name may
// be blank when a source lacks it (e.g. IOC has no Swedish name for a Norwegian-only vagrant); the
// display layer falls back to the flavor's own language, so a blank never surfaces as an empty row.
data class Species(
    val latin: String,
    val norsk: String = "",
    val svensk: String = "",
    val status: String = "",
    val count: Int = 0,
) {
    fun name(lang: Lang): String = when (lang) {
        Lang.NORSK -> norsk
        Lang.SVENSK -> svensk
        Lang.LATIN -> latin
    }
}

// ---- species search ----

/** Normalize a name for matching: just lowercase. No diacritics are folded — å/æ/ø are a keystroke
 *  away on the user's keyboard, so typing the real letter should match precisely ("må" surfaces the
 *  måker, not every "ma…" bird) and an ASCII stand-in ("ma" for "må") is left to fall through to the
 *  typo net like any other misspelling. (No bird name in the data carries a non-keyboard diacritic
 *  like é/ü, so there's nothing else to fold.) */
fun fold(s: String) = s.lowercase()

/** Fold a search query and drop its spaces, so "f m" == "fm". */
fun foldQuery(query: String) = fold(query).filterNot { it.isWhitespace() }
