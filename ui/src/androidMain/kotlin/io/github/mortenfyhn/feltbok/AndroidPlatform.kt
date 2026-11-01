package io.github.mortenfyhn.feltbok

import android.app.Application
import android.content.Context
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** [Platform] on Android. */
class AndroidPlatform(private val app: Application) : Platform {
    override val storage = AndroidStorage(app)
    override val location = LocationTracker(app)

    override fun toast(message: String, long: Boolean) =
        Toast.makeText(app, message, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()

    override suspend fun httpGet(url: String, headers: Map<String, String>, timeoutMs: Int): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 5000
                c.readTimeout = timeoutMs
                headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
                try { c.inputStream.bufferedReader().readText() } finally { c.disconnect() }
            }.getOrNull()
        }

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
