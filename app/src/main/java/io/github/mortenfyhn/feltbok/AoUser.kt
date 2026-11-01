package io.github.mortenfyhn.feltbok

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

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
