package io.github.mortenfyhn.feltbok

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.rules.ExternalResource

/**
 * Wipes the app's stored state before each test.
 *
 * All the instrumented tests share one install, and [MainViewModel] persists notes.json on save and
 * reloads it in its constructor — so a note one test saves is still there for the next. That made
 * the suite order-dependent: [AddObservationFlowTest] asserts on a *single* "kjøttmeis" node, and a
 * leftover note turns that into two, which `onNodeWithText` rejects. It only passed because JUnit
 * runs classes alphabetically (Add before Export). Every test added compounds that, so clear first.
 *
 * Deletes via `Context.filesDir` rather than naming a package, so it can only ever reach this
 * variant's own sandbox (io.github.mortenfyhn.feltbok.releasetest) — never the real app's notes.
 * Wipes the whole directory rather than a list of filenames so it doesn't rot as new state files
 * appear (uses.json, lang_prefs.json, my-localities.csv, …).
 */
class ClearAppDataRule : ExternalResource() {
    override fun before() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        ctx.filesDir.listFiles()?.forEach { it.deleteRecursively() }
    }
}

/** Save one note for [s], through the same public draft path the editor drives. UI thread only. */
fun MainViewModel.seedNote(s: Species) {
    startAdd()
    pickSpecies(s)
    createNewLocality(63.43, 10.39, 100, SEED_LOCALITY)
    save()
}

/** Save one note by Norwegian name — the readable form when a test asserts on that name. */
fun MainViewModel.seedNote(norsk: String) = seedNote(species.first { it.norsk == norsk })

const val SEED_LOCALITY = "Testlokalitet"
