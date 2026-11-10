package io.github.mortenfyhn.feltbok

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Pure, Android-free logic behind co-observer support (#128), kept out of [MainViewModel] so it can
 * be unit-tested (see CoObserversTest). The sticky "følget mitt" party itself lives in
 * [MainViewModel] (it needs persistence, not logic).
 */

/** A registered Artsobservasjoner user, as the co-observer search returns them. The import links a
 *  Medobservatør cell on [alias] ("brukeralias"): a bare name fails when two users share it, and the
 *  numeric user id isn't accepted at all (both found by test paste-import). */
data class AoUser(val name: String, val alias: String, val city: String)

fun parseAoUsers(json: String): List<AoUser> {
    return parseJson(json).jsonArray.map { it.jsonObject }
        .map { AoUser(it.optString("name").trim(), it.optString("userAlias"), it.optString("city").trim()) }
        .filter { it.name.isNotEmpty() && it.alias.isNotEmpty() }
}

/** The picker's name list: the names you've used before, unioned with whoever's on the draft right
 *  now (so a just-added free-text name shows up too), deduped. Alphabetical by surname, taken as the
 *  last word of the name shown ([name] turns a stored alias into the user's name), then by the whole
 *  name - so a long list is easy to scan and doesn't reorder as use counts change (#191). */
fun coObserverOptions(uses: Map<String, Int>, onDraft: List<String>, name: (String) -> String): List<String> =
    (uses.keys + onDraft).distinct().sortedWith(
        compareBy({ norwegianSortKey(name(it).trim().substringAfterLast(' ')) }, { norwegianSortKey(name(it)) }),
    )

/** A sort key for Norwegian alphabetical order, ignoring case: æ, ø, å after z, in that order (plain
 *  string order puts å first). ä and ö sort as æ and ø, as in Norwegian dictionaries. */
fun norwegianSortKey(s: String): String = s.lowercase().map {
    when (it) {
        'æ', 'ä' -> 'z' + 1
        'ø', 'ö' -> 'z' + 2
        'å' -> 'z' + 3
        else -> it
    }
}.joinToString("")

/** The aliases among [users] that the picker must show to tell namesakes apart: those sharing both
 *  name and place with another listed user (case-insensitive). Where the place differs, it does the
 *  job, and a brukeralias isn't shown needlessly - it can look odd, and isn't obviously public. */
fun needsAlias(users: List<AoUser>): Set<String> =
    users.distinctBy { it.alias }.groupBy { fold(it.name.trim()) to fold(it.city.trim()) }
        .values.filter { it.size > 1 }.flatten().map { it.alias }.toSet()

/** Whether the picker offers "Legg til «[query]»" as free text. It's the fallback: hidden while the
 *  user search runs (it would flash, then vanish), and when a saved name or a user from the search
 *  matches exactly, so a namesake gets picked by place and alias instead of typed in unlinked. */
fun canAddFreeText(query: String, searching: Boolean, saved: List<String>, hits: List<AoUser>): Boolean =
    query.isNotBlank() && !searching && saved.none { fold(it) == fold(query) } && hits.none { fold(it.name) == fold(query) }

/** Short form for the list-screen party header (#176): the first name only. Collisions ("two Annes")
 *  are accepted - the header is one tap from the picker, which spells out the full names. */
fun shortCoObsName(name: String): String = name.trim().substringBefore(' ')
