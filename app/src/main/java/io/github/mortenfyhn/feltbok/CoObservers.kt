package io.github.mortenfyhn.feltbok

/**
 * Pure, Android-free logic behind co-observer support (#128), kept out of [MainViewModel] so it can
 * be unit-tested (see CoObserversTest). The sticky "følget mitt" party needs no logic here: it is
 * derived - simply the newest note's co-observers (see MainViewModel.party()).
 */

/** The picker's name list: the names you've used before, most-used first, unioned with whoever's on
 *  the draft right now (so a just-added free-text name shows up too). Deduped, keeping first (highest)
 *  occurrence. */
fun coObserverOptions(uses: Map<String, Int>, onDraft: List<String>): List<String> =
    (uses.entries.sortedByDescending { it.value }.map { it.key } + onDraft).distinct()
