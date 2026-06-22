package io.github.mortenfyhn.feltbok

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// The sites host, login/probe URLs, the my-sites fetch JS, and the response parser are all
// per-country (see Country.kt): Norway pages /core/Sites/ByUser on mobil.artsobservasjoner.no;
// Sweden POSTs GetEditableSitesGeoJson on artportalen.se. The fetch runs same-origin inside the
// logged-in WebView so the session cookie rides along and we never read or store it.

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
            // Default WebView background is white; on first attach it flashes full-bleed for a frame
            // before Compose settles it into its slot, briefly whitening the green header area. A
            // transparent background lets the grey behind show through instead of that white flash.
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
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
                                val sites = withContext(Dispatchers.Default) { Country.mySitesParse(json) }
                                val diff = runCatching { vm.applyMySites(sites) }.getOrNull()
                                if (diff != null) {
                                    CookieManager.getInstance().flush()   // persist the session for next time
                                    result = diff
                                    stage = Stage.DONE
                                } else stage = Stage.ERROR
                            }
                        }
                    }
                } }
            }, "FeltbokSync")
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) {
                    if (inFlight || url == null) return
                    val onSite = url.startsWith(Country.sitesHost)
                    // A login page is either the same-host login path or, when the site uses an
                    // external SSO (Sweden -> useradmin-auth.slu.se), any off-site host.
                    val onLoginPage = !onSite || url.contains(Country.loginPathMarker)
                    when (stage) {
                        // Probe: on the site, fetch with the cached cookie; bounced to a login page
                        // means we're not logged in, so explain and offer to log in.
                        Stage.CHECKING ->
                            if (onLoginPage) {
                                stage = Stage.INTRO
                            } else {
                                inFlight = true; view.evaluateJavascript(Country.mySitesFetchJs, null)
                            }
                        // After login we land back on the site; login pages must not trigger a fetch.
                        Stage.LOGIN -> if (onSite && !url.contains(Country.loginPathMarker)) {
                            inFlight = true; stage = Stage.FETCHING; view.evaluateJavascript(Country.mySitesFetchJs, null)
                        }
                        else -> {}
                    }
                }
            }
            loadUrl(Country.syncProbeUrl)
        }
    }
    DisposableEffect(Unit) { onDispose { webView.destroy() } }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            when {
                stage == Stage.LOGIN -> Strings.Sync.login
                vm.localities.any { it.mine } -> Strings.Sync.update
                else -> Strings.Sync.fetch
            },
            onCancel = { vm.closeSync() },
        )
        if (stage == Stage.LOGIN)
            Text(Strings.Sync.afterLogin,
                color = cs.onSurfaceVariant, modifier = Modifier.padding(14.dp))

        // The WebView stays mounted the whole time (it holds the session cookie and does the probe);
        // every non-login stage simply covers it with a Feltbok surface.
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
            if (stage != Stage.LOGIN) StageContent(stage, result, cs,
                onLogin = { stage = Stage.LOGIN; webView.loadUrl(Country.syncLoginUrl) },
                onRetry = { stage = Stage.CHECKING; webView.loadUrl(Country.syncProbeUrl) },
                onDone = { vm.closeSync() })
        }
    }
}

/** Confirmation wording: first fetch, a fetch that changed something, or nothing new. */
private fun doneMessage(d: SyncDiff?): String = when {
    d == null -> Strings.Sync.doneGeneric
    d.firstSync -> Strings.Sync.doneFirst(d.total)
    d.changed > 0 -> Strings.Sync.doneChanged(d.total, d.changed)
    else -> Strings.Sync.doneUnchanged(d.total)
}

@Composable
private fun StageContent(
    stage: Stage,
    result: SyncDiff?,
    cs: androidx.compose.material3.ColorScheme,
    onLogin: () -> Unit,
    onRetry: () -> Unit,
    onDone: () -> Unit,
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
                    Strings.Sync.intro,
                    color = cs.onSurface, textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(20.dp))
                Button(onClick = onLogin) { Text(Strings.Sync.login) }
            }
            Stage.FETCHING -> {
                CircularProgressIndicator(color = cs.primary)
                Spacer(Modifier.height(12.dp))
                Text(Strings.Sync.fetching, color = cs.onSurfaceVariant)
            }
            Stage.DONE -> {
                Text(doneMessage(result), textAlign = TextAlign.Center,
                    color = cs.primary, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(20.dp))
                Button(onClick = onDone) { Text(Strings.Sync.done) }
            }
            Stage.ERROR -> {
                Text(Strings.Sync.error, color = cs.onSurface, textAlign = TextAlign.Center)
                Spacer(Modifier.height(20.dp))
                Button(onClick = onRetry) { Text(Strings.Sync.retry) }
            }
            Stage.LOGIN -> {}
        }
    }
}
