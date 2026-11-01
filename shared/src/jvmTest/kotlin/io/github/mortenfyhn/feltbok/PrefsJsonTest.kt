package io.github.mortenfyhn.feltbok

import org.junit.Assert.assertEquals
import org.junit.Test

/** The settings files as the shipped app writes them (Android's org.json), so a change to the JSON
 *  code can't silently reset someone's use scores, party or language choice. */
class PrefsJsonTest {

    @Test
    fun usesReadBothFormats() {
        // A bare count is the pre-decay format: it gets a timestamp a day back.
        val now = 1_717_000_000_000
        val uses = parseUses("""{"Parus major":{"s":2.5,"t":1716000000000},"Pica pica":3}""", now)
        assertEquals(UseEntry(2.5, 1_716_000_000_000), uses["Parus major"])
        assertEquals(UseEntry(3.0, now - 24L * 60 * 60 * 1000), uses["Pica pica"])
        assertEquals(uses.getValue("Parus major"), parseUses(usesJson(uses), now).getValue("Parus major"))
    }

    @Test
    fun countsRoundTrip() {
        val counts = parseCounts("""{"Næringssøkende":4,"Rastende":1}""")
        assertEquals(mapOf("Næringssøkende" to 4, "Rastende" to 1), counts)
        assertEquals(counts, parseCounts(countsJson(counts)))
    }

    @Test
    fun coObserverUsersRoundTrip() {
        val users = parseCoObsUsers("""[{"name":"Kari Nordmann","alias":"kari1","city":"Trøndelag"},{"name":"Ola","alias":"ola"}]""")
        assertEquals(mapOf("kari1" to AoUser("Kari Nordmann", "kari1", "Trøndelag"), "ola" to AoUser("Ola", "ola", "")), users)
        assertEquals(users, parseCoObsUsers(coObsUsersJson(users.values)))
    }

    @Test
    fun partyRoundTrips() {
        val party = parseParty("""["Kari Nordmann","ola"]""")
        assertEquals(listOf("Kari Nordmann", "ola"), party)
        assertEquals(party, parseParty(partyJson(party)))
    }

    @Test
    fun langPrefsFillMissingFieldsFromTheDefault() {
        val default = LangPrefs(Lang.NORSK, Lang.LATIN, searchSecondary = true)
        assertEquals(LangPrefs(Lang.SVENSK, Lang.LATIN, true), parseLangPrefs("""{"primary":"SVENSK"}""", default))
        val prefs = LangPrefs(Lang.LATIN, Lang.NORSK, false)
        assertEquals(prefs, parseLangPrefs(langPrefsJson(prefs), default))
    }
}
