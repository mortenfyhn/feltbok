package io.github.mortenfyhn.feltbok

// The two web lookups: the co-observer user search (#128) and the live locality fetch (#173).
// URLs and parsing live here; the HTTP GET itself is per platform ([Platform.httpGet]).

private fun headers(vararg extra: Pair<String, String>) =
    mapOf("X-CSRF" to "1", "User-Agent" to "Feltbok/${AppInfo.versionName}") + extra

/** Search users by name (substring match, at most 15 hits). Empty on any failure - offline, or the
 *  undocumented endpoint changed - so the picker just falls back to free text. */
suspend fun searchAoUsers(platform: Platform, query: String): List<AoUser> {
    val base = Country.userSearchUrl ?: return emptyList()
    val json = platform.httpGet("$base?searchRequest=${uriEncode(query)}", headers(), timeoutMs = 5000)
    return json?.let { runCatching { parseAoUsers(it) }.getOrNull() } ?: emptyList()
}

/** ByBoundingBox rows for the box, or null on any failure (offline, endpoint changed). */
suspend fun fetchSites(platform: Platform, s: Double, w: Double, n: Double, e: Double): List<SiteRow>? {
    val base = Country.publicSitesUrl ?: return null
    val q = "MinX=$w&MinY=$s&MaxX=$e&MaxY=$n&MaxSites=$MAX_SITES&IncludePublicSites=true"
    val json = platform.httpGet("$base?$q", headers("Accept" to "application/json"), timeoutMs = 10000)
    return json?.let { runCatching { siteRows(it) }.getOrNull() }
}
