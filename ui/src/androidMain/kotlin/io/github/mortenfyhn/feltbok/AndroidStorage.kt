package io.github.mortenfyhn.feltbok

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** [Storage] on Android: private files in internal storage, bundled data in the APK's assets, and
 *  the external files dir that `adb push` can reach for dev overrides. */
class AndroidStorage(private val ctx: Context) : Storage {
    private fun file(name: String) = File(ctx.filesDir, name)

    private fun pushed(name: String) = File(ctx.getExternalFilesDir(null), name)

    override fun exists(name: String) = file(name).exists()

    override fun read(name: String): String? = file(name).takeIf { it.exists() }?.readText()

    /** Write to a sibling temp file, fsync, then rename over the target. A plain writeText truncates
     *  first, so a crash mid-save could leave the file empty or torn. The rename is atomic on the
     *  same filesystem, so a complete file exists at every instant; the fsync makes sure the new
     *  content is on disk before the rename points at it. */
    override fun write(name: String, text: String) {
        val f = file(name)
        val tmp = File(f.parentFile, f.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(text.toByteArray())
            out.fd.sync()
        }
        if (!tmp.renameTo(f)) throw IOException("rename failed: $tmp -> $f")
    }

    override fun append(name: String, text: String) = file(name).appendText(text)

    override fun delete(name: String) {
        file(name).delete()
    }

    override fun list(): List<String> = ctx.filesDir.list()?.toList() ?: emptyList()

    /** A pushed copy wins, so `adb push` can override a bundled CSV without a rebuild. */
    override fun readAsset(name: String): String? =
        readPushed(name) ?: runCatching { ctx.assets.open(name).bufferedReader().use { it.readText() } }.getOrNull()

    override fun readPushed(name: String): String? = pushed(name).takeIf { it.exists() }?.readText()
}
