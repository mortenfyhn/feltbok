package io.github.mortenfyhn.feltbok

/**
 * Pure, Android-free logic behind co-observer support (#128), kept out of [MainViewModel] so it can
 * be unit-tested (see CoObserversTest) - the sticky-party rule is the one non-obvious bit. The
 * ViewModel owns the observable state and delegates the maths here.
 */

/** The sticky "følget mitt" after a save: a newly *created* observation's co-observers become the
 *  party, so the next new observation inherits them ("everything for this trip is me + these
 *  people"). Editing an *existing* note leaves the party untouched - going back to fix an old note's
 *  co-observers must not hijack today's party (and the VM can't tell the just-created note from a
 *  week-old one). Returns the party to persist. */
fun stickyPartyAfterSave(current: List<String>, savedCoObservers: List<String>, isEditing: Boolean): List<String> =
    if (isEditing) current else savedCoObservers

/** The picker's name list: the names you've used before, most-used first, unioned with whoever's on
 *  the draft right now (so a just-added free-text name shows up too). Deduped, keeping first (highest)
 *  occurrence. */
fun coObserverOptions(uses: Map<String, Int>, onDraft: List<String>): List<String> =
    (uses.entries.sortedByDescending { it.value }.map { it.key } + onDraft).distinct()
