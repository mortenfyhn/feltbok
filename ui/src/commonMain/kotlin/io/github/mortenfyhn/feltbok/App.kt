package io.github.mortenfyhn.feltbok

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
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
                null -> return@LaunchedEffect
            }
            val result = snackbar.showSnackbar(
                message = message,
                actionLabel = Strings.Notes.undo,
                duration = duration,
            )
            if (result == SnackbarResult.ActionPerformed) vm.undo() else vm.dismissUndo()
        }
        // The undo only makes sense on the list; dismiss it once you act (start/edit an observation,
        // sync, or open export) so it doesn't linger forever.
        LaunchedEffect(vm.screen, vm.showExport) {
            if (vm.screen != Screen.LIST || vm.showExport) snackbar.currentSnackbarData?.dismiss()
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
            Scaffold(
                // Keeps the content clear of the system bars, cutout and keyboard now that the
                // window is edge-to-edge. Sits on the Scaffold, not the Surface, so the Surface's
                // background colour still paints behind the bars instead of leaving them bare.
                modifier = Modifier.safeDrawingPadding(),
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
