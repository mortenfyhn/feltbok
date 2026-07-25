package io.github.mortenfyhn.feltbok

import org.junit.Assert.assertEquals
import org.junit.Test

/** Unit tests for the co-observer picker logic (#128). The sticky party has no logic to test any
 *  more: it's derived (the newest note's co-observers, see MainViewModel.party()). */
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
}
