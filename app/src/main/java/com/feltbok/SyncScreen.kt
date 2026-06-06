package com.feltbok

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val SITES_HOST = "https://mobil.artsobservasjoner.no"
private const val LOGIN_URL = "$SITES_HOST/bff/login?returnUrl=/my-page"

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

/** The flow the screen walks through. The WebView is only ever shown to the user during [LOGIN];
 *  every other stage covers it with a Feltbok-native surface, so the third-party login never
 *  ambushes them. */
private enum class Stage { CHECKING, INTRO, LOGIN, FETCHING, DONE, ERROR }

/**
 * "Hent mine lokaliteter": an optional online action. We first probe silently with the cached
 * session cookie; if it is still valid the user's own private localities are pulled and cached
 * locally (yellow, alongside the bundled public ones) without ever showing a browser. Only when
 * the probe says "not logged in" do we explain what's about to happen and, on the user's go-ahead,
 * reveal the Artsobservasjoner login. The session lives only in this WebView's cookie store - the
 * rest of the app stays offline-first and has no "logged in" state.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SyncScreen(vm: MainViewModel) {
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var stage by remember { mutableStateOf(Stage.CHECKING) }
    var result by remember { mutableStateOf<SyncDiff?>(null) }
    var inFlight by remember { mutableStateOf(false) }   // guards against re-entrant fetches

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
                        inFlight = false
                        when {
                            json.contains("\"error\":\"auth\"") ->
                                // Not logged in. During the login flow we just keep waiting; otherwise
                                // this is the probe telling us we need to explain and log in.
                                if (stage != Stage.LOGIN) stage = Stage.INTRO
                            json.contains("\"error\"") -> stage = Stage.ERROR
                            else -> {
                                val sites = withContext(Dispatchers.Default) { parseMySites(json) }
                                val diff = runCatching { vm.applyMySites(sites) }.getOrNull()
                                if (diff != null) {
                                    CookieManager.getInstance().flush()   // persist the session for next time
                                    result = diff
                                    stage = Stage.DONE
                                    delay(1600); vm.closeSync()
                                } else stage = Stage.ERROR
                            }
                        }
                    }
                } }
            }, "FeltbokSync")
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) {
                    if (inFlight || url == null || !url.startsWith(SITES_HOST)) return
                    when (stage) {
                        // Probe with the cached cookie on any host page (a 401 routes us to INTRO).
                        Stage.CHECKING -> { inFlight = true; view.evaluateJavascript(FETCH_JS, null) }
                        // After login the BFF returns us to a content page; the login page itself
                        // (/bff/...) must not trigger a premature fetch.
                        Stage.LOGIN -> if (!url.contains("/bff/")) {
                            inFlight = true; stage = Stage.FETCHING; view.evaluateJavascript(FETCH_JS, null)
                        }
                        else -> {}
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
            Text(
                when {
                    stage == Stage.LOGIN -> "Logg inn på Artsobservasjoner"
                    vm.localities.any { it.mine } -> "Oppdater lokaliteter"
                    else -> "Hent mine lokaliteter"
                },
                color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            TextButton(onClick = { vm.closeSync() }) { Text("Avbryt", color = Color.White) }
        }
        if (stage == Stage.LOGIN)
            Text("Etter innlogging henter vi lokalitetene automatisk.",
                color = cs.onSurfaceVariant, modifier = Modifier.padding(14.dp))

        // The WebView stays mounted the whole time (it holds the session cookie and does the probe);
        // every non-login stage simply covers it with a Feltbok surface.
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
            if (stage != Stage.LOGIN) StageContent(stage, result, cs,
                onLogin = { stage = Stage.LOGIN; webView.loadUrl(LOGIN_URL) },
                onRetry = { stage = Stage.CHECKING; webView.loadUrl("$SITES_HOST/my-sites") })
        }
    }
}

/** Confirmation wording: first fetch, a fetch that changed something, or nothing new. */
private fun doneMessage(d: SyncDiff?): String = when {
    d == null -> "Ferdig ✓"
    d.firstSync -> "Hentet ${d.total} lokaliteter ✓"
    d.changed > 0 -> "Hentet ${d.total} lokaliteter (${d.changed} endret) ✓"
    else -> "Allerede oppdatert (${d.total} lokaliteter) ✓"
}

@Composable
private fun StageContent(
    stage: Stage,
    result: SyncDiff?,
    cs: androidx.compose.material3.ColorScheme,
    onLogin: () -> Unit,
    onRetry: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().background(cs.background).padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (stage) {
            Stage.CHECKING -> CircularProgressIndicator(color = cs.primary)
            Stage.INTRO -> {
                Text(
                    "Feltbok kan hente dine private lokaliteter fra Artsobservasjoner. Trykk på knappen for å logge inn, så går resten av seg selv.",
                    color = cs.onSurface, textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(20.dp))
                Button(onClick = onLogin) { Text("Logg inn på Artsobservasjoner") }
            }
            Stage.FETCHING -> {
                CircularProgressIndicator(color = cs.primary)
                Spacer(Modifier.height(12.dp))
                Text("Henter lokaliteter…", color = cs.onSurfaceVariant)
            }
            Stage.DONE -> Text(doneMessage(result), textAlign = TextAlign.Center,
                color = cs.primary, fontWeight = FontWeight.SemiBold)
            Stage.ERROR -> {
                Text("Noe gikk galt under henting.", color = cs.onSurface, textAlign = TextAlign.Center)
                Spacer(Modifier.height(20.dp))
                Button(onClick = onRetry) { Text("Prøv igjen") }
            }
            Stage.LOGIN -> {}
        }
    }
}
