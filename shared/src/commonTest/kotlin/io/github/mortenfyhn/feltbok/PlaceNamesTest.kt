package io.github.mortenfyhn.feltbok

import kotlin.test.Test
import kotlin.test.assertEquals

class PlaceNamesTest {
    private fun raw(name: String, type: String, dist: Double, status: String = "hovednavn") =
        RawPlace(name, type, status, dist)

    @Test
    fun nearestUsableNameWins() {
        val out = rankPlaceNames(listOf(raw("Fjæra", "Bruk", 200.0), raw("Haugen", "Bruk", 50.0)))
        assertEquals("Haugen", out.first().name)
    }

    @Test
    fun deniedTypesAndHistoricalNamesAreDropped() {
        val out = rankPlaceNames(
            listOf(
                raw("Øya/Singsaker", "Administrativ bydel", 10.0),
                raw("Gammelnavn", "Bruk", 20.0, status = "historisk"),
                raw("Fagerhaug", "Bruk", 80.0),
            ),
        )
        assertEquals(listOf("Fagerhaug"), out.map { it.name })
    }

    @Test
    fun onlyTheNearestStreetsAreKept() {
        val streets = (1..6).map { raw("Gate $it", "Adressenavn", it * 10.0) }
        val out = rankPlaceNames(streets + raw("Duedalen", "Dal", 98.0))
        assertEquals(listOf("Gate 1", "Gate 2", "Gate 3", "Gate 4", "Duedalen"), out.map { it.name })
    }

    @Test
    fun repeatedNamesKeepTheNearest() {
        val out = rankPlaceNames(listOf(raw("Berg", "Bruk", 400.0), raw("Berg", "Bruk", 120.0)))
        assertEquals(listOf(120), out.map { it.distanceM })
    }

    @Test
    fun listIsCapped() {
        val many = (1..20).map { raw("Sted $it", "Bruk", it.toDouble()) }
        assertEquals(12, rankPlaceNames(many).size)
    }

    @Test
    fun parsesThePunktResponse() {
        val json = """
            {"navn":[
              {"meterFraPunkt":98,"navneobjekttype":"Dal",
               "stedsnavn":[{"skrivemåte":"Duedalen","navnestatus":"hovednavn"}]},
              {"meterFraPunkt":5,"navneobjekttype":"Bruk","stedsnavn":[]}
            ]}
        """
        assertEquals(listOf(RawPlace("Duedalen", "Dal", "hovednavn", 98.0)), parsePlaceNames(json))
    }
}
