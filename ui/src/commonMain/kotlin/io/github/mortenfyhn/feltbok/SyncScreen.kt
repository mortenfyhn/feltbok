package io.github.mortenfyhn.feltbok

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// The login happens in a web view, whose cookie store then holds the session. The app reads the
// cookie from there for its own requests (Platform.siteCookie): the map's ByBoundingBox fetch and
// the user's own sites from ByUser (MainViewModel.refreshMySites).

/** The picker map's footer. Logged in, it updates your own localities in place; logged out, it opens
 *  the login. Logged in, the map also gets all new public localities in dense areas. Styled like
 *  the list's footer links. */
@Composable
fun PickerFooter(vm: MainViewModel, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    LaunchedEffect(Unit) { vm.checkSession() }
    val loggedIn = vm.loggedIn
    Column(modifier.fillMaxWidth().background(cs.surface)) {
        HorizontalDivider(color = cs.outline.copy(alpha = 0.4f))
        Row(
            Modifier.clickable(enabled = !vm.mySitesBusy) { if (loggedIn) vm.updateLocalities() else vm.openSync() }
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Wraps rather than clip its last word with large text (#198); the spinner keeps its room.
            Text(if (loggedIn) Strings.Sync.update else Strings.Sync.loginForPrivate,
                color = cs.primary, fontWeight = FontWeight.Medium, fontSize = 13.sp,
                modifier = Modifier.weight(1f, fill = false))
            if (vm.mySitesBusy) CircularProgressIndicator(Modifier.size(14.dp), color = cs.primary, strokeWidth = 2.dp)
        }
    }
}

/** The platform web view the sync screen drives (Android: WebView, iOS: WKWebView). */
interface SyncWeb {
    fun load(url: String)

    /** Make sure the session cookie outlives the app process. */
    fun persistSession() {}

    @Composable
    fun View(modifier: Modifier)
}

/** A [SyncWeb] that reports finished page loads. */
@Composable
expect fun rememberSyncWeb(onPageFinished: (SyncWeb, String) -> Unit): SyncWeb

/**
 * The Artsobservasjoner login, from the picker's footer. Once the login lands back on the site, the
 * screen closes and the picker fetches your own localities. The session lives only in the web
 * view's cookie store.
 */
@Composable
fun SyncScreen(vm: MainViewModel) {
    val web = rememberSyncWeb(
        onPageFinished = { view, url ->
            // A login page is either the same-host login path or, should the site move to an
            // external SSO, any off-site host.
            val onSite = url.startsWith(Country.sitesHost) && !url.contains(Country.loginPathMarker)
            if (onSite && vm.screen == Screen.SYNC) {   // the landing can report more than one page
                view.persistSession()   // keep the session for next time
                vm.closeSync(loggedIn = true)
            }
        },
    )
    LaunchedEffect(Unit) { web.load(Country.syncLoginUrl) }

    Column(Modifier.fillMaxSize().bottomInsetPadding()) {
        ScreenHeader(Strings.Sync.login, onCancel = { vm.closeSync() })
        web.View(Modifier.weight(1f).fillMaxWidth())
    }
}
