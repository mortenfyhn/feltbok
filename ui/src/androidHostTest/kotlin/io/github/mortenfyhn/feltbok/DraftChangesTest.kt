package io.github.mortenfyhn.feltbok

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Lagre is greyed out until an edit or batch edit changes a field (it follows draftHasChanges), and
 *  while a value would make the export refuse the note. */
@OptIn(ExperimentalCoroutinesApi::class)
class DraftChangesTest {
    private lateinit var vm: MainViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        vm = MainViewModel(FakePlatform())
        vm.notes.addAll(listOf(note(1), note(2)))
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun note(id: Long) = Note(
        id = id, species = "Gråspurv", latin = "Passer domesticus", count = 1, age = "", activity = "", sex = "",
        publicComment = "", privateComment = "", locName = "Sted", locFull = "", lat = 59.9, lon = 10.7,
    )

    @Test
    fun editHasChangesOnlyAfterAFieldChanges() {
        vm.editNote(vm.notes.first())
        assertFalse(vm.draftHasChanges())
        vm.setCount(5)
        assertTrue(vm.draftHasChanges())
    }

    @Test
    fun batchEditHasChangesOnlyAfterAFieldChanges() {
        vm.setSelection(vm.notes.map { it.id })
        vm.startBatchEdit()
        assertFalse(vm.draftHasChanges())
        vm.setCount(5)
        assertTrue(vm.draftHasChanges())
    }

    @Test
    fun anOddCountInPairsCantBeSaved() {
        vm.editNote(vm.notes.first())
        vm.dSex = "I par"
        assertTrue(vm.draftOddPair())
        vm.setCount(2)
        assertFalse(vm.draftOddPair())
    }

    @Test
    fun aCommentTooLongWithTypedCoObserversCantBeSaved() {
        vm.editNote(vm.notes.first())
        vm.dPub = "x".repeat(MAX_COMMENT - 10)
        vm.dCoObs.add("Ola Nordmann") // typed, so it goes into the comment
        assertTrue(vm.draftCommentTooLong())
        vm.dCoObs.clear()
        assertFalse(vm.draftCommentTooLong())
    }
}
