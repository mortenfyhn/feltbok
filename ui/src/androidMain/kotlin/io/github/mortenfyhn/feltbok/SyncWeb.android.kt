package io.github.mortenfyhn.feltbok

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView

@SuppressLint("SetJavaScriptEnabled")
private class AndroidSyncWeb(
    ctx: Context,
    onPageFinished: (SyncWeb, String) -> Unit,
    onDelivered: (SyncWeb, String) -> Unit,
) : SyncWeb {
    val view: WebView = WebView(ctx).apply {
        // Default WebView background is white; on first attach it flashes full-bleed for a frame
        // before Compose settles it into its slot, briefly whitening the green header area. A
        // transparent background lets the grey behind show through instead of that white flash.
        setBackgroundColor(Color.TRANSPARENT)
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        // Identify ourselves to the site operator; the stock WebView UA stays intact in front.
        settings.userAgentString = "${settings.userAgentString} Feltbok/${AppInfo.versionName}"
        addJavascriptInterface(
            object {
                @JavascriptInterface
                fun deliver(json: String) {
                    post { onDelivered(this@AndroidSyncWeb, json) } // bridge runs off-thread; hop to the UI thread
                }
            },
            "FeltbokSync",
        )
        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                if (url != null) onPageFinished(this@AndroidSyncWeb, url)
            }
        }
    }

    override fun load(url: String) = view.loadUrl(url)

    override fun runFetch() = view.evaluateJavascript(Country.mySitesFetchJs, null)

    override fun persistSession() = CookieManager.getInstance().flush()

    @Composable
    override fun View(modifier: Modifier) = AndroidView(factory = { view }, modifier = modifier)
}

@Composable
actual fun rememberSyncWeb(onPageFinished: (SyncWeb, String) -> Unit, onDelivered: (SyncWeb, String) -> Unit): SyncWeb {
    val ctx = LocalContext.current
    val web = remember { AndroidSyncWeb(ctx, onPageFinished, onDelivered) }
    DisposableEffect(Unit) { onDispose { web.view.destroy() } }
    return web
}
