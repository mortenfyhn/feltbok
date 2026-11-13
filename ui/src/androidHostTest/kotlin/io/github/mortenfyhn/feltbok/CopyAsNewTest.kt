package io.github.mortenfyhn.feltbok

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

/** "Kopier" (#130, #136): saves the open note, then opens a new note with every field but the comments. */
@OptIn(ExperimentalCoroutinesApi::class)
class CopyAsNewTest {
    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun copyKeepsTheOriginalAndOpensANewNoteWithoutComments() {
        val vm = MainViewModel(FakePlatform())
        val original = Note(
            id = 1, time = 1, species = "Gråspurv", latin = "Passer domesticus", count = 3, age = "", activity = "",
            sex = "", publicComment = "offentlig", privateComment = "privat", locName = "Sted", locFull = "",
            lat = 59.9, lon = 10.7,
        )
        vm.notes.add(original)

        vm.editNote(original)
        vm.copyAsNew()
        assertEquals(listOf(original), vm.notes.toList())
        assertFalse(vm.isEditing)
        assertEquals("" to "", vm.dPub to vm.dPriv)

        vm.save()
        assertEquals(2, vm.notes.size)
        val copy = vm.notes.single { it.id != original.id }
        assertEquals(original.copy(id = copy.id, publicComment = "", privateComment = ""), copy)
    }
}
