package io.github.mortenfyhn.feltbok

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.ClipData
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import java.util.Calendar
import androidx.activity.compose.BackHandler as ActivityBackHandler

private class AndroidPlatformUi(private val ctx: Context) : PlatformUi {
    override val deviceInfo =
        "${Build.MANUFACTURER} ${Build.MODEL}\nAndroid ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

    override fun openUrl(url: String) {
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    override fun sendMail(uri: String) {
        runCatching { ctx.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse(uri))) }
    }

    override fun toast(message: String) = Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show()

    override fun pickDate(initialMs: Long, onPick: (Long) -> Unit, clearLabel: String?, onClear: () -> Unit) {
        val cal = Calendar.getInstance().apply { timeInMillis = initialMs }
        DatePickerDialog(ctx, { _, y, mo, d ->
            cal.set(Calendar.YEAR, y); cal.set(Calendar.MONTH, mo)
            cal.set(Calendar.DAY_OF_MONTH, d); onPick(cal.timeInMillis)
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).apply {
            // Takes over the picker's own "Avbryt" (set before show(), so ours wins).
            if (clearLabel != null) setButton(DialogInterface.BUTTON_NEGATIVE, clearLabel) { _, _ -> onClear() }
        }.show()
    }

    override fun pickTime(initialMs: Long, onPick: (Long) -> Unit) {
        val cal = Calendar.getInstance().apply { timeInMillis = initialMs }
        TimePickerDialog(ctx, { _, h, mi ->
            cal.set(Calendar.HOUR_OF_DAY, h); cal.set(Calendar.MINUTE, mi)
            onPick(cal.timeInMillis)
        }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true).show()
    }
}

// The Activity, not the application: the dialogs need its theme and window, and starting an
// activity from it needs no NEW_TASK flag.
@Composable
actual fun rememberPlatformUi(): PlatformUi {
    val ctx = LocalContext.current
    return remember(ctx) { AndroidPlatformUi(ctx) }
}

@Composable
actual fun BackHandler(enabled: Boolean, onBack: () -> Unit) = ActivityBackHandler(enabled, onBack)

// The label is unused by the import form, so null.
actual fun plainTextClip(text: String) = ClipEntry(ClipData.newPlainText(null, text))

actual val noFontPadding: PlatformTextStyle? = PlatformTextStyle(includeFontPadding = false)

// decorFitsSystemWindows = false so the keyboard inset reaches safeDrawingPadding instead of the
// dialog window swallowing it (the activity is edge-to-edge, so nothing resizes on its own).
actual fun fullScreenDialogProperties() = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)

actual val boldSymbolStyle = SpanStyle(fontWeight = FontWeight.Bold)

actual val previewOverflow = TextOverflow.Ellipsis

actual val headerLeadingInset = 0.dp
