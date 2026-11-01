package io.github.mortenfyhn.feltbok

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.ComposeUIViewController
import kotlinx.coroutines.delay
import platform.Foundation.NSBundle

/** The iOS app's root view controller, hosted by the Swift app (iosApp/). */
fun MainViewController() = ComposeUIViewController {
    val vm = remember {
        val info = NSBundle.mainBundle.infoDictionary
        AppInfo.gitVersion = GIT_VERSION
        AppInfo.versionName = info?.get("CFBundleShortVersionString") as? String ?: "dev"
        AppInfo.versionCode = (info?.get("CFBundleVersion") as? String)?.toIntOrNull() ?: 0
        MainViewModel(IosPlatform())
    }
    LaunchedEffect(Unit) { vm.startLocationUpdates() }
    Box(Modifier.fillMaxSize()) {
        App(vm)
        ToastOverlay()
    }
}

/** Android's Toast, as a dark pill near the bottom. */
@Composable
private fun ToastOverlay() {
    val t = IosToast.current ?: return
    LaunchedEffect(t.token) {
        delay(if (t.long) 3500 else 2000)
        if (IosToast.current?.token == t.token) IosToast.current = null
    }
    Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.BottomCenter) {
        Text(
            t.text,
            color = Color.White,
            fontSize = 14.sp,
            modifier = Modifier.padding(bottom = 96.dp, start = 32.dp, end = 32.dp)
                .background(Color(0xE6323232), RoundedCornerShape(20.dp))
                .padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}
