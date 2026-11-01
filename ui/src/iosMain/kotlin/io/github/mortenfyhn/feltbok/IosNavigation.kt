package io.github.mortenfyhn.feltbok

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.NavigationEventTransitionState
import androidx.navigationevent.compose.NavigationEventHandler
import androidx.navigationevent.compose.NavigationEventState
import androidx.navigationevent.compose.rememberNavigationEventState
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

// The iOS screen layout, plugged into App(): screens as pages over the list, sliding like an iOS
// push/pop, with the swipe-from-the-left-edge back gesture scrubbing the same pop animation.

/** How deep a screen sits, so a change can slide like an iOS push (deeper) or pop (shallower). */
private fun depth(s: Screen, from: Screen?): Int = when (s) {
    Screen.LIST -> 0
    Screen.SETTINGS, Screen.SYNC, Screen.ARCHIVE -> 1
    // Search is the first step of a new observation, but a step deeper when changing the species.
    Screen.SEARCH -> if (from == Screen.DETAIL) 3 else 1
    Screen.DETAIL -> 2
    Screen.LOCALITY, Screen.COOBS -> 3
}

private val NumberPadBarHeight = 44.dp

@Composable
private fun NumberPadDoneBar() {
    if (!NumberPadDone.visible) return
    val focus = LocalFocusManager.current
    // The Scaffold's safeDrawingPadding includes the keyboard, so the bottom of this box is the top
    // of the keyboard.
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Row(
            Modifier.fillMaxWidth().height(NumberPadBarHeight).background(Color(0xFFF1F1F2)).padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { focus.clearFocus() }) {
                Text(Strings.Time.done, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
            }
        }
    }
}

/** A screen other than the list, as a page on top of it. */
@Composable
private fun Page(vm: MainViewModel, screen: Screen) {
    when (screen) {
        Screen.LIST -> {}
        Screen.SEARCH -> SearchScreen(vm)
        Screen.DETAIL -> DetailScreen(vm)
        Screen.LOCALITY -> LocalityScreen(vm)
        Screen.COOBS -> CoObserverScreen(vm)
        Screen.SYNC -> SyncScreen(vm)
        Screen.SETTINGS -> SettingsScreen(vm)
        Screen.ARCHIVE -> ArchiveScreen(vm)
    }
}

/** Swallows all touches, for content that is shown but not navigated. */
private val BlockInput = Modifier.pointerInput(Unit) {
    awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } }
}

/** What's on top of the root list: a page (null = none) and whether Eksporter covers it. */
private data class PageKey(val screen: Screen?, val export: Boolean)

private fun depth(k: PageKey, from: PageKey): Int =
    depth(k.screen ?: Screen.LIST, from.screen) + if (k.export) 10 else 0

/**
 * The screens, laid out like a UINavigationController:
 *  - The list is the root and stays composed underneath everything, as iOS keeps its root view
 *    controller. Composing the list is the most expensive thing the app does, and going back to it
 *    is the most common move, so it costs nothing.
 *  - Other screens are pages on top, sliding in and out (push/pop). A page that isn't the current
 *    one (sliding away, or revealed by a swipe) takes no taps and its back handlers are inactive.
 *  - iOS's swipe-from-the-left-edge back scrubs the very same pop animation the in-app back button
 *    plays (a seekable transition), so the two look and perform alike. Compose's iOS host reports
 *    the edge pan as a back gesture with progress; letting go past the threshold goes back through
 *    the same BackHandler stack as Android's back button and the pop animation finishes from there.
 */
@Composable
internal fun IosNavigation(vm: MainViewModel, listState: LazyListState) {
    val scope = rememberCoroutineScope()
    val nav = rememberNavigationEventState(NavigationEventInfo.None)
    // Where a back from here lands, so a swipe can show it underneath.
    var cameFrom by remember { mutableStateOf(Screen.LIST) }
    var current by remember { mutableStateOf(vm.screen) }
    // Leaving a screen with a text field (species search, co-observer search) hides the keyboard at
    // once, together with the slide, rather than when the old screen is removed at the end of it.
    // Only on leaving those, so it can't race the next screen's own autofocus.
    val keyboard = LocalSoftwareKeyboardController.current
    if (vm.screen != current) {
        if (current == Screen.SEARCH || current == Screen.COOBS) keyboard?.hide()
        cameFrom = current
        current = vm.screen
    }

    val key = PageKey(if (vm.screen == Screen.LIST) null else vm.screen, vm.showExport)
    val backKey = if (vm.showExport) PageKey(key.screen, false)
    else PageKey(backTarget(vm, cameFrom).takeIf { it != Screen.LIST }, false)
    val pages = remember { SeekableTransitionState(key) }
    // Every navigation (button, back, a completed swipe) animates the pages to the current key.
    LaunchedEffect(key) { pages.animateTo(key) }
    // While a swipe is in progress, scrub the pop animation towards where back would land.
    val backKeyNow by rememberUpdatedState(backKey)
    LaunchedEffect(nav) {
        snapshotFlow { (nav.transitionState as? NavigationEventTransitionState.InProgress)?.latestEvent?.progress }
            .collectLatest { p -> if (p != null) pages.seekTo(p.coerceIn(0f, 1f), backKeyNow) }
    }
    val keyNow by rememberUpdatedState(key)
    BackGesture(
        nav,
        onCancelled = { scope.launch { pages.animateTo(keyNow) } },
        onCompleted = {
            // The back changes the key; LaunchedEffect(key) then finishes the pop from where the
            // finger left it. If nothing changed (nothing to go back to), slide back.
            if (!BackDispatcher.dispatch() || keyNow == pages.currentState) scope.launch { pages.animateTo(keyNow) }
        },
    )

    val onList = key == PageKey(null, false)
    Box(Modifier.fillMaxSize()) {
        // The root list, always composed.
        Box(Modifier.fillMaxSize()) {
            CompositionLocalProvider(LocalBackHandlersActive provides onList) { ListScreen(vm, listState) }
            if (!onList) Box(Modifier.fillMaxSize().then(BlockInput))
        }
        rememberTransition(pages, label = "pages").AnimatedContent(
            // With the Ferdig bar up, the page ends above it, so its bottom buttons (Arkiver/Lagre)
            // float over the keyboard and stay tappable, as Android's do.
            modifier = Modifier.padding(bottom = if (NumberPadDone.visible) NumberPadBarHeight else 0.dp),
            transitionSpec = {
                val forward = depth(targetState, initialState) > depth(initialState, targetState)
                if (forward) slideInHorizontally(tween(300)) { it } togetherWith slideOutHorizontally(tween(300)) { -it / 3 }
                else (slideInHorizontally(tween(300)) { -it / 3 } togetherWith slideOutHorizontally(tween(300)) { it })
                    .apply { targetContentZIndex = -1f }
            },
        ) { k ->
            if (k.screen != null || k.export) {
                val isCurrent = k == key
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    CompositionLocalProvider(LocalBackHandlersActive provides isCurrent) {
                        if (k.screen != null) Page(vm, k.screen)
                        if (k.export) ExportScreen(vm)
                    }
                    if (!isCurrent) Box(Modifier.fillMaxSize().then(BlockInput))
                }
            }
        }
        NumberPadDoneBar()
    }
}

/** The back-gesture registration, in its own scope so a change in whether back is possible
 *  recomposes only this, not the screens. */
@Composable
private fun BackGesture(
    nav: NavigationEventState<out NavigationEventInfo>,
    onCancelled: () -> Unit,
    onCompleted: () -> Unit,
) {
    NavigationEventHandler(
        state = nav,
        isForwardEnabled = false,
        isBackEnabled = BackDispatcher.canGoBack,
        onBackCancelled = onCancelled,
        onBackCompleted = onCompleted,
    )
}

/** The screen the current back would land on - mirrors the view model's back paths. */
private fun backTarget(vm: MainViewModel, cameFrom: Screen): Screen? = when (vm.screen) {
    Screen.LIST -> null
    Screen.DETAIL -> Screen.LIST
    Screen.SEARCH -> if (vm.dSpecies.isNotEmpty() || vm.isEditing) Screen.DETAIL else Screen.LIST
    Screen.LOCALITY -> if (vm.pickingCurrent) Screen.LIST else Screen.DETAIL
    Screen.COOBS -> cameFrom
    Screen.SYNC, Screen.SETTINGS, Screen.ARCHIVE -> Screen.LIST
}
