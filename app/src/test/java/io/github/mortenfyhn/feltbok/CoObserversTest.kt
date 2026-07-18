package io.github.mortenfyhn.feltbok

import org.junit.Assert.assertEquals
import org.junit.Test

/** Unit tests for the co-observer sticky-party + picker logic (#128) - the non-obvious behaviour a
 *  regression would break silently. Pure functions in CoObservers.kt; the ViewModel just calls them. */
class CoObserversTest {

    @Test
    fun creatingAnObservationMakesItsCoObserversTheParty() {
        // The "everything for this trip is me + these people" default: a new obs sets the party, so
        // the next new obs inherits it.
        assertEquals(
            listOf("Kari", "Ola"),
            stickyPartyAfterSave(current = emptyList(), savedCoObservers = listOf("Kari", "Ola"), isEditing = false),
        )
    }

    @Test
    fun editingAnObservationLeavesThePartyUntouched() {
        // Going back to fix an old note's co-observers must not hijack today's party (the field-tested
        // edge: adding a co-obs by *editing* the first obs doesn't make it sticky).
        assertEquals(
            listOf("Kari"),
            stickyPartyAfterSave(current = listOf("Kari"), savedCoObservers = listOf("Per"), isEditing = true),
        )
    }

    @Test
    fun creatingSoloClearsTheParty() {
        // Saving a new obs with nobody along resets the party - "Nå er jeg alene".
        assertEquals(
            emptyList<String>(),
            stickyPartyAfterSave(current = listOf("Kari", "Ola"), savedCoObservers = emptyList(), isEditing = false),
        )
    }

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
}
