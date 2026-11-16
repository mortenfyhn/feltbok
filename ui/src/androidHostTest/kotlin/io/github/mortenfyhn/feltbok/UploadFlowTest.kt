package io.github.mortenfyhn.feltbok

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The upload's order of work: check everything, then send only what isn't sent, and keep each
 *  uploaded note's sighting id even when the rest fails. */
class UploadFlowTest {
    private val now = nowMs() - 3_600_000
    private fun note(id: Long, vararg changes: (Note) -> Note) = changes.fold(
        Note(
            id = id, time = now, species = "fiskemåke", latin = "Larus canus", count = 1, age = "", activity = "",
            sex = "", publicComment = "", privateComment = "", locName = "Uttian", locFull = "", lat = 63.77,
            lon = 8.87, locId = "352401",
        ),
    ) { n, f -> f(n) }

    // A server that stores what it's sent, answering in the real answer's shape.
    private fun echo(body: String, id: Long = 1): HttpResult {
        fun field(k: String) = Regex("\"$k\":([^,}]+)").find(body)!!.groupValues[1]
        val observers = Regex("\"observerIds\":\\[([^\\]]*)]").find(body)!!.groupValues[1]
            .split(',').filter { it.isNotBlank() }.joinToString(",") { """{"id":$it}""" }
        val extra = ""","id":$id,"site":{"id":${field("siteId")}},"taxon":{"id":${field("taxonId")}},"observers":[$observers]}"""
        return HttpResponse(201, body.dropLast(1) + extra)
    }

    private fun platform() = FakePlatform().apply {
        cookie = "c"
        answers["/bff/user"] = { """[{"type":"user_id","value":"58758"}]""" }
        answers["TaxonName/Search"] = { """[{"taxonId":3681,"speciesGroupId":8,"scientificName":{"name":"Larus canus"}}]""" }
        answers["ByUserAlias?userAlias=kari"] = { """{"id":7,"name":"Kari"}""" }
        postAnswer = { echo(it) }
    }

    // Users picked before ids were kept: each is looked up.
    private fun upload(
        p: FakePlatform,
        notes: List<Note>,
        known: Map<String, AoUser> = mapOf("kari" to AoUser("Kari", "kari", "")),
    ): Pair<UploadResult, Map<Long, Long>> {
        val sent = LinkedHashMap<Long, Long>()
        val r = runBlocking { uploadNotes(p, notes, emptyMap(), known) { n, id -> sent[n.id] = id } }
        return r to sent
    }

    @Test
    fun aProblemNoteBlocksTheWholeUpload() {
        val p = platform()
        val (r, sent) = upload(p, listOf(note(1), note(2, { it.copy(locId = "") })))
        assertEquals(setOf(2L), (r as UploadResult.Blocked).problems.keys)
        assertTrue(p.posts.isEmpty() && sent.isEmpty())
    }

    @Test
    fun anUnknownSpeciesOrUserBlocksIt() {
        val p = platform().apply { answers["TaxonName/Search"] = { "[]" } }
        assertTrue(upload(p, listOf(note(1))).first is UploadResult.Blocked)
        val q = platform().apply { errors["userAlias=ola"] = 400 } // as the site answers an unknown alias
        val (r, _) = upload(q, listOf(note(1, { it.copy(coObservers = listOf("ola")) })), mapOf("ola" to AoUser("Ola Nordmann", "ola", "")))
        // Named as the user search showed them: the alias is hidden on the site.
        assertEquals(mapOf(1L to "Brukeren Ola Nordmann finnes ikke på Artsobservasjoner."), (r as UploadResult.Blocked).problems)
        assertTrue(q.posts.isEmpty())
    }

    @Test
    fun aTooLongCommentSaysWhichAndWhetherTheCoObserversMadeIt() {
        val long = "x".repeat(250)
        fun problem(n: Note) = (upload(platform(), listOf(n)).first as UploadResult.Blocked).problems.values.single()
        assertEquals(Strings.Upload.privateTooLong, problem(note(1, { it.copy(privateComment = long + "xxxxx") })))
        assertEquals(Strings.Upload.publicTooLong, problem(note(1, { it.copy(publicComment = long + "xxxxx") })))
        // A user picked from the search is linked by id, so only the typed-in name counts.
        val n = note(1, { it.copy(publicComment = long, coObservers = listOf("kari", "Per Hansen")) })
        assertEquals(Strings.Upload.publicTooLongWithCoObservers, problem(n))
    }

    @Test
    fun noAnswerDuringTheChecksSendsNothing() {
        val p = platform().apply { val _ = answers.remove("TaxonName/Search"); offline = true }
        assertTrue(upload(p, listOf(note(1))).first is UploadResult.NotStarted)
        assertTrue(p.posts.isEmpty())
        assertTrue(upload(platform().apply { cookie = null }, listOf(note(1))).first is UploadResult.NotStarted)
    }

    @Test
    fun aRejectedNoteFailsAloneButLoggingOutStopsAndARetrySendsTheRest() {
        val p = platform()
        var calls = 0
        p.postAnswer = { body ->
            when (++calls) {
                2 -> HttpResponse(400, """{"detail":"Invalid arguments: Date can not be a date in the future"}""")
                3 -> HttpResponse(401, "")
                else -> echo(body, 100L + calls)
            }
        }
        val notes = listOf(note(1), note(2), note(3), note(4))
        val (r, sent) = upload(p, notes)
        r as UploadResult.Done
        assertEquals(mapOf(2L to "«Date can not be a date in the future»"), r.failed)
        assertEquals(Interruption.LoggedOut, r.stopped)
        assertEquals(mapOf(1L to 101L), sent)
        assertEquals(3, p.posts.size) // nothing after the 401
        // The caller stores the ids on the notes; the retry sends only what's left.
        val retry = notes.map { n -> sent[n.id]?.let { n.copy(sightingId = it) } ?: n }
        p.posts.clear()
        val _ = upload(p, retry.filter { it.id !in sent })
        assertEquals(3, p.posts.size)
    }

    @Test
    fun everyChoiceInTheAppHasAnId() {
        assertEquals(emptyList<String>(), Country.ages.filterNot { it in BirdLists.stages })
        assertEquals(emptyList<String>(), Country.sexes.filterNot { it in BirdLists.genders })
        assertEquals(emptyList<String>(), Country.activities.filterNot { it in BirdLists.activities })
    }

    @Test
    fun observersAreTheReporterAndTheUsersAndTheRestGoInTheComment() {
        val p = platform()
        val _ = upload(p, listOf(note(1, { it.copy(coObservers = listOf("kari", "Per Hansen"), publicComment = "Fin") })))
        val body = p.posts.single().second
        assertTrue(body, "\"observerIds\":[58758,7]" in body)
        assertTrue(body, "\"comment\":\"Fin. Medobservatør: Per Hansen\"" in body)
    }

    @Test
    fun aSightingStoredDifferentlyIsKeptButFlagged() {
        val p = platform()
        p.postAnswer = { body -> echo(body.replace("\"siteId\":352401", "\"siteId\":3635289")) }
        val (r, sent) = upload(p, listOf(note(1)))
        assertEquals(setOf(1L), (r as UploadResult.Done).mismatched.keys)
        assertEquals(setOf(1L), sent.keys)
    }

    @Test
    fun eachLookupIsMadeOncePerUpload() {
        val p = platform()
        val _ = upload(p, List(3) { note(it.toLong(), { n -> n.copy(coObservers = listOf("kari")) }) })
        assertEquals(1, p.requestsTo("TaxonName/Search").size)
        assertEquals(1, p.requestsTo("ByUserAlias").size)
    }

    @Test
    fun bundledTaxonIdsAndStoredUserIdsNeedNoLookups() {
        val p = platform()
        val n = note(1, { it.copy(coObservers = listOf("kari")) })
        runBlocking { uploadNotes(p, listOf(n), mapOf("Larus canus" to 3681L), mapOf("kari" to AoUser("Kari", "kari", "", 7))) { _, _ -> } }
        assertEquals(0, p.requestsTo("TaxonName/Search").size + p.requestsTo("ByUserAlias").size)
        assertTrue("\"observerIds\":[58758,7]" in p.posts.single().second)
    }
}
