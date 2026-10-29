package io.github.mortenfyhn.feltbok

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** A registered Artsobservasjoner user, as the co-observer search returns them. The import links a
 *  Medobservatør cell on [alias] ("brukeralias"): a bare name fails when two users share it, and the
 *  numeric user id isn't accepted at all (both found by test paste-import). */
data class AoUser(val name: String, val alias: String, val city: String)

fun parseAoUsers(json: String): List<AoUser> {
    val a = JSONArray(json)
    return (0 until a.length()).map { a.getJSONObject(it) }
        .map { AoUser(it.optString("name").trim(), it.optString("userAlias"), it.optString("city").trim()) }
        .filter { it.name.isNotEmpty() && it.alias.isNotEmpty() }
}

/** Search users by name (substring match, at most 15 hits). Empty on any failure - offline, or the
 *  undocumented endpoint changed - so the picker just falls back to free text. */
suspend fun searchAoUsers(query: String): List<AoUser> {
    val base = Country.userSearchUrl ?: return emptyList()
    return withContext(Dispatchers.IO) {
        runCatching {
            val c = URL("$base?searchRequest=${URLEncoder.encode(query, "UTF-8")}").openConnection() as HttpURLConnection
            c.connectTimeout = 5000
            c.readTimeout = 5000
            c.setRequestProperty("X-CSRF", "1")
            c.setRequestProperty("User-Agent", "Feltbok/${BuildConfig.VERSION_NAME}")
            try { parseAoUsers(c.inputStream.bufferedReader().readText()) } finally { c.disconnect() }
        }.getOrDefault(emptyList())
    }
}

/** The picker name to file [u] under: their plain name, unless that name is already in use - linked to
 *  someone else, or as free text in the list or on any note. Then the alias is added (unique, unlike
 *  the city: same-named users can share a town). Never reusing a free-text name means picking a user
 *  can't change what existing notes export as. */
fun coObsLabel(u: AoUser, linked: Map<String, AoUser>, inUse: Set<String>): String = when {
    linked[u.name]?.alias == u.alias -> u.name
    u.name !in linked && u.name !in inUse -> u.name
    else -> "${u.name} (${u.alias})"
}
