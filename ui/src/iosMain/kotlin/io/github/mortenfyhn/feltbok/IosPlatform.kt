@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package io.github.mortenfyhn.feltbok

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSBundle
import platform.Foundation.NSData
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileHandle
import platform.Foundation.NSFileManager
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.closeFile
import platform.Foundation.create
import platform.Foundation.dataTaskWithRequest
import platform.Foundation.dataUsingEncoding
import platform.Foundation.fileHandleForWritingAtPath
import platform.Foundation.seekToEndOfFile
import platform.Foundation.setValue
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeData
import platform.Foundation.writeToFile
import kotlin.coroutines.resume

/** [Storage] on iOS: private files in the app's Documents folder, bundled data in the app bundle. */
class IosStorage : Storage {
    private val dir: String = (
        NSFileManager.defaultManager.URLsForDirectory(NSDocumentDirectory, NSUserDomainMask).first() as NSURL
        ).path!!

    private fun path(name: String) = "$dir/$name"

    private fun readPath(p: String): String? = NSString.stringWithContentsOfFile(p, NSUTF8StringEncoding, null)

    // Kotlin/Native bridges String to NSString at runtime; the compiler can't see that.
    @Suppress("CAST_NEVER_SUCCEEDS")
    private fun ns(text: String) = text as NSString

    override fun exists(name: String) = NSFileManager.defaultManager.fileExistsAtPath(path(name))

    override fun read(name: String): String? = readPath(path(name))

    // atomically = Foundation's own temp file + rename, so a crash leaves the old file or the new.
    override fun write(name: String, text: String) {
        val ok = ns(text).writeToFile(path(name), atomically = true, encoding = NSUTF8StringEncoding, error = null)
        check(ok) { "write failed: $name" }
    }

    override fun append(name: String, text: String) {
        val p = path(name)
        if (!exists(name)) return write(name, text)
        val handle = NSFileHandle.fileHandleForWritingAtPath(p) ?: error("append failed: $name")
        handle.seekToEndOfFile()
        handle.writeData(ns(text).dataUsingEncoding(NSUTF8StringEncoding)!!)
        handle.closeFile()
    }

    override fun delete(name: String) {
        NSFileManager.defaultManager.removeItemAtPath(path(name), null)
    }

    override fun list(): List<String> =
        NSFileManager.defaultManager.contentsOfDirectoryAtPath(dir, null)?.filterIsInstance<String>() ?: emptyList()

    // The app bundles app/src/main/assets as an assets/ folder (iosApp/project.yml), as Android does.
    override fun readAsset(name: String): String? {
        val dot = name.lastIndexOf('.')
        return NSBundle.mainBundle.pathForResource(name.substring(0, dot), name.substring(dot + 1), "assets")?.let(::readPath)
    }
}

/** The toast on screen, if any; drawn by the iOS root (MainViewController.kt). */
internal object IosToast {
    class Shown(val text: String, val long: Boolean, val token: Long)

    var current by mutableStateOf<Shown?>(null)
    private var next = 0L

    fun show(text: String, long: Boolean) {
        current = Shown(text, long, next++)
    }
}

/** [Platform] on iOS. */
class IosPlatform : Platform {
    override val storage = IosStorage()
    override val location = LocationTracker()

    override fun toast(message: String, long: Boolean) = IosToast.show(message, long)

    override suspend fun httpGet(url: String, headers: Map<String, String>, timeoutMs: Int): String? {
        val nsUrl = NSURL.URLWithString(url) ?: return null
        val request = NSMutableURLRequest.requestWithURL(nsUrl).apply {
            setTimeoutInterval(timeoutMs / 1000.0)
            headers.forEach { (k, v) -> setValue(v, forHTTPHeaderField = k) }
        }
        return suspendCancellableCoroutine { cont ->
            val task = NSURLSession.sharedSession.dataTaskWithRequest(request) { data: NSData?, response, error ->
                val ok = error == null && (response as? NSHTTPURLResponse)?.statusCode?.toInt() in 200..299
                cont.resume(if (ok && data != null) NSString.create(data, NSUTF8StringEncoding)?.toString() else null)
            }
            cont.invokeOnCancellation { task.cancel() }
            task.resume()
        }
    }
}
