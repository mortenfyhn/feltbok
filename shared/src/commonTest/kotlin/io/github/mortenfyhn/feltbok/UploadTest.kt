package io.github.mortenfyhn.feltbok

import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class UploadTest {
    private val oslo = TimeZone.of("Europe/Oslo")
    private fun note(vararg changes: (Note) -> Note) = changes.fold(
        Note(
            id = 1, time = 1794567600000, // 13.11.2026 12:00 Oslo
            species = "fiskemåke", latin = "Larus canus", count = 2, age = "Adult", activity = "Overflygende",
            sex = "Hunn", publicComment = "", privateComment = "p", locName = "Uttian", locFull = "Uttian",
            lat = 63.77, lon = 8.87, locId = "352401",
        ),
    ) { n, f -> f(n) }

    private fun body(n: Note) = Json.parseToJsonElement(sightingJson(n, 3681, listOf(58758), zone = oslo)).jsonObject

    @Test
    fun mapsARegistryNote() {
        val b = body(note())
        assertEquals("352401", b["siteId"].toString())
        assertEquals("2026-11-12T23:00:00Z", b["startDate"]!!.toString().trim('"')) // local midnight, as UTC
        assertEquals("\"12:00\"", b["startTime"].toString())
        assertEquals(listOf("23", "2", "24"), listOf("stageId", "genderId", "activityId").map { b[it].toString() })
        assertEquals("[58758]", b["observerIds"].toString())
    }

    @Test
    fun unknownCountAndTimeAreNull() {
        val b = body(note({ it.copy(count = UNKNOWN_COUNT, timeUnknown = true) }))
        assertEquals(JsonNull, b["quantity"])
        assertEquals(JsonNull, b["startTime"])
    }

    @Test
    fun aNewSpotIsSentAsNewSiteInfo() {
        val b = body(note({ it.copy(newLoc = true, locId = "", locRadius = 50) }))
        assertEquals("0", b["siteId"].toString())
        assertEquals("50", b["newSiteInfo"]!!.jsonObject["accuracy"].toString())
    }

    private fun site(id: String, name: String = "Uttian") = Locality(id, name, "", "Frøya", 63.77, 8.87, 0, 0.0)

    @Test
    fun oldNotesFindTheirSiteByNameAndPosition() {
        val old = note({ it.copy(locId = "") })
        assertEquals("352401", backfillLocIds(listOf(old), listOf(site("352401"), site("9", "Annen"))).single().locId)
        // A duplicate pair in the registry (same name, same spot): the lower id.
        assertEquals("352401", backfillLocIds(listOf(old), listOf(site("999999"), site("352401"))).single().locId)
        // The site is gone: no id, so the note gets flagged before upload.
        assertEquals("", backfillLocIds(listOf(old), listOf(site("9", "Annen"))).single().locId)
    }

    @Test
    fun problemsAreFoundBeforeUploading() {
        val now = note().time
        assertEquals(null, uploadProblem(note(), now, oslo))
        assertEquals(UploadProblem.NO_SITE, uploadProblem(note({ it.copy(locId = "") }), now, oslo))
        assertEquals(UploadProblem.UNKNOWN_AGE, uploadProblem(note({ it.copy(age = "Ung") }), now, oslo))
        assertEquals(UploadProblem.UNKNOWN_SEX, uploadProblem(note({ it.copy(sex = "Hann?") }), now, oslo))
        assertEquals(UploadProblem.UNKNOWN_ACTIVITY, uploadProblem(note({ it.copy(activity = "Rasta") }), now, oslo))
        assertEquals(UploadProblem.ODD_PAIR, uploadProblem(note({ it.copy(sex = "I par", count = 3) }), now, oslo))
        assertEquals(UploadProblem.FUTURE, uploadProblem(note({ it.copy(time = now + 86_400_000) }), now, oslo))
        // Later today is fine: the server compares dates.
        assertEquals(null, uploadProblem(note({ it.copy(time = now + 3_600_000) }), now, oslo))
    }

    @Test
    fun aTypedSpeciesIsFoundByAnyOfItsNames() {
        // In the shape TaxonName/Search answers, trimmed.
        val json = """[
            {"taxonId":46560,"speciesGroupId":7,"scientificName":{"name":"Gonepteryx rhamni"},
             "vernacularName":{"name":"sitronsommerfugl"},"vernacularNameSynonyms":[{"name":"sitronsommarfugl"}]},
            {"taxonId":1,"speciesGroupId":7,"scientificName":{"name":"Gonepteryx"},"vernacularName":{"name":"sitronsommerfugler"}}
        ]"""
        assertEquals(TypedTaxon(46560, groupId = 7), parseTaxonByName(json, "Sitronsommerfugl "))
        assertEquals(46560, parseTaxonByName(json, "sitronsommarfugl")?.id)
        assertEquals(46560, parseTaxonByName(json, "gonepteryx rhamni")?.id)
        // Only a whole name counts.
        assertEquals(null, parseTaxonByName(json, "sitron"))
    }

    @Test
    fun aTypedNameThatFitsSeveralTaxaFindsNone() {
        val json = """[
            {"taxonId":1,"scientificName":{"name":"A a"},"vernacularName":{"name":"tvilling"}},
            {"taxonId":2,"scientificName":{"name":"B b"},"vernacularName":{"name":"tvilling"}}
        ]"""
        assertEquals(null, parseTaxonByName(json, "tvilling"))
    }

    @Test
    fun aGroupAllowsOnlyItsOwnOptions() {
        // In the shape GetAllLists answers, trimmed: id 0 is the blank choice.
        val json = """{
            "speciesGroups":[{"id":7,"resourceLabel":"Virvelløse dyr"},{"id":4,"resourceLabel":"Sopp"}],
            "speciesGroupStages":[{"speciesGroupId":7,"stages":[{"id":0},{"id":1}]}],
            "speciesGroupGenders":[{"speciesGroupId":7,"genders":[{"id":1},{"id":2}]}],
            "speciesGroupActivities":[{"speciesGroupId":7,"activityCategories":[{"activity":[{"id":0},{"id":25}]}]}]
        }"""
        val lists = parseGroupLists(json)
        val bug = lists.getValue(7)
        assertEquals("Virvelløse dyr", bug.name)
        assertEquals(null, unfitOption(note({ it.copy(age = "Egg", sex = "Hann", activity = "Næringssøkende") }), bug))
        assertEquals("Rastende", unfitOption(note({ it.copy(age = "", sex = "Hann", activity = "Rastende") }), bug))
        assertEquals("Adult", unfitOption(note({ it.copy(age = "Adult", activity = "Rastende") }), bug))
        // A group without a list allows none of it.
        assertEquals("Hann", unfitOption(note({ it.copy(age = "", sex = "Hann", activity = "") }), lists.getValue(4)))
    }

    @Test
    fun datesAreLocalMidnightInSummerAndWinter() {
        // 00:30 local: the day is the local one, not UTC's (which is still the day before).
        val summer = body(note({ it.copy(time = 1782167400000) })) // 23.06.2026 00:30, UTC+2
        assertEquals("\"2026-06-22T22:00:00Z\"", summer["startDate"].toString())
        assertEquals("\"00:30\"", summer["startTime"].toString())
        val winter = body(note({ it.copy(time = 1767223800000) })) // 01.01.2026 00:30, UTC+1
        assertEquals("\"2025-12-31T23:00:00Z\"", winter["startDate"].toString())
        // A range over midnight ends on the next day.
        val range = body(note({ it.copy(endTime = it.time + 13 * 3_600_000) })) // 12:00 -> 01:00
        assertEquals("\"2026-11-13T23:00:00Z\"", range["endDate"].toString())
        assertEquals("\"01:00\"", range["endTime"].toString())
    }

    @Test
    fun passesTheRestOfTheFields() {
        val b = body(note({ it.copy(publicComment = "offentlig", uncertain = true, hideUntil = 1826103600000) })) // 13.11.2027 12:00
        assertEquals("\"offentlig\"", b["comment"].toString())
        assertEquals("\"p\"", b["privateComment"].toString())
        assertEquals("true", b["unsureDetermination"].toString())
        assertEquals("\"2027-11-12T23:00:00Z\"", b["hiddenByProvider"].toString())
        assertEquals(JsonNull, body(note())["hiddenByProvider"])
    }

    // The fields of a real 201 answer (2026-11-13) that the check reads.
    private fun answer(siteId: Long = 352401, stageId: Int = 23) = """
        {"id":42227128,"startDate":"2026-11-12T23:00:00Z","startTime":"12:00","endDate":"2026-11-12T23:00:00Z",
         "endTime":"12:00","taxon":{"id":3681},"site":{"id":$siteId,"name":"Uttian","longitude":8.86918358,
         "latitude":63.77354881,"accuracy":1500},"reporter":null,"observers":[{"id":58758,"name":null}],
         "quantity":2,"activityId":24,"stageId":$stageId,"genderId":2,"unitId":null,"comment":"",
         "privateComment":"p","unsureDetermination":false,"hiddenByProvider":null}
    """

    @Test
    fun aStoredSightingIsCheckedAgainstTheNote() {
        val sent = sightingJson(note(), 3681, listOf(58758), zone = oslo)
        assertEquals(emptyList(), sightingMismatches(sent, answer()))
        assertEquals(listOf("site"), sightingMismatches(sent, answer(siteId = 3635289)))
        assertEquals(listOf("stageId"), sightingMismatches(sent, answer(stageId = 3)))
    }
}
