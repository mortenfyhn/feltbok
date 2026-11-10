package io.github.mortenfyhn.feltbok

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** The app: theme, undo snackbar and back handling around [screens], which lays the screens out
 *  (by default one at a time; iOS supplies its own with page transitions and an edge swipe). */
// Lint: the Scaffold's content padding is unused on purpose; see the insets comment inside.
@Suppress("UnusedMaterial3ScaffoldPaddingParameter")
@Composable
fun App(vm: MainViewModel, screens: @Composable (LazyListState) -> Unit = { Screens(vm, it) }) {
    AppTheme {
        val snackbar = remember { SnackbarHostState() }
        // After a delete or a draft discard, offer to undo it (#122). Keyed on undoToken so a fresh
        // snackbar shows per action; hosted here (not on the list) so it survives the switch to LIST.
        // It clears on whichever comes first: you tap Angre, the duration elapses, or you
        // navigate away (the dismiss effect below).
        LaunchedEffect(vm.undoToken) {
            // A batch edit gets the Long duration: unlike a delete or an export, the old values
            // aren't recoverable from anywhere else once the offer is gone (#177).
            val (message, duration) = when (val action = vm.undoable) {
                is Undoable.Deleted -> Strings.Notes.deleted(action.notes.size) to SnackbarDuration.Short
                is Undoable.Discarded -> Strings.Detail.discarded(action.wasEdit) to SnackbarDuration.Short
                is Undoable.Edited -> Strings.Notes.edited(action.before.size) to SnackbarDuration.Long
                is Undoable.ForgotCoObs -> Strings.CoObs.forgot(action.uses.size) to SnackbarDuration.Short
                null -> return@LaunchedEffect
            }
            val result = snackbar.showSnackbar(
                message = message,
                actionLabel = Strings.Notes.undo,
                duration = duration,
            )
            if (result == SnackbarResult.ActionPerformed) vm.undo() else vm.dismissUndo()
        }
        // The undo only makes sense on the screen it was offered on: the list, or the co-observer
        // picker for deleted names (#191). Dismiss it once you act (start/edit an observation, sync,
        // open export, leave the picker) so it doesn't linger forever.
        LaunchedEffect(vm.screen, vm.showExport) {
            val home = if (vm.undoable is Undoable.ForgotCoObs) Screen.COOBS else Screen.LIST
            if (vm.screen != home || vm.showExport) snackbar.currentSnackbarData?.dismiss()
        }
        Surface(color = MaterialTheme.colorScheme.background) {
            // System back navigates within the app instead of exiting (except on the list).
            BackHandler(enabled = vm.showExport || vm.screen != Screen.LIST) {
                when {
                    vm.showExport -> vm.closeExport()
                    vm.screen == Screen.SYNC -> vm.closeSync()
                    vm.screen == Screen.COOBS -> vm.closeCoObs()
                    vm.screen == Screen.SETTINGS -> vm.closeSettings()
                    vm.screen == Screen.SEARCH -> vm.cancelSearch()
                    // DETAIL, LOCALITY and ARCHIVE each compose their own BackHandler, which shadows this one
                    // so the gesture matches their "tilbake" button; those branches never run here.
                    else -> {}
                }
            }
            // Transparent Scaffold purely to host the undo snackbar; screens manage their own insets,
            // so the content padding is intentionally ignored.
            // Hoisted above the screen switch so the list's scroll position survives a trip into the
            // editor and back (#146) - a LazyListState remembered inside ListScreen is discarded when
            // that screen leaves composition.
            val listState = rememberLazyListState()
            Column {
                StatusBarBand()
                Scaffold(
                    // The window is edge-to-edge. Only the sides and the keyboard are kept clear here:
                    // the band above fills the status bar, and each screen pads its own bottom edge
                    // (bottomInsetPadding) so its bottom bar can paint behind the home indicator.
                    modifier = Modifier.weight(1f)
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)).imePadding(),
                    // Lifted above the list's footer bar (Material floats snackbars above persistent
                    // bottom chrome), so the undo toast can't cover the Arkiv/feedback links - which it
                    // otherwise does exactly when you've just archived and might want the archive. The
                    // footer is a single fixed 13sp text row, so a constant clears it.
                    snackbarHost = { SnackbarHost(snackbar, Modifier.padding(bottom = 40.dp)) },
                    containerColor = Color.Transparent,
                ) { _ ->
                    screens(listState)
                }
            }
        }
    }
}

/** The current screen, with Eksporter over it when open. */
@Composable
fun Screens(vm: MainViewModel, listState: LazyListState) {
    when (vm.screen) {
        Screen.LIST -> ListScreen(vm, listState)
        Screen.SEARCH -> SearchScreen(vm)
        Screen.DETAIL -> DetailScreen(vm)
        Screen.LOCALITY -> LocalityScreen(vm)
        Screen.COOBS -> CoObserverScreen(vm)
        Screen.SYNC -> SyncScreen(vm)
        Screen.SETTINGS -> SettingsScreen(vm)
        Screen.ARCHIVE -> ArchiveScreen(vm)
    }
    if (vm.showExport) ExportScreen(vm)
}
