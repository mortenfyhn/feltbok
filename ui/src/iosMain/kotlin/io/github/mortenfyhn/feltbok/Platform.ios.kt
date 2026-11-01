package io.github.mortenfyhn.feltbok

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.window.DialogProperties

@Composable
actual fun BackHandler(enabled: Boolean, onBack: () -> Unit) {}

@OptIn(ExperimentalComposeUiApi::class)
actual fun plainTextClip(text: String) = ClipEntry.withPlainText(text)

actual val noFontPadding: PlatformTextStyle? = null

actual fun fullScreenDialogProperties() = DialogProperties(usePlatformDefaultWidth = false)
