package io.github.mortenfyhn.feltbok

/**
 * Pure, Android-free logic behind co-observer support (#128), kept out of [MainViewModel] so it can
 * be unit-tested (see CoObserversTest). The sticky "følget mitt" party itself lives in
 * [MainViewModel] (it needs persistence, not logic).
 */

/** The picker's name list: the names you've used before, most-used first, unioned with whoever's on
 *  the draft right now (so a just-added free-text name shows up too). Deduped, keeping first (highest)
 *  occurrence. */
fun coObserverOptions(uses: Map<String, Int>, onDraft: List<String>): List<String> =
    (uses.entries.sortedByDescending { it.value }.map { it.key } + onDraft).distinct()

/** Short form for the list-screen party header (#176): the first name only. Collisions ("two Annes")
 *  are accepted - the header is one tap from the picker, which spells out the full names. */
fun shortCoObsName(name: String): String = name.trim().substringBefore(' ')
