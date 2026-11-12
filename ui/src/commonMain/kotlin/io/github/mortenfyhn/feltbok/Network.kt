package io.github.mortenfyhn.feltbok

// The two web lookups: the co-observer user search (#128) and the live locality fetch (#173).
// URLs and parsing live here; the HTTP GET itself is per platform ([Platform.httpGet]).

private fun HttpResponse?.okBody() = this?.takeIf { it.status in 200..299 }?.body

private fun headers(vararg extra: Pair<String, String>) =
    mapOf("X-CSRF" to "1", "User-Agent" to "Feltbok/${AppInfo.versionName}") + extra

/** Search users by name (substring match, at most 15 hits). Empty on any failure - offline, or the
 *  undocumented endpoint changed - so the picker just falls back to free text. */
suspend fun searchAoUsers(platform: Platform, query: String): List<AoUser> {
    val base = Country.userSearchUrl ?: return emptyList()
    val json = platform.httpGet("$base?searchRequest=${uriEncode(query)}", headers(), timeoutMs = 5000).okBody()
    return json?.let { runCatching { parseAoUsers(it) }.getOrNull() } ?: emptyList()
}

/** ByBoundingBox rows for the box, or null on any failure (offline, endpoint changed). */
suspend fun fetchSites(platform: Platform, s: Double, w: Double, n: Double, e: Double): List<SiteRow>? {
    val base = Country.publicSitesUrl ?: return null
    val q = "MinX=$w&MinY=$s&MaxX=$e&MaxY=$n&MaxSites=$MAX_SITES&IncludePublicSites=true"
    val cookie = platform.siteCookie()
    val h = headers("Accept" to "application/json") + listOfNotNull(cookie?.let { "Cookie" to it })
    val json = platform.httpGet("$base?$q", h, timeoutMs = 10000).okBody()
    return json?.let { runCatching { siteRows(it) }.getOrNull() }
}

/** When the Artsobservasjoner session ends (epoch ms), or null when logged out or offline. An
 *  expired cookie doesn't fail ByBoundingBox (you just get the anonymous answer), so this is how
 *  to tell. */
suspend fun fetchSessionEnd(platform: Platform): Long? {
    val cookie = platform.siteCookie() ?: return null
    val json = platform.httpGet(Country.sessionUrl, headers("Accept" to "application/json", "Cookie" to cookie), timeoutMs = 5000).okBody()
    val left = json?.let { runCatching { parseSessionExpiresIn(it) }.getOrNull() } ?: return null
    return nowMs() + left * 1000
}

/** The user's own private localities (ByUser, all pages), or null on any failure: logged out,
 *  offline, or the endpoint changed. A session that /bff/user still accepts can be refused here
 *  (401/403, seen on a month-old login); then [onRejected] runs, since only a new login helps. */
suspend fun fetchMySites(platform: Platform, onRejected: () -> Unit = {}): List<Locality>? {
    val cookie = platform.siteCookie() ?: return null
    val h = headers("Accept" to "application/json", "Cookie" to cookie)
    val out = ArrayList<Locality>()
    var page = 1
    var total = 1
    while (page <= total) {
        val res = platform.httpGet("${Country.mySitesUrl}?pageSize=100&pageNumber=$page", h, timeoutMs = 10000)
        if (res?.status == 401 || res?.status == 403) onRejected()
        val json = res.okBody() ?: return null
        runCatching { out += parseMySites(json); total = parseTotalPages(json) }.getOrElse { return null }
        page++
    }
    return out
}
