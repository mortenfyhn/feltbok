package io.github.mortenfyhn.feltbok

import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.time.Instant

// Direct upload to Artsobservasjoner's review page ("mine upubliserte funn") through the mobile
// API's single-sighting endpoint, POST /core/Sightings - the one its own entry form uses. Unlike
// the paste import it takes the exact site id, so a public site can't be mixed up with a private
// one of the same name.

/** Ids of the bird (species group 8) choices, from /lists/Lists/GetAllLists?languageCode=nb. The
 *  labels are the app's own terms, letter for letter. */
object BirdLists {
    val stages = mapOf("Egg" to 1, "Pulli" to 2, "Adult" to 23, "1K" to 3, "1K+" to 4, "2K" to 5, "2K+" to 6, "2K-" to 7, "3K" to 8, "3K+" to 9, "3K-" to 10, "4K" to 11, "4K+" to 12, "4K-" to 13, "5K" to 14, "5K+" to 15, "5K-" to 16, "6K" to 17, "6K+" to 18, "6K-" to 19, "7K" to 20, "7K+" to 21, "7K-" to 22)
    val genders = mapOf("Hann" to 1, "Hunn" to 2, "Hunnfarget" to 3, "I par" to 4)
    val activities = mapOf("Reir med egg eller unger" to 1, "Reir, unger hørt" to 2, "Rugende" to 4, "Mat til unger" to 6, "Bar ekskrementpose" to 7, "Reir i bruk" to 121, "Besøker bebodd reir" to 8, "Unger utenfor reir, ikke utvokste" to 9, "Brukt reir" to 10, "Eggeskall" to 5, "Avledningsmanøver" to 11, "Mislykket hekking" to 3, "Reirbygging" to 12, "Rugeflekker" to 13, "Engstelig adferd, indikasjon på hekking" to 14, "Reirbesøk?" to 15, "Paring/kurtise på mulig hekkeplass" to 16, "Permanent revir" to 17, "Par i passende hekkebiotop" to 18, "Sang/spill i hekketid og passende hekkebiotop" to 19, "Observasjon i hekketid, passende biotop" to 20, "Rastende" to 22, "Stasjonær" to 23, "Overflygende" to 24, "Næringssøkende" to 25, "Ved fôring" to 122, "Sang/spill, ikke hekking" to 26, "Lokkelyd, øvrige lyder" to 27, "Revir, ikke hekking" to 28, "Ringmerket" to 29, "Individmerket (kontroll)" to 30, "Syk" to 65, "Trekkforsøk" to 31, "Trekkende" to 32, "Trekkende mot N" to 33, "Trekkende mot NØ" to 34, "Trekkende mot Ø" to 35, "Trekkende mot SØ" to 36, "Trekkende mot S" to 37, "Trekkende mot SV" to 38, "Trekkende mot V" to 39, "Trekkende mot NV" to 40, "Ferske spor" to 42, "Eldre spor" to 43, "Fersk møkk" to 44, "Eldre møkk" to 45, "Død - kollisjon med kraftledning" to 68, "Død - kollisjon med vindturbin" to 69, "Død - kollisjon med vindu" to 70, "Død - kollisjon med fyr" to 71, "Død - kollisjon med fly" to 73, "Død - kollisjon med gjerde" to 74, "Drept av elektrokusjon (strømslag)" to 75, "Drept av olje" to 102, "Trafikkdrept" to 72, "Garndød" to 76, "Skadet av fiskeredskap" to 123, "Drept av predator" to 77, "Død av sykdom/sult" to 78, "Skutt/avlivet" to 96, "Død - ukjent dødsårsak" to 41)
}

// The API takes a date as the instant of local midnight (the web form sends Date.toISOString()).
private fun dayStart(ms: Long, zone: TimeZone): String =
    Instant.fromEpochMilliseconds(ms).toLocalDateTime(zone).date.atStartOfDayIn(zone).toString()

/** The POST /core/Sightings body for [n]. [observerIds] must include the reporter: unlike the
 *  paste import, this endpoint doesn't add them. [comment] replaces the public comment (to add
 *  co-observers who aren't users). A note without a site id gets siteId 0, which only a brand-new
 *  spot (sent as newSiteInfo) may have. */
fun sightingJson(
    n: Note,
    taxonId: Long,
    observerIds: List<Long>,
    comment: String = n.publicComment,
    zone: TimeZone = TimeZone.currentSystemDefault(),
): String {
    val end = n.endTime ?: n.time
    return buildJsonObject {
        put("taxonId", taxonId)
        put("siteId", if (n.newLoc) 0L else n.locId.toLongOrNull() ?: 0L)
        if (n.newLoc) put("newSiteInfo", buildJsonObject {
            put("siteName", n.locName)
            put("latitude", n.lat)
            put("longitude", n.lon)
            put("accuracy", n.locRadius)
            put("isPolygon", false)
        })
        put("observerIds", JsonArray(observerIds.map { JsonPrimitive(it) }))
        put("quantity", if (n.count == UNKNOWN_COUNT) JsonNull else JsonPrimitive(n.count))
        BirdLists.stages[n.age]?.let { put("stageId", it) }
        BirdLists.genders[n.sex]?.let { put("genderId", it) }
        BirdLists.activities[n.activity]?.let { put("activityId", it) }
        put("startDate", dayStart(n.time, zone))
        put("endDate", dayStart(end, zone))
        put("startTime", if (n.timeUnknown) null else shortTime(n.time, zone))
        put("endTime", if (n.timeUnknown) null else shortTime(end, zone))
        put("comment", comment)
        put("privateComment", n.privateComment)
        if (n.uncertain) put("unsureDetermination", true)
        put("hiddenByProvider", n.hideUntil?.let { dayStart(it, zone) })
    }.toString()
}

/** Why a note can't be uploaded as it is, found before anything is sent. Mirrors the server's
 *  checks where the app can break them, so an upload doesn't stop halfway. */
enum class UploadProblem { NO_SPECIES, NO_SITE, UNKNOWN_AGE, UNKNOWN_SEX, UNKNOWN_ACTIVITY, ODD_PAIR, FUTURE, END_BEFORE_START }

fun uploadProblem(n: Note, now: Long, zone: TimeZone = TimeZone.currentSystemDefault()): UploadProblem? {
    val end = n.endTime ?: n.time
    val day = { ms: Long -> Instant.fromEpochMilliseconds(ms).toLocalDateTime(zone).date }
    return when {
        n.latin.isBlank() -> UploadProblem.NO_SPECIES // typed in by hand before #144 was dropped
        !n.newLoc && n.locId.isBlank() -> UploadProblem.NO_SITE
        // An age, sex or activity without an id would be left out of the sighting without a word.
        n.age.isNotEmpty() && n.age !in BirdLists.stages -> UploadProblem.UNKNOWN_AGE
        n.sex.isNotEmpty() && n.sex !in BirdLists.genders -> UploadProblem.UNKNOWN_SEX
        n.activity.isNotEmpty() && n.activity !in BirdLists.activities -> UploadProblem.UNKNOWN_ACTIVITY
        n.sex == "I par" && n.count != UNKNOWN_COUNT && n.count % 2 != 0 -> UploadProblem.ODD_PAIR
        day(end) > day(now) -> UploadProblem.FUTURE // the server compares dates, not times
        end < n.time -> UploadProblem.END_BEFORE_START
        else -> null
    }
}

/** The logged-in user's id, from /bff/user's claims. */
fun parseUserId(json: String): Long? =
    parseJson(json).jsonArray.map { it.jsonObject }
        .firstOrNull { it.optString("type") == "user_id" }?.optString("value")?.toLongOrNull()

/** The bird taxon whose scientific name, or a synonym of it, is [latin], from a TaxonName/Search
 *  answer. The same rule as scripts/build_taxon_ids.py, which fills taxon_ids.csv. */
fun parseTaxonId(json: String, latin: String): Long? =
    parseJson(json).jsonArray.map { it.jsonObject }
        .filter { it.optInt("speciesGroupId") == 8 }
        .firstOrNull { h ->
            val names = listOfNotNull(h.optObject("scientificName")) + h.optArray("scientificNameSynonyms").orEmpty().map { it.jsonObject }
            names.any { it.optString("name").equals(latin, ignoreCase = true) }
        }
        ?.optLong("taxonId")

/** The id from an answer that has one: a created sighting, or a Users/ByUserAlias user. */
fun parseId(json: String): Long? = runCatching { (parseJson(json) as JsonObject).optLong("id") }.getOrNull()

/** The human-readable reason in an error answer (a problem+json `detail`), if any. */
fun parseErrorDetail(json: String): String? =
    runCatching { (parseJson(json) as JsonObject).optString("detail").ifBlank { null } }.getOrNull()

/** Fills in the site id on notes saved before notes kept one (before v1.5). A note copies its
 *  locality's name and coordinates, so they find the site again; of a few duplicate pairs in the
 *  registry (same name, same spot) the lower id wins. A note whose site is gone keeps no id. */
fun backfillLocIds(notes: List<Note>, localities: List<Locality>): List<Note> {
    if (notes.none { !it.newLoc && it.locId.isBlank() }) return notes
    val byKey = localities.filter { it.id.isNotBlank() }.groupBy { Triple(it.lokalitet, it.lat, it.lon) }
    return notes.map { n ->
        if (n.newLoc || n.locId.isNotBlank()) return@map n
        val site = byKey[Triple(n.locName, n.lat, n.lon)]?.minByOrNull { it.id.toLongOrNull() ?: Long.MAX_VALUE }
        site?.let { n.copy(locId = it.id) } ?: n
    }
}

/** What the site stored differently from what was sent: the [sent] sightingJson body against the
 *  created sighting in [answer]. Empty when it matches. Catches a sighting landing on the wrong
 *  site, species or day, which a 201 alone doesn't. */
fun sightingMismatches(sent: String, answer: String): List<String> {
    val s = parseJson(sent).jsonObject
    val a = parseJson(answer).jsonObject
    val site = a.optObject("site")
    val out = mutableListOf<String>()
    fun check(field: String, ok: Boolean) { if (!ok) out += field }
    val newSite = s.optObject("newSiteInfo")
    if (newSite == null) check("site", site?.optLong("id") == s.optLong("siteId"))
    else check(
        "site",
        site?.optString("name") == newSite.optString("siteName") && site.optInt("accuracy") == newSite.optInt("accuracy") &&
            haversine(site.optDouble("latitude"), site.optDouble("longitude"), newSite.optDouble("latitude"), newSite.optDouble("longitude")) < 5,
    )
    check("taxon", a.optObject("taxon")?.optLong("id") == s.optLong("taxonId"))
    fun instant(o: JsonObject, k: String) = o.optString(k).takeIf { it.isNotEmpty() }?.let(Instant::parse)
    for (k in listOf("startDate", "endDate", "hiddenByProvider")) check(k, instant(a, k) == instant(s, k))
    for (k in listOf("startTime", "endTime", "comment", "privateComment")) check(k, a.optString(k) == s.optString(k))
    for (k in listOf("quantity", "stageId", "genderId", "activityId")) check(k, a.has(k) == s.has(k) && a.optLong(k) == s.optLong(k))
    check("unsureDetermination", a.optBoolean("unsureDetermination") == s.optBoolean("unsureDetermination"))
    val ids = { arr: JsonArray? -> arr.orEmpty().map { (if (it is JsonObject) it.optLong("id") else it.jsonPrimitive.content.toLong()) }.toSet() }
    check("observers", ids(a.optArray("observers")) == ids(s.optArray("observerIds")))
    return out
}
