package io.github.mortenfyhn.feltbok

/**
 * Pure, Android-free logic behind co-observer support (#128), kept out of [MainViewModel] so it can
 * be unit-tested (see CoObserversTest). The sticky "følget mitt" party itself lives in
 * [MainViewModel] (it needs persistence, not logic).
 */

/** A registered Artsobservasjoner user, as the co-observer search returns them. The import links a
 *  Medobservatør cell on [alias] ("brukeralias"): a bare name fails when two users share it, and the
 *  numeric user id isn't accepted at all (both found by test paste-import). */
data class AoUser(val name: String, val alias: String, val city: String)

/** The picker's name list: the names you've used before, most-used first, unioned with whoever's on
 *  the draft right now (so a just-added free-text name shows up too). Deduped, keeping first (highest)
 *  occurrence. */
fun coObserverOptions(uses: Map<String, Int>, onDraft: List<String>): List<String> =
    (uses.entries.sortedByDescending { it.value }.map { it.key } + onDraft).distinct()

/** Whether the picker offers "Legg til «[query]»" as free text. It's the fallback: hidden while the
 *  user search runs (it would flash, then vanish), and when a saved name or a user from the search
 *  matches exactly, so a namesake gets picked by place and alias instead of typed in unlinked. */
fun canAddFreeText(query: String, searching: Boolean, saved: List<String>, hits: List<AoUser>): Boolean =
    query.isNotBlank() && !searching && saved.none { fold(it) == fold(query) } && hits.none { fold(it.name) == fold(query) }

/** Short form for the list-screen party header (#176): the first name only. Collisions ("two Annes")
 *  are accepted - the header is one tap from the picker, which spells out the full names. */
fun shortCoObsName(name: String): String = name.trim().substringBefore(' ')
