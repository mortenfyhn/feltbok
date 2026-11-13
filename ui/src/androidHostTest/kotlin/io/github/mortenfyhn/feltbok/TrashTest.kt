package io.github.mortenfyhn.feltbok

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The papirkurv (`archive` in the code) as the view model drives it. */
@OptIn(ExperimentalCoroutinesApi::class)
class TrashTest {
    private val platform = FakePlatform()
    private val note = Note(id = 1L, species = "kjøttmeis", latin = "", count = 1, age = "", activity = "",
        sex = "", publicComment = "", privateComment = "", locName = "Brygga", locFull = "Brygga", lat = 59.9, lon = 10.7)

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun restoringReturnsToTheListWithTheNote() {
        appendToArchive(platform.storage, listOf(note))
        val vm = MainViewModel(platform)
        vm.openArchive()
        vm.toggleSelect(note.id)
        vm.restoreSelected()
        assertEquals(Screen.LIST, vm.screen)
        assertTrue(vm.selected.isEmpty())
        assertEquals(listOf(note), vm.notes.toList())
        assertEquals(emptyList<Note>(), loadArchive(platform.storage))
        assertEquals(setOf(note.id), vm.flashIds)
        assertEquals(note.id, vm.scrollToNoteId)
    }

    // Deleting archives at once, so the undo must take the notes back out: a note must never be both
    // live and in the papirkurv.
    @Test
    fun undoingADeleteTakesTheNoteBackOutOfThePapirkurv() {
        saveNotes(platform.storage, listOf(note))
        val vm = MainViewModel(platform)
        vm.setSelection(listOf(note.id))
        vm.deleteSelected()
        assertEquals(listOf(note), loadArchive(platform.storage))
        vm.undo()
        assertEquals(listOf(note), vm.notes.toList())
        assertEquals(emptyList<Note>(), loadArchive(platform.storage))
    }
}
