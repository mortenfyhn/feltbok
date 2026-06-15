package com.feltbok

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// The app's single style sheet: every colour the UI uses lives here, so the look
// stays coherent and is easy to restyle from one place.

private val Moss = Color(0xFF5B7A2B)
private val MossDark = Color(0xFF41591D)

/** The comment "i" badge: a soft blue when the note has a public comment, pale grey for private-only. */
val BadgeBlue = Color(0xFF4985D4)
val BadgeGrey = Color(0xFFB0B5B8)

private val MossColors = lightColorScheme(
    primary = Moss,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE8C6),
    onPrimaryContainer = MossDark,
    secondary = MossDark,
    onSecondary = Color.White,
    background = Color(0xFFEEF1F0),
    onBackground = Color(0xFF1C2624),
    surface = Color.White,
    onSurface = Color(0xFF1C2624),
    surfaceVariant = Color(0xFFF2F5F3),
    onSurfaceVariant = Color(0xFF6B7A76),
    outline = Color(0xFFCBD3D0),
    error = Color(0xFFB3261E),
)

/** Wraps the app in its colour scheme. */
@Composable
fun AppTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = MossColors, content = content)

/** The map overlays' semantic colours (ARGB), in one place so the palette stays coherent:
 *  public localities are green, your own ones yellow, the GPS fix blue, a new spot magenta. */
object MapPalette {
    // Public locality — green
    const val GreenFill = 0x553C8C28L
    const val GreenFillPale = 0x1E3C8C28L     // big ones, so they don't dominate
    const val GreenStroke = 0xFF2E7D32L

    // Public superlocality (has sublocalities) — same green hue as a normal locality but clearly
    // darker, the way artsobs marks supers (bright vs deep green on the same hue, not a new hue).
    const val SuperFill = 0x55235015L
    const val SuperFillPale = 0x1E235015L

    // Point-dot supers are tiny, so a translucent fill barely reads; paint them near-solid so the
    // deep green actually shows. Only used for 0 m dots — circles/polygons stay pale so the
    // sub-localities inside a super stay visible and tappable.
    const val SuperFillSolid = 0xE62E7A33L
    const val SuperStroke = 0xFF173A14L

    // Your own locality — yellow
    const val YellowFill = 0x55E0A100L
    const val YellowFillPale = 0x1EE0A100L
    const val YellowStroke = 0xFFB8860BL

    // Selected locality — bold highlight (green hue, or yellow for your own)
    const val SelFill = 0xDD4CAF50L
    const val SelFillFaint = 0x224CAF50L
    const val SelStroke = 0xFF1B5E20L
    const val SelFillPriv = 0xDDE0A100L
    const val SelFillFaintPriv = 0x22E0A100L
    const val SelStrokePriv = 0xFF6B4F00L

    // GPS fix — blue dot + white ring + an accuracy halo
    const val Gps = 0xFF2962FFL
    const val GpsRing = 0xFFFFFFFFL
    const val GpsAccFill = 0x332962FFL        // preview screen (slightly stronger)
    const val GpsAccStroke = 0xAA2962FFL
    const val GpsAccFillFaint = 0x222962FFL   // picker screen
    const val GpsAccStrokeFaint = 0x882962FFL

    // Locality labels — dark text with a halo tinted by type (pale so the text stays legible).
    const val LabelText = 0xFF18250FL
    const val LabelHaloGreen = 0xF2DCEFC6L    // public
    const val LabelHaloSuper = 0xF2CFE5ADL    // public superlocality (deeper green than normal)
    const val LabelHaloYellow = 0xF2FFF7D8L   // your own
}
