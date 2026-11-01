package io.github.mortenfyhn.feltbok

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

// The text formats of the small settings files (use scores and counts, co-observers, the party,
// language prefs). Each parser throws on a malformed file; the platform's loader falls back.

/** uses.json: latin -> {s: score, t: last touched}. */
fun parseUses(text: String, now: Long): Map<String, UseEntry> =
    parseJson(text).jsonObject.mapValues { (_, v) ->
        when (v) {
            is JsonObject -> UseEntry(v.getDouble("s"), v.getLong("t"))
            // Legacy format was a bare count, no timestamp. Seed it a day back: clear of the pin
            // window (so migrated regulars aren't mistaken for the current batch), and the tiny
            // uniform decay doesn't change their relative order.
            else -> UseEntry(v.jsonPrimitive.double, now - 24L * 60 * 60 * 1000)
        }
    }

fun usesJson(uses: Map<String, UseEntry>): String =
    JsonObject(uses.mapValues { (_, e) -> buildJsonObject { put("s", e.score); put("t", e.lastTouched) } }).toString()

/** A name -> count map (act_uses.json, coobs_uses.json). */
fun parseCounts(text: String): Map<String, Int> = parseJson(text).jsonObject.let { o -> o.mapValues { (k, _) -> o.getInt(k) } }

fun countsJson(counts: Map<String, Int>): String = JsonObject(counts.mapValues { JsonPrimitive(it.value) }).toString()

/** coobs_users.json: users picked from the co-observer search, by brukeralias. */
fun parseCoObsUsers(text: String): Map<String, AoUser> =
    parseJson(text).jsonArray.map { it.jsonObject }
        .map { AoUser(it.getString("name"), it.getString("alias"), it.optString("city")) }
        .associateBy { it.alias }

fun coObsUsersJson(users: Collection<AoUser>): String =
    JsonArray(users.map { buildJsonObject { put("name", it.name); put("alias", it.alias); put("city", it.city) } }).toString()

/** party.json: the current field party ("følget mitt"). */
fun parseParty(text: String): List<String> = parseJson(text).jsonArray.map { it.jsonPrimitive.content }

fun partyJson(party: List<String>): String = JsonArray(party.map(::JsonPrimitive)).toString()

// ---- species-name language preference (#155): which language to show as primary + secondary ----

/** The two display languages shown for each species: [primary] on top, [secondary] beneath. Both are
 *  always set (there's no "no secondary" - Latin fills that role); a secondary equal to the primary
 *  just isn't drawn. Search matches the [primary] name only, unless [searchSecondary] is on, which
 *  also searches the secondary language (a fallback for "I forget the primary-language name"); it's
 *  off by default so, e.g., typing "t" in the Norway build doesn't surface birds via their Swedish
 *  name. None of this affects export - that stays the registry's export language. */
data class LangPrefs(val primary: Lang, val secondary: Lang, val searchSecondary: Boolean = false) {
    /** The name languages the search matches against, given these prefs. */
    val searchLangs: Set<Lang> get() = setOf(primary) + if (searchSecondary) setOf(secondary) else emptySet()
}

/** lang_prefs.json, with a missing secondary or toggle taken from [default]. */
fun parseLangPrefs(text: String, default: LangPrefs): LangPrefs {
    val o = parseJson(text).jsonObject
    return LangPrefs(
        Lang.valueOf(o.getString("primary")),
        o.optString("secondary").takeIf { it.isNotBlank() }?.let { Lang.valueOf(it) } ?: default.secondary,
        o.optBoolean("searchSecondary", default.searchSecondary),
    )
}

fun langPrefsJson(prefs: LangPrefs): String = buildJsonObject {
    put("primary", prefs.primary.name)
    put("secondary", prefs.secondary.name)
    put("searchSecondary", prefs.searchSecondary)
}.toString()
