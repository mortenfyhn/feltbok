package io.github.mortenfyhn.feltbok

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.flow.StateFlow

/** What the view model needs from the platform, so the same view model runs on Android and iOS. */
interface Platform {
    val storage: Storage
    val location: LocationSource

    /** A short message over whatever screen is showing; [long] for one worth reading twice. */
    fun toast(message: String, long: Boolean = false)

    /** The body of a successful GET of [url], or null on any failure (offline, timeout, HTTP error). */
    suspend fun httpGet(url: String, headers: Map<String, String>, timeoutMs: Int): String?

    /** Mirror the export somewhere a user can reach without the app (Android: Downloads), so a
     *  crash can't trap field notes (#85). Never throws. */
    fun backupExport(notes: List<Note>) {}

    /** Dev builds: import a pushed seed into notes.json, before it's loaded. */
    fun importSeedNotes() {}
}

/** GPS fixes while started. */
interface LocationSource {
    val fix: StateFlow<GpsFix?>
    fun start()
    fun stop()
}

/** Platform services that act on the screen being shown (on Android they need the Activity, for
 *  dialogs and intents). Get one with [rememberPlatformUi]. */
interface PlatformUi {
    /** Device and OS, for feedback reports, e.g. "Google Pixel 7\nAndroid 14 (API 34)". */
    val deviceInfo: String

    fun openUrl(url: String)

    /** Open the mail app on a `mailto:` URI. */
    fun sendMail(uri: String)

    fun toast(message: String)

    /** The platform date picker, seeded on [initialMs]; [onPick] gets it with the date replaced and
     *  the time of day kept. [clearLabel] adds a button that calls [onClear] instead. */
    fun pickDate(initialMs: Long, onPick: (Long) -> Unit, clearLabel: String? = null, onClear: () -> Unit = {})

    /** The platform time picker; [onPick] gets [initialMs] with the time of day replaced. */
    fun pickTime(initialMs: Long, onPick: (Long) -> Unit)
}

@Composable
expect fun rememberPlatformUi(): PlatformUi

/** System back (Android's back button and gesture) while [enabled]. */
@Composable
expect fun BackHandler(enabled: Boolean = true, onBack: () -> Unit)

/** A clipboard entry holding plain [text]. */
expect fun plainTextClip(text: String): ClipEntry

/** Text style that drops the line-height font padding (Android's includeFontPadding), so a lone
 *  glyph sits in the visual centre of a small badge. */
expect val noFontPadding: PlatformTextStyle?

/** Properties for a dialog that fills the screen and lets the keyboard inset reach the content. */
expect fun fullScreenDialogProperties(): DialogProperties

/** The locality picker map. */
@Composable
expect fun LocalityScreen(vm: MainViewModel)

/** The small map on the editor's locality row. */
@Composable
expect fun LocalityPreview(vm: MainViewModel, modifier: Modifier = Modifier)
