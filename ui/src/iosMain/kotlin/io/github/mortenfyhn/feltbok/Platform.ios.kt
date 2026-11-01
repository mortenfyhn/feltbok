package io.github.mortenfyhn.feltbok

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.window.DialogProperties

// Placeholders for the map and sync screens until the MapLibre and WKWebView versions land.

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
