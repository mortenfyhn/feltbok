@file:OptIn(ExperimentalForeignApi::class)

package io.github.mortenfyhn.feltbok

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readValue
import platform.CoreGraphics.CGRectZero
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.UIKit.UIColor
import platform.WebKit.WKNavigation
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKScriptMessage
import platform.WebKit.WKScriptMessageHandlerProtocol
import platform.WebKit.WKUserContentController
import platform.WebKit.WKUserScript
import platform.WebKit.WKUserScriptInjectionTime
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.WebKit.WKWebsiteDataStore
import platform.darwin.NSObject

/** Defines the name the fetch script calls (Android's JS interface), forwarding to WebKit's bridge,
 *  so the script is the same on both platforms. */
private const val BRIDGE_JS =
    "window.FeltbokSync = { deliver: function (s) { window.webkit.messageHandlers.FeltbokSync.postMessage(s); } };"

private class IosSyncWeb(
    onPageFinished: (SyncWeb, String) -> Unit,
    onDelivered: (SyncWeb, String) -> Unit,
) : SyncWeb {
    private val handler = object : NSObject(), WKScriptMessageHandlerProtocol {
        override fun userContentController(userContentController: WKUserContentController, didReceiveScriptMessage: WKScriptMessage) {
            onDelivered(this@IosSyncWeb, didReceiveScriptMessage.body as? String ?: "")
        }
    }

    private val navigation = object : NSObject(), WKNavigationDelegateProtocol {
        override fun webView(webView: WKWebView, didFinishNavigation: WKNavigation?) {
            webView.URL?.absoluteString?.let { onPageFinished(this@IosSyncWeb, it) }
        }
    }

    val view: WKWebView = WKWebView(
        frame = CGRectZero.readValue(),
        configuration = WKWebViewConfiguration().apply {
            websiteDataStore = WKWebsiteDataStore.defaultDataStore() // persistent: keeps the session
            // Identify ourselves to the site operator; the stock Safari UA stays intact in front.
            applicationNameForUserAgent = "Feltbok/${AppInfo.versionName}"
            userContentController.addUserScript(
                WKUserScript(BRIDGE_JS, WKUserScriptInjectionTime.WKUserScriptInjectionTimeAtDocumentStart, true),
            )
            userContentController.addScriptMessageHandler(handler, "FeltbokSync")
        },
    ).apply {
        navigationDelegate = navigation
        // No white flash before the page paints over the header colour (as on Android).
        opaque = false
        backgroundColor = UIColor.clearColor
    }

    override fun load(url: String) {
        NSURL.URLWithString(url)?.let { view.loadRequest(NSURLRequest.requestWithURL(it)) }
    }

    override fun runFetch() = view.evaluateJavaScript(Country.mySitesFetchJs, null)

    @Composable
    override fun View(modifier: Modifier) = UIKitView(factory = { view }, modifier = modifier)

    fun dispose() {
        // The content controller holds the handler strongly; drop it so neither leaks.
        view.configuration.userContentController.removeScriptMessageHandlerForName("FeltbokSync")
        view.navigationDelegate = null
        view.stopLoading()
    }
}

@Composable
actual fun rememberSyncWeb(onPageFinished: (SyncWeb, String) -> Unit, onDelivered: (SyncWeb, String) -> Unit): SyncWeb {
    val web = remember { IosSyncWeb(onPageFinished, onDelivered) }
    DisposableEffect(Unit) { onDispose { web.dispose() } }
    return web
}
