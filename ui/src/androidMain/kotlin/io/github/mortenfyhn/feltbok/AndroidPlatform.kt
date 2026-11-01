package io.github.mortenfyhn.feltbok

import android.app.Application
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import java.io.File

/** [Platform] on Android. */
class AndroidPlatform(private val app: Application) : Platform {
    override val storage = AndroidStorage(app)
    override val location = LocationTracker(app)

    override fun toast(message: String, long: Boolean) =
        Toast.makeText(app, message, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()

    override suspend fun searchUsers(query: String) = searchAoUsers(query)

    override suspend fun fetchSites(s: Double, w: Double, n: Double, e: Double) = fetchSitesHttp(s, w, n, e)

    override fun backupExport(notes: List<Note>) = backupExport(app, notes)

    override fun importSeedNotes() = importSeedNotes(app)
}

/** How Android (and the instrumented tests) build the view model. */
fun MainViewModel(app: Application) = MainViewModel(AndroidPlatform(app))

/**
 * Dev seeding. The debug build ships non-debuggable (it runs at release speed), so `run-as` can't
 * reach its files, and notes.json lives in internal storage that `adb push` can't write either. So
 * `just seed` pushes a seed file to the external files dir (which adb push *can* reach), and
 * the dev build imports it into notes.json on the next launch and deletes it. Gated to the `.debug`
 * package so the real release app never imports a pushed file. Call before [loadNotes].
 */
private fun importSeedNotes(ctx: Context) {
    if (!ctx.packageName.endsWith(".debug")) return
    val seed = File(ctx.getExternalFilesDir(null), "seed-notes.json")
    if (!seed.exists()) return
    runCatching {
        File(ctx.filesDir, "notes.json").writeText(seed.readText())
        seed.delete()
    }
}

/**
 * Best-effort mirror of the export into the public Downloads folder, refreshed on every persist.
 * notes.json lives in private internal storage, which a non-debuggable release seals off (no
 * run-as, no pull) - so a UI crash like #85 left field observations unreachable until a rebuild.
 * A submit-ready TSV in Downloads is recoverable with any file manager, and survives even an
 * uninstall. Q+ only: MediaStore needs no permission there; older devices would need a runtime
 * storage grant, not worth it. Never throws - a failed backup must not break saving.
 */
private fun backupExport(ctx: Context, notes: List<Note>) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
    runCatching {
        val name = "feltbok-sikkerhetskopi.tsv"
        val resolver = ctx.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        // Reuse the existing mirror (matched by its distinctive name) so saves overwrite one
        // file instead of accumulating copies; otherwise mint a fresh one under Downloads/Feltbok.
        val existing = resolver.query(
            collection, arrayOf(MediaStore.Downloads._ID),
            "${MediaStore.Downloads.DISPLAY_NAME}=?", arrayOf(name), null,
        )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
        val uri = existing?.let { ContentUris.withAppendedId(collection, it) }
            ?: resolver.insert(
                collection,
                ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "text/tab-separated-values")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Feltbok")
                },
            ) ?: return@runCatching
        resolver.openOutputStream(uri, "wt")?.use { it.write(exportTsv(notes).toByteArray()) }
    }
}
