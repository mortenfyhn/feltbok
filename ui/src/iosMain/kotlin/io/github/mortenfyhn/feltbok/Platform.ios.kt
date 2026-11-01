package io.github.mortenfyhn.feltbok

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.window.DialogProperties

// Placeholders so the shared UI compiles for iOS. The iOS app (port step 4, #115) replaces them
// with the spike's real versions: UIDatePicker sheets, a MapLibre map, a WKWebView sync screen.

private object PendingPlatformUi : PlatformUi {
    override val deviceInfo = "iOS"
    override fun openUrl(url: String) {}
    override fun sendMail(uri: String) {}
    override fun toast(message: String) {}
    override fun pickDate(initialMs: Long, onPick: (Long) -> Unit, clearLabel: String?, onClear: () -> Unit) {}
    override fun pickTime(initialMs: Long, onPick: (Long) -> Unit) {}
}

@Composable
actual fun rememberPlatformUi(): PlatformUi = remember { PendingPlatformUi }

@Composable
actual fun BackHandler(enabled: Boolean, onBack: () -> Unit) {}

@OptIn(ExperimentalComposeUiApi::class)
actual fun plainTextClip(text: String) = ClipEntry.withPlainText(text)

actual val noFontPadding: PlatformTextStyle? = null

actual fun fullScreenDialogProperties() = DialogProperties(usePlatformDefaultWidth = false)

@Composable
private fun Pending(what: String) = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("$what: kommer") }

@Composable
actual fun LocalityScreen(vm: MainViewModel) = Pending("Kart")

@Composable
actual fun LocalityPreview(vm: MainViewModel, modifier: Modifier) {}

@Composable
actual fun SyncScreen(vm: MainViewModel) = Pending("Hent mine lokaliteter")
