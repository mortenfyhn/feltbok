@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package io.github.mortenfyhn.feltbok

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.HTTPShouldHandleCookies
import platform.Foundation.NSBundle
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileHandle
import platform.Foundation.NSFileManager
import platform.Foundation.NSHTTPCookie
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSURLErrorDataNotAllowed
import platform.Foundation.NSURLErrorInternationalRoamingOff
import platform.Foundation.NSURLErrorNotConnectedToInternet
import platform.Foundation.NSURLSession
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.closeFile
import platform.Foundation.create
import platform.Foundation.dataTaskWithRequest
import platform.Foundation.dataUsingEncoding
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.fileHandleForWritingAtPath
import platform.Foundation.seekToEndOfFile
import platform.Foundation.setValue
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeData
import platform.Foundation.writeToFile
import platform.WebKit.WKWebsiteDataStore
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
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

    override suspend fun httpGet(url: String, headers: Map<String, String>, timeoutMs: Int): HttpResult {
        val nsUrl = NSURL.URLWithString(url) ?: return NoAnswer(offline = false)
        val request = NSMutableURLRequest.requestWithURL(nsUrl).apply {
            setTimeoutInterval(timeoutMs / 1000.0)
            headers.forEach { (k, v) -> setValue(v, forHTTPHeaderField = k) }
            // Otherwise NSHTTPCookieStorage (a different store from the WebView's) may replace it.
            if ("Cookie" in headers) HTTPShouldHandleCookies = false
        }
        return suspendCancellableCoroutine { cont ->
            val task = NSURLSession.sharedSession.dataTaskWithRequest(request) { data: NSData?, response, error ->
                val status = (response as? NSHTTPURLResponse)?.statusCode?.toInt()
                val body = data?.let { NSString.create(it, NSUTF8StringEncoding)?.toString() }.orEmpty()
                val offline = error?.code in listOf(
                    NSURLErrorNotConnectedToInternet, NSURLErrorDataNotAllowed, NSURLErrorInternationalRoamingOff)
                cont.resume(if (error == null && status != null) HttpResponse(status, body) else NoAnswer(offline))
            }
            cont.invokeOnCancellation { task.cancel() }
            task.resume()
        }
    }

    // NSURLSession doesn't see WKWebView's cookies, so read the WebView's store (main thread only).
    override suspend fun siteCookie(): String? {
        val host = NSURL.URLWithString(Country.sitesHost)?.host ?: return null
        val cookies = suspendCancellableCoroutine { cont ->
            dispatch_async(dispatch_get_main_queue()) {
                WKWebsiteDataStore.defaultDataStore().httpCookieStore.getAllCookies { all ->
                    cont.resume(all.orEmpty().filterIsInstance<NSHTTPCookie>())
                }
            }
        }
        return cookies.filter { it.domain.removePrefix(".").let { d -> host == d || host.endsWith(".$d") } }
            .joinToString("; ") { "${it.name}=${it.value}" }.ifEmpty { null }
    }

    override fun clearCookies() = dispatch_async(dispatch_get_main_queue()) {
        WKWebsiteDataStore.defaultDataStore().removeDataOfTypes(
            WKWebsiteDataStore.allWebsiteDataTypes(), NSDate.dateWithTimeIntervalSince1970(0.0)) {}
    }
}
