package io.github.mortenfyhn.feltbok

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceNamesFetchTest {
    @Test
    fun answerBecomesRankedSuggestions() = runBlocking {
        val p = FakePlatform()
        p.answers["stedsnavn/v1/punkt?nord=63.42&ost=10.41"] = {
            """{"navn":[{"meterFraPunkt":98,"navneobjekttype":"Dal","stedsnavn":[{"skrivemåte":"Duedalen","navnestatus":"hovednavn"}]}]}"""
        }
        assertEquals(listOf(PlaceSuggestion("Duedalen", "Dal", 98)), fetchPlaceNames(p, 63.42, 10.41))
    }

    @Test
    fun offlineOrBrokenAnswersGiveNoSuggestions() = runBlocking {
        val p = FakePlatform()
        assertTrue(fetchPlaceNames(p, 63.42, 10.41).isEmpty())     // no answer: offline
        p.answers["stedsnavn"] = { "<html>" }
        assertTrue(fetchPlaceNames(p, 63.42, 10.41).isEmpty())     // not JSON
        p.errors["stedsnavn"] = 503
        assertTrue(fetchPlaceNames(p, 63.42, 10.41).isEmpty())
    }
}
