package com.appobs

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val SITES_HOST = "https://mobil.artsobservasjoner.no"

// Pages /core/Sites/ByUser from inside the logged-in WebView (the session cookies ride along
// automatically, so we never read or store them), accumulates the rows, and hands them back to
// Kotlin via the FeltbokSync bridge. Same-origin, so it only succeeds while on the sites host.
private val FETCH_JS = """
(async () => {
  try {
    let all = [], page = 1, total = 1;
    do {
      const r = await fetch('/core/Sites/ByUser?pageSize=100&pageNumber=' + page,
                            { headers: { 'X-CSRF': '1' }, credentials: 'same-origin' });
      if (r.status === 401 || r.status === 403) { FeltbokSync.deliver('{"error":"auth"}'); return; }
      if (!r.ok) { FeltbokSync.deliver('{"error":"http"}'); return; }
      const d = await r.json();
      (d.data || []).forEach(function(x) { all.push(x); });
      total = d.totalPages || 1; page++;
    } while (page <= total);
    FeltbokSync.deliver(JSON.stringify({ data: all }));
  } catch (e) { FeltbokSync.deliver('{"error":"js"}'); }
})();
""".trimIndent()

/**
 * "Synk mine lokaliteter": an optional online action. The user logs in to Artsobservasjoner in
 * a WebView; we then pull their own private localities via the mobile API and cache them locally
 * (yellow, alongside the bundled public ones). The session lives only in this WebView's cookie
 * store - the rest of the app stays offline-first and has no "logged in" state. The cookie
 * persists, so re-syncing later usually skips the login until the session expires.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SyncScreen(vm: MainViewModel) {
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("Logg inn på Artsobservasjoner, så henter vi lokalitetene dine.") }
    var done by remember { mutableStateOf(false) }
    var fetching by remember { mutableStateOf(false) }

    val webView = remember {
        WebView(ctx).apply {
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            addJavascriptInterface(object {
                @JavascriptInterface
                fun deliver(json: String) { this@apply.post {   // bridge runs off-thread; hop to the UI thread
                    scope.launch {
                        when {
                            json.contains("\"error\":\"auth\"") -> {
                                fetching = false
                                status = "Logg inn over, så henter vi automatisk."
                            }
                            json.contains("\"error\"") -> {
                                fetching = false
                                status = "Noe gikk galt under henting. Prøv igjen."
                            }
                            else -> {
                                val sites = withContext(Dispatchers.Default) { parseMySites(json) }
                                vm.applyMySites(sites)
                                CookieManager.getInstance().flush()   // persist the session for next time
                                done = true; fetching = false
                                status = "Hentet ${sites.size} av dine lokaliteter ✓"
                                delay(1200); vm.closeSync()
                            }
                        }
                    }
                } }
            }, "FeltbokSync")
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) {
                    // Once we're (back) on the sites host, try the fetch: succeeds if logged in,
                    // 401s into the "log in" hint otherwise. Skipped on the identity-provider pages.
                    if (!done && !fetching && url != null && url.startsWith(SITES_HOST)) {
                        fetching = true
                        view.evaluateJavascript(FETCH_JS, null)
                    }
                }
            }
            loadUrl("$SITES_HOST/my-sites")
        }
    }
    DisposableEffect(Unit) { onDispose { webView.destroy() } }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().background(cs.primary).padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Synk mine lokaliteter", color = Color.White, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f))
            if (!done) TextButton(onClick = {
                if (!fetching) { fetching = true; status = "Henter…"; webView.evaluateJavascript(FETCH_JS, null) }
            }) { Text("Hent", color = Color.White) }
            TextButton(onClick = { vm.closeSync() }) { Text("Lukk", color = Color.White) }
        }
        Text(status, color = cs.onSurfaceVariant, modifier = Modifier.padding(14.dp))
        AndroidView(factory = { webView }, modifier = Modifier.weight(1f).fillMaxWidth())
    }
}
