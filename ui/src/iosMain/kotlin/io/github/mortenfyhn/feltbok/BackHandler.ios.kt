package io.github.mortenfyhn.feltbok

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue

// iOS has no system back button, so back handlers register on a stack like Android's
// OnBackPressedDispatcher (the newest enabled one wins), and the edge-swipe gesture
// (IosNavigation.kt) dispatches to it.

internal class BackEntry(enabled: Boolean, var onBack: () -> Unit) {
    // Observable, so the back gesture turns on/off when only a flag changes (export over the list).
    var enabled by mutableStateOf(enabled)
}

/** False inside content that is only shown, not navigated: the screen under a back swipe and a
 *  screen sliding away. Their handlers stay registered but inactive, or a swipe would also fire
 *  the back of the screen it reveals (Medobs -> form -> list in one swipe). */
internal val LocalBackHandlersActive = compositionLocalOf { true }

internal object BackDispatcher {
    val entries = mutableStateListOf<BackEntry>()
    val canGoBack: Boolean get() = entries.any { it.enabled }

    fun dispatch(): Boolean {
        val e = entries.lastOrNull { it.enabled } ?: return false
        e.onBack()
        return true
    }
}

@Composable
actual fun BackHandler(enabled: Boolean, onBack: () -> Unit) {
    val active = enabled && LocalBackHandlersActive.current
    val current by rememberUpdatedState(onBack)
    val entry = remember { BackEntry(active) { current() } }
    SideEffect { entry.enabled = active }
    DisposableEffect(entry) {
        BackDispatcher.entries.add(entry)
        onDispose { BackDispatcher.entries.remove(entry) }
    }
}
