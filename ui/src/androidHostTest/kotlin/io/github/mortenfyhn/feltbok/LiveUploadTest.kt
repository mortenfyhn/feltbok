package io.github.mortenfyhn.feltbok

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Uploads a set of notes covering every case the upload handles to the real Artsobservasjoner, reads
 * each sighting back and checks it against the note, then deletes them. Skipped unless
 * FELTBOK_LIVE_COOKIE names a file holding a logged-in session's "Cookie:" line (or a Firefox "Copy as
 * cURL" with one). FELTBOK_LIVE_COOBS optionally adds a real co-observer's brukeralias:
 *
 *     FELTBOK_LIVE_COOKIE=~/ao-curl.txt ./gradlew :ui:testAndroidHostTest --tests '*LiveUploadTest*' --rerun
 *
 * Leaves one thing behind: the brand-new spot mints a private site "Feltbok live-test", which the API
 * can't delete. Delete it under Mine lokaliteter.
 */
class LiveUploadTest {
    private val cookieFile = System.getenv("FELTBOK_LIVE_COOKIE")?.takeIf { it.isNotBlank() }
    private val coObserver = System.getenv("FELTBOK_LIVE_COOBS")?.takeIf { it.isNotBlank() } // a real brukeralias, optional

    @Test
    fun uploadedSightingsAreStoredAsTheNotesSay() {
        assumeTrue("set FELTBOK_LIVE_COOKIE to run", cookieFile != null)
        val p = LivePlatform(readCookie(File(cookieFile!!)))
        val day = 86_400_000L
        val noon = nowMs() / day * day - day + 11 * 3_600_000 // yesterday ~12:00 local
        val hidden = noon + 365 * day // hidden for a year, in case one is published by mistake
        fun note(id: Long, locId: String, name: String, vararg changes: (Note) -> Note) = changes.fold(
            Note(
                id = id, time = noon, species = "fiskemåke", latin = "Larus canus", count = 1, age = "", activity = "",
                sex = "", publicComment = "", privateComment = "Feltbok live-test, slettes", locName = name, locFull = "",
                lat = 0.0, lon = 0.0, locId = locId, hideUntil = hidden,
            ),
        ) { n, f -> f(n) }
        val notes = listOfNotNull(
            note(1, "352401", "Uttian", {
                it.copy(count = 2, age = "Adult", sex = "Hunn", activity = "Overflygende", publicComment = "offentlig", uncertain = true)
            }),
            note(2, "349438", "Ånegga sør"), // public, with a private site of the same name
            note(3, "3635289", "Ånegga sør"), // that private one
            note(4, "353951", "Svarthaugen"), // public without a parent, with a private namesake
            note(5, "1165177", "registry duplicate"), // one of two public sites with the same name and spot
            note(6, "", "Feltbok live-test", { it.copy(newLoc = true, lat = 63.7735, lon = 8.8692, locRadius = 25) }),
            note(7, "352401", "Uttian", { it.copy(count = UNKNOWN_COUNT, timeUnknown = true) }),
            note(8, "352401", "Uttian", { it.copy(time = noon - day + 11 * 3_600_000, endTime = noon - 10 * 3_600_000) }), // 23:00 -> 02:00
            note(9, "352401", "Uttian", { it.copy(time = noon - 2 * day, endTime = noon, timeUnknown = true) }),
            note(10, "352401", "Uttian", { it.copy(sex = "I par", count = 2) }),
            note(11, "352401", "Uttian", { it.copy(coObservers = listOf("Per Testesen"), publicComment = "fin") }),
            coObserver?.let { a -> note(12, "352401", "Uttian", { it.copy(coObservers = listOf(a)) }) },
            note(13, "352401", "Uttian", { it.copy(species = "ub. makrell-/rødnebbterne", latin = "Sterna hirundo/paradisaea") }),
            note(14, "352401", "Uttian", { it.copy(species = "ub. svane", latin = "Cygnus") }),
        )
        val sent = LinkedHashMap<Long, Long>()
        try {
            val r = runBlocking {
                // The bundled taxon ids, so this also checks them against the site.
                val taxonIds = parseTaxonIds(File("../app/src/main/assets/taxon_ids.csv").readText())
                val users = listOfNotNull(coObserver).associateWith { AoUser(it, it, "") }
                uploadNotes(p, notes, taxonIds, users) { n, id -> sent[n.id] = id }
            }
            assertEquals(UploadResult.Done(notes.size, emptyMap()), r)
            // Read each one back: what's stored, not just what the POST answered.
            val mismatches = sent.mapNotNull { (noteId, sightingId) ->
                val stored = (p.get("${Country.sitesHost}/core/Sightings/$sightingId") as HttpResponse).body
                sightingMismatches(p.bodies.getValue(sightingId), stored).takeIf { it.isNotEmpty() }?.let { noteId to it }
            }.toMap()
            assertEquals(emptyMap<Long, List<String>>(), mismatches)
        } finally {
            if (sent.isNotEmpty()) { val _ = p.send("DELETE", "${Country.sitesHost}/core/Sightings", sent.values.joinToString(",", "[", "]")) }
        }
    }

    /** A "Cookie:" header line, or a Firefox "Copy as cURL" holding one. */
    private fun readCookie(f: File) =
        Regex("(?im)^(?:.*-H\\s+['\"])?cookie:\\s*([^'\"\\n]+)").find(f.readText())!!.groupValues[1].trim()

    private class LivePlatform(private val cookie: String) : Platform {
        val bodies = HashMap<Long, String>() // sighting id -> the body that created it

        override val storage: Storage get() = error("not used")
        override val location = object : LocationSource {
            override val fix = MutableStateFlow<GpsFix?>(null)
            override fun start() = Unit
            override fun stop() = Unit
        }
        override fun toast(message: String, long: Boolean) = Unit
        override fun clearCookies() = Unit
        override suspend fun siteCookie() = cookie
        override suspend fun httpGet(url: String, headers: Map<String, String>, timeoutMs: Int) = get(url)
        override suspend fun httpPostJson(url: String, headers: Map<String, String>, body: String, timeoutMs: Int): HttpResult =
            send("POST", url, body).also { r -> (r as? HttpResponse)?.body?.let(::parseId)?.let { bodies[it] = body } }

        fun get(url: String) = send("GET", url, null)

        fun send(method: String, url: String, body: String?): HttpResult = runCatching {
            val c = URL(url).openConnection() as HttpURLConnection
            c.requestMethod = method
            c.setRequestProperty("X-CSRF", "1")
            c.setRequestProperty("Cookie", cookie)
            c.setRequestProperty("Accept", "application/json")
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.outputStream.use { it.write(body.encodeToByteArray()) }
            }
            val stream = if (c.responseCode in 200..299) c.inputStream else c.errorStream
            HttpResponse(c.responseCode, stream?.bufferedReader()?.readText().orEmpty())
        }.getOrElse { NoAnswer(offline = false) }
    }
}
