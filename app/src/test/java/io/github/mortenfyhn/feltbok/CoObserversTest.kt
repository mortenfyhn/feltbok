package io.github.mortenfyhn.feltbok

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for the co-observer picker logic (#128). The sticky party itself is state, not logic
 *  (see MainViewModel.party). */
class CoObserversTest {

    @Test
    fun pickerListsKnownNamesMostUsedFirst() {
        val uses = mapOf("Kari" to 2, "Ola" to 5, "Per" to 1)
        assertEquals(listOf("Ola", "Kari", "Per"), coObserverOptions(uses, onDraft = emptyList()))
    }

    @Test
    fun pickerIncludesDraftNamesNotYetInTheRegisterWithoutDuplicating() {
        // A just-added free-text name is on the draft but not yet in the register - it must still show,
        // and a name in both must appear once (kept at its register rank).
        val uses = mapOf("Ola" to 5, "Kari" to 2)
        assertEquals(listOf("Ola", "Kari", "Nina"), coObserverOptions(uses, onDraft = listOf("Kari", "Nina")))
    }

    @Test
    fun searchedUserGetsThePlainNameOnlyWhenNothingElseUsesIt() {
        val u = AoUser("Arian Amundsen Borgaas", "122345", "Moss")
        val own = "Arian Amundsen Borgaas (122345)"
        assertEquals(u.name, coObsLabel(u, emptyMap(), emptySet()))                 // new name
        assertEquals(u.name, coObsLabel(u, mapOf(u.name to u), setOf(u.name)))     // same person again
        // A free-text entry of that name is left alone, so its notes keep exporting as typed.
        assertEquals(own, coObsLabel(u, emptyMap(), setOf(u.name)))
        // Same name and town as someone already linked: the alias keeps a third namesake apart too.
        val other = mapOf(u.name to u.copy(alias = "123445566778"))
        assertEquals(own, coObsLabel(u, other, emptySet()))
        assertEquals(own, coObsLabel(u, other + (own to u), setOf(own)))
    }

    @Test
    fun freeTextIsOfferedOnlyWhenNothingMatchesExactly() {
        val inge = AoUser("Inge Berg", "ingeb", "Bergen")
        assertTrue(canAddFreeText("Inge Berg", searching = false, saved = emptyList(), hits = emptyList()))  // offline / not found
        assertFalse(canAddFreeText("Inge Berg", searching = true, saved = emptyList(), hits = emptyList()))
        assertFalse(canAddFreeText("inge berg", searching = false, saved = listOf("Inge Berg"), hits = emptyList()))
        assertFalse(canAddFreeText("inge berg", searching = false, saved = emptyList(), hits = listOf(inge)))
        assertTrue(canAddFreeText("Inge", searching = false, saved = emptyList(), hits = listOf(inge)))       // partial hit only
        assertFalse(canAddFreeText(" ", searching = false, saved = emptyList(), hits = emptyList()))
    }

    @Test
    fun searchResponseParsesAndSkipsUsersWithoutAlias() {
        val json = """[{"id":39931,"name":"Audun Amundsen ","userAlias":"Teupaksa","city":"Trondheim"},
            {"id":1,"name":"Uten Alias","userAlias":"","city":"Moss"},{"id":2,"name":"Uten By","userAlias":"utenby"}]"""
        assertEquals(
            listOf(AoUser("Audun Amundsen", "Teupaksa", "Trondheim"), AoUser("Uten By", "utenby", "")),
            parseAoUsers(json),
        )
    }
}
