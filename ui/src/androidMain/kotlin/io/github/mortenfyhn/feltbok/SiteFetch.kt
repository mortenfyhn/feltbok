package io.github.mortenfyhn.feltbok

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

// The network side of the live locality lookup (#173); the logic lives in LiveSites.kt.

/** ByBoundingBox rows for the box, or null on any failure (offline, endpoint changed). */
suspend fun fetchSites(s: Double, w: Double, n: Double, e: Double): List<SiteRow>? {
    val base = Country.publicSitesUrl ?: return null
    return withContext(Dispatchers.IO) {
        runCatching {
            val q = "MinX=$w&MinY=$s&MaxX=$e&MaxY=$n&MaxSites=$MAX_SITES&IncludePublicSites=true"
            val c = URL("$base?$q").openConnection() as HttpURLConnection
            c.connectTimeout = 5000
            c.readTimeout = 10000
            c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty("X-CSRF", "1")
            c.setRequestProperty("User-Agent", "Feltbok/${AppInfo.versionName}")
            try { siteRows(c.inputStream.bufferedReader().readText()) } finally { c.disconnect() }
        }.getOrNull()
    }
}
