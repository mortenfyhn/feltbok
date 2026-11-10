package io.github.mortenfyhn.feltbok

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** Opening a note in the editor and saving it keeps its time range and unknown time-of-day. */
@OptIn(ExperimentalCoroutinesApi::class)
class EditTimeTest {
    private lateinit var vm: MainViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        vm = MainViewModel(FakePlatform())
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun note(id: Long, endTime: Long?, timeUnknown: Boolean) = Note(
        id = id, endTime = endTime, timeUnknown = timeUnknown,
        species = "Gråspurv", latin = "Passer domesticus", count = 1, age = "", activity = "", sex = "",
        publicComment = "", privateComment = "", locName = "Sted", locFull = "", lat = 59.9, lon = 10.7,
    )

    @Test
    fun saveKeepsTheEndTimeAndUnknownTime() {
        val start = 1_780_000_000_000
        val ranged = note(start, endTime = start + 90 * 60_000, timeUnknown = false)
        val noTime = note(start + 1, endTime = start + 86_400_000, timeUnknown = true)
        vm.notes.addAll(listOf(ranged, noTime))
        for (n in listOf(ranged, noTime)) {
            vm.editNote(n)
            vm.save()
            val saved = vm.notes.single { it.id == n.id }
            assertEquals(n.endTime, saved.endTime)
            assertEquals(n.timeUnknown, saved.timeUnknown)
        }
    }
}
