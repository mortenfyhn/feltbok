package io.github.mortenfyhn.feltbok

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for the co-observer picker logic (#128). The sticky party itself is state, not logic
 *  (see MainViewModel.party). */
class CoObserversTest {

    @Test
    fun pickerListsNamesBySurnameWhateverTheUseCounts() {
        // "ingeb" is a user's alias, shown (and so sorted) as Inge Berg. The surname is the last word;
        // a single name sorts as it is, and the whole name breaks a tie.
        val uses = mapOf("Ola Aas" to 5, "Kari Nordmann" to 2, "ingeb" to 9, "Hanna Tomasgård Olstad" to 1, "Mona" to 1, "Anne Berg" to 3)
        val shown = mapOf("ingeb" to "Inge Berg")
        assertEquals(
            listOf("Ola Aas", "Anne Berg", "ingeb", "Mona", "Kari Nordmann", "Hanna Tomasgård Olstad"),
            coObserverOptions(uses, onDraft = emptyList()) { shown[it] ?: it },
        )
    }

    @Test
    fun pickerIncludesDraftNamesNotYetInTheRegisterWithoutDuplicating() {
        // A just-added free-text name is on the draft but not yet in the register - it must still show,
        // and a name in both must appear once.
        val uses = mapOf("Ola" to 5, "Kari" to 2)
        assertEquals(listOf("Kari", "Nina", "Ola"), coObserverOptions(uses, onDraft = listOf("Kari", "Nina")) { it })
    }

    @Test
    fun norwegianOrderPutsAeOeAaAfterZ() {
        val names = listOf("Åse", "Øyvind", "Zara", "Ærlend", "Ödegaard", "anna")
        assertEquals(listOf("anna", "Zara", "Ærlend", "Ödegaard", "Øyvind", "Åse"), names.sortedBy(::norwegianSortKey))
    }

    @Test
    fun aliasIsNeededOnlyForNamesakesFromTheSamePlace() {
        val users = listOf(
            AoUser("Inge Berg", "ingeb", "Bergen"),
            AoUser("inge berg", "Pingu", "bergen"),   // same name and place: only the alias tells them apart
            AoUser("Inge Berg", "ib2", "Oslo"),        // the place tells this one apart
            AoUser("Kari Nordmann", "kari", ""),       // unique name
            AoUser("Inge Berg", "ingeb", "Bergen"),    // the same user listed twice is no namesake
        )
        assertEquals(setOf("ingeb", "Pingu"), needsAlias(users))
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
