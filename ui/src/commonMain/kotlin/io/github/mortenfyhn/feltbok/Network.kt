package io.github.mortenfyhn.feltbok

// The web lookups: the co-observer user search (#128), the live locality fetch (#173), the user's
// own localities, and place-name suggestions (#137).
// URLs and parsing live here; the HTTP GET itself is per platform ([Platform.httpGet]).

private fun HttpResult.okBody() = (this as? HttpResponse)?.takeIf { it.status in 200..299 }?.body

/** The site refused the session: only a new login helps. */
val HttpResult.rejected get() = this is HttpResponse && (status == 401 || status == 403)

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

/** True when the site certainly won't take an export: no login, or the site refused it. Offline
 *  is not refused, so the export can still try and say what went wrong. */
suspend fun loginRefused(platform: Platform): Boolean {
    val cookie = platform.siteCookie() ?: return true
    return platform.httpGet(Country.sessionUrl, headers("Accept" to "application/json", "Cookie" to cookie), timeoutMs = 5000).rejected
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
 *  offline, or the endpoint changed. [onFailed] gets a failed request; a session that /bff/user
 *  still accepts can be [rejected] here (seen on a month-old login). */
suspend fun fetchMySites(platform: Platform, onFailed: (HttpResult) -> Unit = {}): List<Locality>? {
    val cookie = platform.siteCookie() ?: return null
    val h = headers("Accept" to "application/json", "Cookie" to cookie)
    val out = ArrayList<Locality>()
    var page = 1
    var total = 1
    while (page <= total) {
        val res = platform.httpGet("${Country.mySitesUrl}?pageSize=100&pageNumber=$page", h, timeoutMs = 10000)
        val json = res.okBody() ?: return null.also { onFailed(res) }
        runCatching { out += parseMySites(json); total = parseTotalPages(json) }.getOrElse { return null }
        page++
    }
    return out
}

/** Ranked place names near a point, for naming a new locality (#137). Empty on any failure. */
suspend fun fetchPlaceNames(platform: Platform, lat: Double, lon: Double): List<PlaceSuggestion> {
    val url = "https://api.kartverket.no/stedsnavn/v1/punkt?nord=$lat&ost=$lon&koordsys=4258&utkoordsys=4258" +
        "&radius=$PLACE_NAME_RADIUS_M&treffPerSide=500&side=1"
    val json = platform.httpGet(url, mapOf("User-Agent" to "Feltbok/${AppInfo.versionName}"), timeoutMs = 5000).okBody()
    return json?.let { runCatching { rankPlaceNames(parsePlaceNames(it)) }.getOrNull() } ?: emptyList()
}

// The template's limit for both comment fields ("Maks 254 tegn").
private const val MAX_COMMENT = 254

/** Why an upload didn't start, or stopped before the last note. */
sealed interface Interruption {
    /** Only a new login helps. */
    data object LoggedOut : Interruption

    /** Offline, or the site didn't answer: try again. */
    data class Failed(val reason: String) : Interruption
}

/** How an upload ended. [Blocked] and [NotStarted] sent nothing. */
sealed interface UploadResult {
    data class NotStarted(val why: Interruption) : UploadResult

    /** Nothing sent: these notes (by id) need fixing first. */
    data class Blocked(val problems: Map<Long, String>) : UploadResult

    /** Sent; [failed] (by id) are the notes the server refused, and [mismatched] the ones it stored
     *  differently from the note (uploaded all the same). [stopped] says why it stopped early. */
    data class Done(
        val sent: Int,
        val failed: Map<Long, String>,
        val mismatched: Map<Long, String>,
        val stopped: Interruption? = null,
    ) : UploadResult
}

/** Where the export screen's upload is. One value, so the screen can't see a half-updated state. */
sealed interface UploadState {
    data object Idle : UploadState

    /** [sentBefore]: how many this visit had sent when it started, so the button keeps its label. */
    data class Running(val sentBefore: Int) : UploadState
    data class Finished(val result: UploadResult) : UploadState
}

/** Uploads [notes] to the review page (POST /core/Sightings), one by one. Everything that can be checked is checked first, so a problem blocks the whole
 *  upload before anything is sent. [taxonIds] are the bundled taxon ids by scientific name. [users] are the
 *  co-observers picked from the user search, by brukeralias; anyone else on a note was typed in, and
 *  goes into the public comment. Ids missing from either are looked up. [onSent] gets each uploaded
 *  note and its sighting id as it lands, for the caller to keep. */
suspend fun uploadNotes(
    platform: Platform,
    notes: List<Note>,
    taxonIds: Map<String, Long>,
    users: Map<String, AoUser>,
    onSent: (Note, Long) -> Unit,
): UploadResult {
    val isUser = { s: String -> s in users }
    val cookie = platform.siteCookie() ?: return UploadResult.NotStarted(Interruption.LoggedOut)
    // Asks for the site's rejection reasons in Norwegian, if it has them.
    val h = headers("Accept" to "application/json", "Cookie" to cookie, "Accept-Language" to "nb-NO,nb;q=0.9,no;q=0.8")
    val get = suspend { url: String -> platform.httpGet(url, h, timeoutMs = 10000) }
    val session = get(Country.sessionUrl)
    val me = session.okBody()?.let(::parseUserId)
        ?: return UploadResult.NotStarted(if (session.rejected) Interruption.LoggedOut else Interruption.Failed(Strings.Upload.failed(session)))

    // Co-observers who aren't users can't be linked, so they go into the public comment.
    val publicComment = { n: Note ->
        n.coObservers.filterNot(isUser).let { if (it.isEmpty()) n.publicComment else Strings.Upload.withCoObservers(n.publicComment, it) }
    }
    val now = nowMs()
    val problems = LinkedHashMap<Long, String>()
    notes.forEach { n -> uploadProblem(n, now)?.let { problems[n.id] = Strings.Upload.problem(it, n) } }
    // Look up what the notes refer to. A lookup that gets no answer stops here; one that answers
    // "not found" is the note's problem. A note that passes gets its request body.
    val taxa = HashMap<String, Long?>(taxonIds)
    val userIds = HashMap<String, Long?>(users.mapNotNull { (alias, u) -> u.id?.let { alias to it } }.toMap())
    val bodies = ArrayList<Pair<Note, String>>()
    for (n in notes.filter { it.id !in problems }) {
        if (n.latin !in taxa) {
            val r = get("${Country.sitesHost}/core/TaxonName/Search?search=${uriEncode(n.latin)}&includeSubSpecies=true")
            if (r !is HttpResponse) return UploadResult.NotStarted(Interruption.Failed(Strings.Upload.failed(r)))
            taxa[n.latin] = r.okBody()?.let { parseTaxonId(it, n.latin) }
        }
        for (alias in n.coObservers.filter(isUser).filter { it !in userIds }) {
            val r = get("${Country.sitesHost}/core/Users/ByUserAlias?userAlias=${uriEncode(alias)}")
            if (r !is HttpResponse) return UploadResult.NotStarted(Interruption.Failed(Strings.Upload.failed(r)))
            userIds[alias] = r.okBody()?.let(::parseId)
        }
        val taxon = taxa[n.latin]
        val coUsers = n.coObservers.filter(isUser)
        val missing = coUsers.filter { userIds[it] == null }
        when {
            taxon == null -> problems[n.id] = Strings.Upload.unknownSpecies
            missing.isNotEmpty() -> problems[n.id] = Strings.Upload.unknownCoObserver(missing.map { users[it]?.name ?: it })
            n.privateComment.length > MAX_COMMENT -> problems[n.id] = Strings.Upload.privateTooLong
            n.publicComment.length > MAX_COMMENT -> problems[n.id] = Strings.Upload.publicTooLong
            publicComment(n).length > MAX_COMMENT -> problems[n.id] = Strings.Upload.publicTooLongWithCoObservers
            else -> bodies += n to sightingJson(n, taxon, listOf(me) + coUsers.mapNotNull { userIds[it] }, publicComment(n))
        }
    }
    if (problems.isNotEmpty()) return UploadResult.Blocked(problems)

    val failed = LinkedHashMap<Long, String>()
    val mismatched = LinkedHashMap<Long, String>()
    var sent = 0
    for ((n, body) in bodies) {
        val r = platform.httpPostJson("${Country.sitesHost}/core/Sightings", h, body, timeoutMs = 15000)
        val answer = r.okBody()
        val id = answer?.let(::parseId)
        if (id != null) {
            // Kept even when it doesn't match: the sighting exists, and a retry would duplicate it.
            onSent(n, id)
            sent++
            sightingMismatches(body, answer).takeIf { it.isNotEmpty() }?.let { mismatched[n.id] = Strings.Upload.mismatch(it) }
        } else if (r is HttpResponse && r.status != 401) {
            failed[n.id] = parseErrorDetail(r.body)?.let(Strings.Upload::rejected) ?: Strings.Upload.failed(r)
        } else {
            // Logged out or no answer: the rest would fail the same way. Stop, and the next try
            // sends what's left.
            return UploadResult.Done(sent, failed, mismatched, if (r is HttpResponse) Interruption.LoggedOut else Interruption.Failed(Strings.Upload.lost(r)))
        }
    }
    return UploadResult.Done(sent, failed, mismatched)
}
