package io.github.mortenfyhn.feltbok

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

@OptIn(ExperimentalComposeUiApi::class)
actual fun plainTextClip(text: String) = ClipEntry.withPlainText(text)

actual val noFontPadding: PlatformTextStyle? = null

actual fun fullScreenDialogProperties() = DialogProperties(usePlatformDefaultWidth = false)

/** On iOS 15, Compose's default-font fallback draws ♂/♀ cut (the ♀ loses the top of its circle;
 *  likely Skia cropping to bounds that font under-reports). The serif fallback draws them whole. It
 *  has no bold, so the glyph outline is stroked instead, thickening its thin lines like a bold. */
actual val boldSymbolStyle = SpanStyle(fontFamily = FontFamily.Serif, drawStyle = Stroke(width = 2.2f))

/** Visible, not Ellipsis: ♂/♀ come from a fallback font whose glyphs overshoot the line box on older
 *  iOS, and Ellipsis clips to that box. Nothing is lost: the row only shows the preview when it fits
 *  whole. */
actual val previewOverflow = TextOverflow.Visible

/** Clear of the left-edge back-swipe zone (~20 pt), where UIKit's edge recognizer cancels a tap that
 *  moves even slightly, so the ✕ would only hit on its right half. */
actual val headerLeadingInset = 8.dp
