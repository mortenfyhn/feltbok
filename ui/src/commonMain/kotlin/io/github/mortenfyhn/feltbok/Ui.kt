@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.mortenfyhn.feltbok

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ============================ LIST ============================

/** Round GPS minimap on the main screen — hidden for now; flip to re-enable (code kept). */
private const val SHOW_MINIMAP = false

@Composable
fun ListScreen(vm: MainViewModel, listState: LazyListState) {
    val cs = MaterialTheme.colorScheme
    var showFeedback by remember { mutableStateOf(false) }
    if (showFeedback) FeedbackDialog { showFeedback = false }
    var showAbout by remember { mutableStateOf(false) }
    if (showAbout) AboutDialog(onDismiss = { showAbout = false }, onSettings = { showAbout = false; vm.openSettings() })
    val selecting = vm.selectionMode
    val haptic = LocalHapticFeedback.current
    // While marking notes, system Back leaves selection mode rather than exiting the app.
    BackHandler(enabled = selecting) { vm.clearSelection() }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // Selection mode's action bar overlays the status strip at its exact size (matchParentSize),
            // so entering/leaving selection never resizes the strip and the list stays put (#120).
            Box {
                StatusStrip(vm)
                if (selecting) SelectionBar(vm, onEdit = { vm.startBatchEdit() }, Modifier.matchParentSize())
            }
            // The list area holds the notes (or the empty hint) and the floating + button.
            // The footer below sits in normal flow, so it can never overlap a note row (#28).
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (vm.notes.isEmpty()) {
                    Column(Modifier.fillMaxSize()) {
                        PartyStrip(vm)
                        Box(Modifier.fillMaxSize(), Alignment.Center) {
                            Text(
                                Strings.Notes.empty,
                                color = cs.onSurfaceVariant, textAlign = TextAlign.Center,
                            )
                        }
                    }
                } else {
                    // Bottom padding so the last row scrolls clear of the floating + button. Notes
                    // are grouped into per-day sections, each under its own date header. Selection
                    // mode's chrome lives in the top strip (count + actions) and a leading circle on
                    // each row/header - nothing is inserted here, so marking never shifts the list.
                    // Notes in display order (for long-press-drag range selection, which maps a
                    // drag's Y to items via the list's layout info and sweeps this order).
                    val groups = groupNotesByDay(vm.notes, Strings.dateNames)
                    val orderedIds = groups.flatMap { g -> g.notes.map { it.id } }
                    // Inverted lookup for the drag-select range sweep, so it's O(1) instead of an O(n)
                    // indexOf on every drag move / auto-scroll frame.
                    val orderedIndex = remember(orderedIds) { orderedIds.withIndex().associate { (i, id) -> id to i } }
                    // A just-added obs lands off-screen when it starts a new day above the current
                    // scroll position - jump to its day header so it's visible. Consumed once shown.
                    LaunchedEffect(vm.scrollToNoteId) {
                        val id = vm.scrollToNoteId ?: return@LaunchedEffect
                        var idx = 1   // the party row precedes the first day header
                        for (g in groups) {
                            if (g.notes.any { it.id == id }) { listState.animateScrollToItem(idx); break }
                            idx += 1 + g.notes.size   // day header + its rows
                        }
                        vm.clearScrollTarget()
                    }
                    val ds = remember { DragSelect<Long>({ vm.selected.toSet() }, vm::setSelection) }
                    SelectDragAutoScroll(listState, orderedIds, orderedIndex, ds)
                    LazyColumn(
                        Modifier.fillMaxSize().dragToSelect(listState, orderedIds, orderedIndex, haptic, ds)
                            .scrollIndicator(listState),
                        state = listState, userScrollEnabled = !ds.active,
                        contentPadding = PaddingValues(bottom = 84.dp),
                    ) {
                        // The party rides along as the list's first row, so it scrolls away like a
                        // day header - it's a reminder for when you're logging, not permanent chrome.
                        item(key = "party") { PartyStrip(vm) }
                        // The grand total of species rides along on the newest day's header only.
                        val totalSpecies = vm.notes.map { it.latin }.distinct().size
                        groups.forEachIndexed { index, group ->
                            item(key = "day:${group.label}") {
                                val species = group.notes.map { it.latin }.distinct().size
                                val label = "${group.label} · ${Strings.Notes.speciesCount(species)}" +
                                    if (index == 0) " ${Strings.Notes.speciesTotal(totalSpecies)}" else ""
                                val ids = group.notes.map { it.id }
                                DayHeader(
                                    label, selecting,
                                    allSelected = group.notes.all { it.id in vm.selected },
                                    onToggle = { vm.toggleDay(ids) },
                                    onLongPress = { vm.startSelectDay(ids) },
                                )
                            }
                            items(group.notes, key = { it.id }) { n ->
                                NoteRow(
                                    n, vm.noteName(n), vm.langPrefs.primary == Lang.LATIN, vm.statusFor(n.latin),
                                    selecting = selecting, selected = n.id in vm.selected,
                                    // Tap toggles the mark while marking, else opens the editor. Entering
                                    // selection (long-press, optionally dragged into a range) is handled by
                                    // the list's dragToSelect so it doesn't fight a row-level long-press -
                                    // but a no-drag long-press leaves a trailing tap, which we swallow once.
                                    onClick = {
                                        if (ds.suppressTap) ds.suppressTap = false
                                        else if (selecting) vm.toggleSelect(n.id) else vm.editNote(n)
                                    },
                                )
                            }
                        }
                    }
                }
                // Hidden while marking notes — the contextual bar's delete is the action then.
                // A FAB's touch target is exactly its visual size, so a slightly-off tap for "+"
                // used to land on the note row behind it. Wrap it in a larger transparent
                // clickable ring (no ripple) so the hitbox grows without the button looking bigger.
                if (!selecting) Box(
                    Modifier.align(Alignment.BottomEnd).padding(6.dp).size(88.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { vm.startAdd() },
                    contentAlignment = Alignment.Center,
                ) {
                    FloatingActionButton(
                        onClick = { vm.startAdd() },
                        containerColor = cs.primary, contentColor = Color.White,
                        modifier = Modifier.size(64.dp),
                    ) { Text("+", fontSize = 30.sp) }
                }
            }
            // Footer bar: Arkiv / Tilbakemelding, with the version tucked in the centre.
            HorizontalDivider(color = cs.outline.copy(alpha = 0.4f))
            // The two buttons sit at the edges; the version is overlaid dead-centre on the screen
            // (a weighted slot would centre it between the buttons, which is off-centre since the
            // labels differ in width). It overflows visibly, so the full string stays readable:
            // clean in the gap on release ("v0.7"), spilling over the buttons on a long dev build.
            // Tapping the version opens the About/credits dialog — the conventional home for the
            // #139 attribution, stashed where about-info is expected without new chrome.
            Box(Modifier.fillMaxWidth().background(cs.surface).bottomInsetPadding(), Alignment.Center) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    // Always visible, even with nothing archived (#153) - so the archive is
                    // discoverable before you first need it.
                    Text(Strings.Archive.title, color = cs.primary, fontWeight = FontWeight.Medium, fontSize = 13.sp,
                        maxLines = 1, modifier = Modifier.clickable { vm.openArchive() }.padding(horizontal = 14.dp, vertical = 10.dp))
                    Spacer(Modifier.weight(1f))
                    Text(Strings.Notes.feedback, color = cs.primary, fontWeight = FontWeight.Medium, fontSize = 13.sp,
                        maxLines = 1, modifier = Modifier.clickable { showFeedback = true }.padding(horizontal = 14.dp, vertical = 10.dp))
                }
                Text(AppInfo.gitVersion, color = cs.onSurfaceVariant.copy(alpha = 0.6f),
                    fontSize = 11.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Visible,
                    modifier = Modifier.align(Alignment.BottomCenter).clickable { showAbout = true }.offset(y = 5.dp))
            }
        }
        // Round minimap on the left of the top bar, clipping the top and left edges equally.
        // Hidden for now (unclear if it's useful); flip SHOW_MINIMAP to bring it back.
        if (SHOW_MINIMAP)
            LocalityPreview(vm, Modifier.align(Alignment.TopStart).offset(x = (-6).dp, y = (-6).dp))
    }
}

/** Transient state of a long-press-drag range selection, shared between the gesture detector and the
 *  edge auto-scroll loop. [active] drives scroll-freeze + the loop (observable); the rest is plain
 *  scratch the detector writes and the loop reads. [anchor] is an index into the ordered id list.
 *  [selected] and [select] read and write the marks it sweeps: the notes' (ids) or the co-observer
 *  picker's (names). */
private class DragSelect<K>(val selected: () -> Set<K>, val select: (Set<K>) -> Unit) {
    var active by mutableStateOf(false)
    var anchor = -1
    var base: Set<K> = emptySet()
    var pointerY by mutableFloatStateOf(-1f)

    // The row index [applyDragRange] last swept to. Auto-scroll re-checks every frame even when the
    // finger hasn't crossed into a new row, so this skips the sweep+setSelection work on those frames.
    var lastCursor = -2

    // A long-press with no drag still lets the row's own tap fire on finger-up (the two gestures live
    // on different nodes, so the tap isn't cancelled). That trailing tap would toggle the just-marked
    // row straight back off - so a long-press arms this, and the row's onClick eats the next tap once.
    var suppressTap = false
}

/** Edge auto-scroll while a select-drag is live (shared by the notes list, the archive and the
 *  co-observer picker). While a select-drag is live the list must not scroll on its own: its scroll
 *  gesture would race the drag detector and swallow the first moves ("didn't register") - the caller
 *  freezes user scroll via [DragSelect.active], and this drives scrolling instead. */
@Composable
private fun <K> SelectDragAutoScroll(
    listState: LazyListState,
    orderedIds: List<K>,
    orderedIndex: Map<K, Int>,
    ds: DragSelect<K>,
) {
    val density = LocalDensity.current
    LaunchedEffect(ds.active) {
        if (!ds.active) return@LaunchedEffect
        // Fixed edge band; speed ramps 0..1 with depth into the band (grazing barely
        // moves), capped low, dead everywhere else - so it's steerable, not a lurch.
        val band = with(density) { 72.dp.toPx() }
        val maxStep = with(density) { 10.dp.toPx() }
        while (ds.active) {
            withFrameNanos {}
            // y can be negative when the finger is dragged above the list (toward the
            // top bar) - that must still scroll up, so don't skip it.
            val y = ds.pointerY
            val info = listState.layoutInfo
            val top = info.viewportStartOffset.toFloat()
            val bottom = info.viewportEndOffset.toFloat()
            val frac = when {
                y < top + band -> -((top + band - y) / band)
                y > bottom - band -> (y - (bottom - band)) / band
                else -> 0f
            }.coerceIn(-1f, 1f)
            if (frac != 0f) {
                listState.scrollBy(frac * maxStep)
                // The finger is past the list edge, so it's over no row - sweep to the
                // edge-most visible row instead, so rows scrolling into view get marked.
                applyDragRange(listState, orderedIds, orderedIndex, ds, y.coerceIn(top, bottom - 1f))
            }
        }
    }
}

/** The position (in the ordered id list) of the row under a Y coordinate (list-viewport space), or
 *  null if that item isn't a selectable row (a day header, a search hit, empty space). */
private fun <K> rowIndexAt(listState: LazyListState, orderedIndex: Map<K, Int>, y: Float): Int? =
    listState.layoutInfo.visibleItemsInfo
        .firstOrNull { y >= it.offset && y < it.offset + it.size }
        // Item keys are untyped (Any); a key of another type (e.g. a "day:…" header) just isn't found.
        ?.let {
            @Suppress("UNCHECKED_CAST")
            (orderedIndex as Map<Any, Int>)[it.key]
        }

/** Mark every row between the drag anchor and the row currently under [y], painted onto the marks
 *  that existed when the drag began (so shrinking the drag un-marks, without clobbering earlier ones).
 *  [orderedIndex] is [orderedIds] inverted (id -> position), so this stays O(1) per call instead of an
 *  O(n) scan - this runs on every drag move AND every auto-scroll frame while a drag is live. */
private fun <K> applyDragRange(
    listState: LazyListState,
    orderedIds: List<K>,
    orderedIndex: Map<K, Int>,
    ds: DragSelect<K>,
    y: Float,
) {
    if (ds.anchor < 0) return
    val cur = rowIndexAt(listState, orderedIndex, y) ?: return
    if (cur == ds.lastCursor) return  // still over the same row - nothing changed since last sweep
    ds.lastCursor = cur
    ds.select(ds.base + sweepRange(orderedIds, ds.anchor, cur))
}

/** Long-press-and-drag range selection (Material's multi-select drag), on the notes list, the archive
 *  and the co-observer picker. Long-press a row to anchor + enter marking, then drag to sweep a range;
 *  edge auto-scroll (see [SelectDragAutoScroll]) extends it past the visible rows. The row-level
 *  long-press lives here, not on the rows, so the two don't fight over the gesture. */
private fun <K> Modifier.dragToSelect(
    listState: LazyListState,
    orderedIds: List<K>,
    orderedIndex: Map<K, Int>,
    haptic: HapticFeedback,
    ds: DragSelect<K>,
): Modifier = pointerInput(orderedIds) {
    detectDragGesturesAfterLongPress(
        onDragStart = { offset ->
            ds.anchor = rowIndexAt(listState, orderedIndex, offset.y) ?: -1
            if (ds.anchor >= 0) {
                ds.base = ds.selected()
                ds.pointerY = offset.y
                ds.lastCursor = ds.anchor
                ds.suppressTap = true  // eat the trailing tap if this long-press doesn't turn into a drag
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                ds.select(ds.base + orderedIds[ds.anchor])
                ds.active = true  // freeze list scroll + start edge auto-scroll
            }
        },
        onDrag = { change, _ ->
            change.consume()
            if (ds.anchor < 0) return@detectDragGesturesAfterLongPress
            ds.suppressTap = false  // a real drag: movement already cancels the row's tap
            ds.pointerY = change.position.y
            applyDragRange(listState, orderedIds, orderedIndex, ds, change.position.y)
        },
        onDragEnd = { ds.anchor = -1; ds.active = false },
        onDragCancel = { ds.anchor = -1; ds.active = false },
    )
}

/** Lets people send feedback without knowing what GitHub is: a friendly chooser between
 *  filing a GitHub issue (via a guided issue form) and plain email. */
@Composable
private fun FeedbackDialog(onDismiss: () -> Unit) {
    val platform = rememberPlatformUi()
    // Version + device + OS we'd otherwise have to ask the reporter for; auto-filled into
    // both the issue form and the email.
    val tech = "Feltbok ${AppInfo.gitVersion}\n${platform.deviceInfo}"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Strings.Feedback.title) },
        text = { Text(Strings.Feedback.body) },
        confirmButton = {
            TextButton(onClick = {
                // Issue forms accept query params keyed by field id, so we prefill "teknisk".
                platform.openUrl("https://github.com/mortenfyhn/feltbok/issues/new?template=tilbakemelding.yml" +
                    "&teknisk=" + uriEncode(tech))
                onDismiss()
            }) { Text(Strings.Feedback.githubIssue) }
        },
        dismissButton = {
            TextButton(onClick = {
                // A mailto intent can't pre-attach files, so we just nudge for a screenshot.
                val body = "\n\n---\n${Strings.Feedback.mailHint}\n$tech"
                platform.sendMail("mailto:morten.fyhn.amundsen+feltbok@gmail.com" +
                    "?subject=" + uriEncode(Strings.Feedback.mailSubject) +
                    "&body=" + uriEncode(body))
                onDismiss()
            }) { Text(Strings.Feedback.email) }
        },
    )
}

/** The contextual action bar shown while marking notes (#120): count + close on the left, the
 *  bulk actions on the right. Drawn over the status strip at its size, so it reads as the same bar
 *  transforming (Material's selection app-bar) rather than a new row - and can't shift the list.
 *  Export stays top-right, roughly where the whole-list export button sits; Endre/Slett sit before it. */
@Composable
private fun SelectionBar(vm: MainViewModel, onEdit: () -> Unit, modifier: Modifier = Modifier) {
    // Deleting several notes at once is surprising, so confirm it (#156). A single mark deletes
    // straight away - the undo snackbar is enough of a safety net there.
    var confirmDelete by remember { mutableStateOf(false) }
    Row(
        modifier.background(MaterialTheme.colorScheme.primary)
            // The bar overlays the status strip, whose locality area is clickable - swallow taps that
            // miss an action (e.g. on the count) so they don't fall through and open the map picker.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("✕", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 20.sp,
            modifier = Modifier.clip(CircleShape).clickable { vm.clearSelection() }.padding(6.dp))
        Spacer(Modifier.width(8.dp))
        SelectedCount(vm.selected.size, Modifier.weight(1f))
        Text(Strings.Notes.edit, color = Color.White, fontWeight = FontWeight.Medium, fontSize = 14.sp,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onEdit() }
                .padding(horizontal = 10.dp, vertical = 8.dp))
        Text(Strings.Notes.deleteSelected, color = Color.White, fontWeight = FontWeight.Medium, fontSize = 14.sp,
            modifier = Modifier.clip(RoundedCornerShape(8.dp))
                .clickable { if (vm.selected.size > 1) confirmDelete = true else vm.deleteSelected() }
                .padding(horizontal = 10.dp, vertical = 8.dp))
        Spacer(Modifier.width(2.dp))
        Box(
            Modifier.clip(RoundedCornerShape(8.dp))
                .border(1.dp, Color.White.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                .clickable { vm.exportSelected() }.padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(Strings.Notes.export, color = Color.White, fontWeight = FontWeight.Medium, fontSize = 14.sp)
        }
    }
    if (confirmDelete) {
        val cs = MaterialTheme.colorScheme
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(Strings.Notes.confirmDeleteTitle(vm.selected.size)) },
            confirmButton = {
                Button(
                    onClick = { vm.deleteSelected(); confirmDelete = false },
                    colors = ButtonDefaults.buttonColors(containerColor = cs.error),
                ) { Text(Strings.Notes.deleteSelected) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(Strings.cancel) } },
        )
    }
}

/** A selection bar's "N markert". Large text on a narrow phone leaves it too little room beside the
 *  actions, and it broke mid-word ("mar/ker", #198); then it drops the word and keeps the number, or
 *  at the very largest sizes drops out (the marked rows still show), for as long as the bar is up. */
@Composable
private fun SelectedCount(n: Int, modifier: Modifier) {
    var shrink by remember { mutableIntStateOf(0) }
    Text(when (shrink) { 0 -> Strings.Notes.selected(n); 1 -> "$n"; else -> "" }, color = Color.White,
        fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false,
        onTextLayout = { if (it.hasVisualOverflow && shrink < 2) shrink++ }, modifier = modifier)
}

/** The leading select-hint circle on rows and day headers while marking (#120): a hollow ring that
 *  fills with a check once marked, matching the photos-app selection idiom the maintainer asked for. */
@Composable
private fun SelectCircle(selected: Boolean) {
    val cs = MaterialTheme.colorScheme
    Box(
        // Only ever shown while marking, so it can afford to be bold: a solid 2 dp ring (mid-grey
        // when empty, primary when filled) rather than the pale hairline outline colour.
        Modifier.size(22.dp).clip(CircleShape)
            .background(if (selected) cs.primary else Color.Transparent)
            .border(2.dp, if (selected) cs.primary else cs.onSurfaceVariant, CircleShape),
        Alignment.Center,
    ) {
        // Drawn as a vector rather than a "✓" glyph: the font glyph sits low in the circle (its ink
        // isn't vertically centred in its line box), so a small stroked path centres exactly instead.
        if (selected) Canvas(Modifier.size(11.dp)) {
            val stroke = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            val path = Path().apply {
                moveTo(size.width * 0.14f, size.height * 0.55f)
                lineTo(size.width * 0.42f, size.height * 0.80f)
                lineTo(size.width * 0.86f, size.height * 0.26f)
            }
            drawPath(path, Color.White, style = stroke)
        }
    }
}

/** Per-day section header. Plain date+count text normally; while marking it grows a leading circle
 *  (like the rows) that selects/deselects the whole day at once - Material's "parent checkbox". A
 *  long-press starts marking the whole day even from the plain state, mirroring a row's long-press. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DayHeader(label: String, selecting: Boolean, allSelected: Boolean, onToggle: () -> Unit, onLongPress: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth()
            // Tap toggles the day only once marking; the plain header still long-presses into it.
            // Vertical padding is a touch roomier than a bare label since the whole header is now a
            // press target.
            .combinedClickable(onClick = { if (selecting) onToggle() }, onLongClick = onLongPress)
            .padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selecting) {
            SelectCircle(allSelected)
            Spacer(Modifier.width(12.dp))
        }
        Text(label, color = cs.onSurfaceVariant, fontSize = 13.sp)
    }
}

@Composable
private fun StatusStrip(vm: MainViewModel) {
    val cs = MaterialTheme.colorScheme
    val cur = vm.currentLocality()
    Row(
        // start padding leaves room for the round minimap overlaid on the left (when shown).
        Modifier.fillMaxWidth().background(cs.primary)
            .padding(start = if (SHOW_MINIMAP) 104.dp else 14.dp, end = 14.dp, top = 11.dp, bottom = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Distance to the locality edge and GPS accuracy share one line so neither crowds the
        // export button. The accuracy tells you whether to trust the fix / wait for a better one.
        val fix = vm.fix
        val subtitle = listOfNotNull(
            if (cur != null && fix != null) distanceLabel(cur, fix.lat, fix.lon) else null,
            when {
                fix == null -> Strings.Notes.searchingGps
                fix.accuracyM.isNaN() -> Strings.Notes.gps
                else -> Strings.Notes.gpsAccuracy(fix.accuracyM.toInt())
            },
        ).joinToString(" ")
        // Tap to open the map and pick a different current locality.
        Column(Modifier.weight(1f).clickable { vm.openCurrentLocalityPicker() }) {
            // Name ellipsizes; the chevron is pinned beside it so it can't wrap to its own line.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (cur != null) cur.lokalitet else Strings.findingPosition,
                    color = Color.White, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (cur != null) Text(" ›", color = Color.White, fontWeight = FontWeight.SemiBold)
            }
            Text(subtitle, color = Color.White.copy(alpha = 0.82f), fontSize = 12.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        // The one export entry point (#140), top-right. Hidden with nothing to export.
        // Outlined, not a solid white block: export is a once-per-session action, so it
        // shouldn't shout for the thumb that's reaching for the + button (#62).
        if (vm.notes.isNotEmpty()) {
            Spacer(Modifier.width(10.dp))
            Box(
                Modifier.clip(RoundedCornerShape(8.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                    .clickable { vm.openExport() }.padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(Strings.Notes.export, color = Color.White, fontWeight = FontWeight.Medium, fontSize = 14.sp)
            }
        }
    }
}

/** The current party ("følget mitt") at the top of the notes list (#176): who your next observation will
 *  credit, and a tap to change it. Always shown - "Ingen medobservatører valgt" is the reminder that makes
 *  you notice a party you forgot to set. It rides in the list as its first row, so it scrolls away
 *  rather than eating a permanent line of chrome. The count leads ("2 medobs.: Kari, Ola") so it
 *  survives the ellipsis on a big party. First names only (full ones are one tap away in the picker);
 *  the same muted pink as the list rows' co-observer badge, so the two read as the same thing. */
@Composable
private fun PartyStrip(vm: MainViewModel) {
    val cs = MaterialTheme.colorScheme
    val party = vm.party
    Row(
        Modifier.fillMaxWidth().background(cs.secondaryContainer)
            .clickable { vm.editParty() }.padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Count and icon as on the list rows' badge, so the strip teaches what the badge means (#191).
        if (party.isNotEmpty()) {
            Text("${party.size}", color = cs.onSecondaryContainer, fontSize = 13.sp)
            Spacer(Modifier.width(3.dp))
            Icon(GroupIcon, contentDescription = null, tint = cs.onSecondaryContainer, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(
            if (party.isEmpty()) Strings.CoObs.none
            else party.joinToString(", ") { shortCoObsName(vm.coObsName(it)) },
            color = cs.onSecondaryContainer, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            // Centred when nobody is set: with no names to read left-to-right the line is just a
            // status, and centring keeps it from looking like a truncated list.
            textAlign = if (party.isEmpty()) TextAlign.Center else TextAlign.Start,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Credits / attribution (#139). Artsdatabanken's data is CC BY 4.0 (attribution required). OSM is
 *  credited on the map too but gathered here for one tidy home. */
@Composable
private fun AboutDialog(onDismiss: () -> Unit, onSettings: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Strings.About.title) },
        text = {
            Column {
                Text(Strings.About.madeBy)
                Text(Strings.About.dataHeader, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 24.dp, bottom = 4.dp))
                Text(Strings.About.names, fontSize = 13.sp, color = cs.onSurfaceVariant)
                Text(Strings.About.artsdatabanken, fontSize = 13.sp, color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp))
                Text(Strings.About.alienRisk, fontSize = 13.sp, color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp))
                Text(Strings.About.artsobs, fontSize = 13.sp, color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp))
                Text(Strings.About.osm, fontSize = 13.sp, color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(Strings.About.close) } },
        dismissButton = { TextButton(onClick = onSettings) { Text(Strings.About.settings) } },
    )
}

private val REDLIST_CODES = setOf("RE", "CR", "EN", "VU", "NT", "DD")

/** Conservation/alien-risk badge (e.g. VU, SE) - bold caps next to a species name. Colour flags the
 *  concern at a glance: Rødlista 2021 red (vulnerable), Fremmedartslista 2023 SE/HI/PH black (invasive,
 *  high risk), LO/NK grey. Purely a label - no tap target, so it never swallows a tap meant for the
 *  row it sits in (e.g. opening/marking a note). */
/** Badge colour for a status code: Rødlista 2021 red, Fremmedartslista 2023 SE/HI/PH black, rest grey. */
private fun statusColor(code: String, cs: ColorScheme): Color = when {
    code in REDLIST_CODES -> cs.error
    code == "SE" || code == "HI" || code == "PH" -> Color.Black
    else -> cs.onSurfaceVariant   // LO/NK (and any unknown)
}

@Composable
private fun StatusBadge(code: String) {
    if (code.isBlank()) return
    Text(code, color = statusColor(code, MaterialTheme.colorScheme), fontWeight = FontWeight.Bold,
        fontSize = 11.sp, letterSpacing = 0.5.sp, modifier = Modifier.padding(start = 6.dp))
}

// An "i" badge marking a note that carries a comment, like Artsobservasjoner: blue for a public
// comment (visible to all), muted grey when only a private comment is set.
@Composable
private fun CommentBadge(n: Note) {
    val hasPublic = n.publicComment.isNotBlank()
    if (!hasPublic && n.privateComment.isBlank()) return
    val color = if (hasPublic) BadgeBlue else BadgeGrey
    Box(
        Modifier.padding(start = 6.dp).size(15.dp).background(color, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        // Drop the line-height font padding so the lone "i" sits in the visual centre of the circle.
        Text("i", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 10.sp, lineHeight = 10.sp,
            style = LocalTextStyle.current.copy(platformStyle = noFontPadding))
    }
}

// A people icon + count pill on rows with co-observers (#128): tells at a glance that an obs wasn't
// just you, and how many joined. No pill = solo. The icon says what the number counts (#191); a bare
// "+2" was unclear. Muted Material container colour, no bespoke tuning.
@Composable
private fun CoObserverBadge(n: Note) {
    if (n.coObservers.isEmpty()) return
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.padding(start = 6.dp).clip(RoundedCornerShape(50)).background(cs.secondaryContainer)
            .padding(horizontal = 5.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("${n.coObservers.size}", color = cs.onSecondaryContainer, fontWeight = FontWeight.Bold,
            fontSize = 10.sp, lineHeight = 10.sp,
            style = LocalTextStyle.current.copy(platformStyle = noFontPadding))
        Spacer(Modifier.width(2.dp))
        Icon(GroupIcon, contentDescription = null, tint = cs.onSecondaryContainer, modifier = Modifier.size(11.dp))
    }
}

// Material's "group" icon, inlined: the icon libraries are a big dependency for one glyph.
private val GroupIcon = ImageVector.Builder("Group", 24.dp, 24.dp, 24f, 24f).addPath(
    addPathNodes(
        "M16,11c1.66,0 2.99,-1.34 2.99,-3S17.66,5 16,5c-1.66,0 -3,1.34 -3,3s1.34,3 3,3zM8,11c1.66,0 " +
            "2.99,-1.34 2.99,-3S9.66,5 8,5C6.34,5 5,6.34 5,8s1.34,3 3,3zM8,13c-2.33,0 -7,1.17 -7,3.5L1,19h14v-2.5" +
            "c0,-2.33 -4.67,-3.5 -7,-3.5zM16,13c-0.29,0 -0.62,0.02 -0.97,0.05 1.16,0.84 1.97,1.97 1.97,3.45L17,19" +
            "h6v-2.5c0,-2.33 -4.67,-3.5 -7,-3.5z",
    ),
    fill = SolidColor(Color.Black),
).build()

/** Append [text] bolding only the ♂/♀ glyphs (with their trailing VS15), so a "(♀)" form keeps its
 *  parens at regular weight — bold parens would dwarf the thin symbol, which barely thickens itself. */
private fun AnnotatedString.Builder.appendBoldSymbols(text: String) {
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c == '♂' || c == '♀') {   // ♂ / ♀
            val end = if (i + 1 < text.length && text[i + 1] == '︎') i + 2 else i + 1   // keep VS15 attached
            withStyle(boldSymbolStyle) { append(text.substring(i, end)) }
            i = end
        } else {
            append(c)
            i++
        }
    }
}

@Composable
private fun NoteRow(n: Note, name: String, nameItalic: Boolean, status: String, selecting: Boolean, selected: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val hasLoc = n.locName.isNotBlank()
    // age + sex (sex symbol bolded so the thin ♂/♀ glyphs read at a glance), each shown only when
    // set. Lowest priority of the four pieces: it's the first to ellipsize/drop when space is tight.
    val preview = buildList {
        shortAge(n.age).takeIf { it.isNotBlank() }?.let { add(it to false) }
        sexSymbol(n.sex).takeIf { it.isNotBlank() }?.let { add(it to true) }
    }
    // A plain Row can't split width by need: weights divide the slack by ratio and never hand one
    // child's leftover to another. So we measure by hand, in priority order: time pinned right, then
    // the name+status, then the locality (kept flush before the time), and finally the sex/age
    // preview gets whatever room is left between name and locality (ellipsized, or dropped).
    // Wrapped in a Row so a leading select-circle (only while marking) precedes the measured content;
    // the click/tint/padding live on the Row so the whole width, circle included, is one tap target.
    Row(
        Modifier.fillMaxWidth()
            .clickable(onClick = onClick)
            .background(if (selected) cs.primaryContainer else cs.surface)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selecting) {
            SelectCircle(selected)
            Spacer(Modifier.width(12.dp))
        }
        Layout(
            content = {
                Row(verticalAlignment = Alignment.CenterVertically) {   // [0] count + species + status + comment
                    Text(
                        buildAnnotatedString {
                            val count = if (n.count == UNKNOWN_COUNT) "?" else n.count.toString()
                            withStyle(SpanStyle(color = cs.onPrimaryContainer, fontWeight = FontWeight.Bold)) { append("$count ") }
                            val shown = if (n.uncertain) "$name?" else name
                            if (nameItalic) withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(shown) } else append(shown)
                            // Status code (VU/SE/…) inline right after the name, coloured like the badge.
                            if (status.isNotBlank()) {
                                withStyle(SpanStyle(color = statusColor(status, cs), fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 0.5.sp)) {
                                    append(" $status")
                                }
                            }
                        },
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    CommentBadge(n)
                    CoObserverBadge(n)
                }
                Text(   // [1] sex/age preview
                    buildAnnotatedString {
                        preview.forEachIndexed { i, (text, bold) ->
                            if (i > 0) append(" ")
                            if (bold) appendBoldSymbols(text) else append(text)
                        }
                    },
                    color = cs.onSurfaceVariant, fontSize = 13.sp, maxLines = 1, overflow = previewOverflow,
                )
                // End-align so a truncated name's "…" hugs the right edge instead of leaving the
                // ellipsized box's trailing slack, keeping every locality flush against the timestamp.
                Text(n.locName, color = cs.onSurface, fontSize = 13.sp, textAlign = TextAlign.End,   // [2] locality
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                // A no-time obs has no clock to show; a blanked-out clock marks it (the day header carries the date).
                Text(if (n.timeUnknown) "--:--" else shortTime(n.time), color = cs.onSurfaceVariant, fontSize = 13.sp)   // [3] timestamp
            },
            modifier = Modifier.weight(1f),
        ) { measurables, constraints ->
            val w = constraints.maxWidth
            val gap = 8.dp.roundToPx()
            val time = measurables[3].measure(Constraints(maxWidth = w))
            val leftBudget = (w - time.width - gap).coerceAtLeast(0)               // room left of the timestamp
            val species = measurables[0].measure(Constraints(maxWidth = leftBudget))
            // Locality keeps its room ahead of the preview: it's measured against everything left of the
            // name, before the preview gets a look in.
            val afterName = (leftBudget - species.width - gap).coerceAtLeast(0)
            val locBudget = if (hasLoc) afterName else 0
            val loc = measurables[2].measure(Constraints(maxWidth = locBudget))
            // Only count the locality toward the row height when it's actually shown: a Text measured at
            // width 0 (no locality, or a long name leaving no room) balloons to two lines, which would
            // inflate the row and leave the content floating in a too-tall box.
            val showLoc = hasLoc && locBudget > 0
            // Preview is last in line: it gets whatever sits between the name and the locality. It's
            // short (sex + age), so show it all-or-nothing — measured at its natural width and dropped
            // whole (dot included) when it won't fit, rather than collapsing to a stray "· …".
            val previewBudget = (afterName - (if (showLoc) loc.width + gap else 0)).coerceAtLeast(0)
            val prev = measurables[1].measure(Constraints())
            val showPreview = preview.isNotEmpty() && prev.width <= previewBudget
            val h = maxOf(species.height, time.height, if (showLoc) loc.height else 0, if (showPreview) prev.height else 0)
            layout(w, h) {
                species.placeRelative(0, (h - species.height) / 2)
                time.placeRelative(w - time.width, (h - time.height) / 2)
                if (showPreview) prev.placeRelative(species.width + gap, (h - prev.height) / 2)
                if (showLoc) loc.placeRelative(w - time.width - gap - loc.width, (h - loc.height) / 2)
            }
        }
    }
    HorizontalDivider(color = cs.outline.copy(alpha = 0.4f))
}

// ============================ SEARCH ============================

@Composable
fun SearchScreen(vm: MainViewModel) {
    val cs = MaterialTheme.colorScheme
    var q by remember { mutableStateOf("") }
    // Suggestions are computed off the typing path: the field updates instantly while a
    // debounced effect refreshes `results` a moment later, so keystrokes never wait on it.
    var results by remember { mutableStateOf(vm.blankQuickList()) }
    LaunchedEffect(q) {
        if (q.isBlank()) {
            results = vm.blankQuickList()
        } else {
            delay(70)  // coalesce fast keystrokes
            results = vm.searchResults(q)
        }
    }
    // Auto-focus with the keyboard up so you can type the moment the screen opens.
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(Unit) { focus.requestFocus() }
    // Snap back to the top on every new result set. Otherwise the LazyColumn keeps its scroll
    // anchored to the previously-visible item across searches, so after typing a few queries back
    // and forth the top row was a stale, low-ranked match instead of the best one (#64).
    val listState = rememberLazyListState()
    LaunchedEffect(results) { listState.scrollToItem(0) }
    // Offer a free-text "add" row (at the bottom, so the best checklist match stays on top) for a
    // species not in the list (#144), unless the typed name already matches a shown result exactly.
    val query = q.trim()
    val canAdd = query.isNotBlank() && results.none { s ->
        fold(vm.primaryName(s)) == fold(query) || vm.secondaryName(s)?.let { fold(it) == fold(query) } == true
    }
    Column(Modifier.fillMaxSize().bottomInsetPadding()) {
        Row(
            Modifier.fillMaxWidth().background(cs.surface).padding(start = 4.dp, end = 12.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Cancel on the left, consistent with the other screens (#59).
            // No full header here (keeps the width for species), but match the "‹ Tilbake" the
            // other non-discarding screens use - leaving search throws nothing away.
            TextButton(onClick = { vm.cancelSearch() }) { Text("‹ ${Strings.back}") }
            // BasicTextField, not OutlinedTextField: the Material field forces a ~56dp min height
            // with no contentPadding knob, which is what made the bar too tall - here we set the
            // padding directly. Same approach as the count stepper above.
            BasicTextField(q, { q = it },
                Modifier.weight(1f).focusRequester(focus)
                    .border(1.dp, cs.outline, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(color = cs.onSurface),
                cursorBrush = SolidColor(cs.primary),
                // autoCorrect off: don't want the IME "correcting" species names, and the
                // gesture/compose path it drives is what crashes on swipe-typing.
                // Done, not Search (#166): the list filters as you type, so a "search" key promised an
                // action that doesn't exist - and on a name that isn't in the list it was doubly wrong.
                // Done just folds the keyboard away, showing more of the results.
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                decorationBox = { inner ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) {
                            if (q.isEmpty()) Text(Strings.Search.placeholder, color = cs.onSurfaceVariant)
                            inner()
                        }
                        // Clear button - keep focus so the keyboard stays up to retype. Plain clickable
                        // Text (not a TextButton) to avoid its 48dp min height re-inflating the bar.
                        if (q.isNotEmpty()) Text("✕", color = cs.onSurfaceVariant,
                            modifier = Modifier.clickable { q = ""; focus.requestFocus() }.padding(start = 8.dp))
                    }
                })
        }
        LazyColumn(Modifier.weight(1f), state = listState) {
            items(results, key = { it.latin }) { s ->   // norsk has homonyms (e.g. Rødhalevarsler); latin is unique
                Row(
                    Modifier.fillMaxWidth().clickable { vm.pickSpecies(s) }.background(cs.surface)
                        .padding(horizontal = 16.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(vm.primaryName(s), fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        fontStyle = if (vm.langPrefs.primary == Lang.LATIN) FontStyle.Italic else FontStyle.Normal)
                    StatusBadge(s.status)
                    // Secondary name in the user's chosen secondary language (#155); Latin is italic
                    // (scientific convention), a common name is not.
                    val secondary = vm.secondaryName(s)
                    if (secondary != null) {
                        Spacer(Modifier.width(8.dp))
                        Text(secondary, color = cs.onSurfaceVariant,
                            fontStyle = if (vm.langPrefs.secondary == Lang.LATIN) FontStyle.Italic else FontStyle.Normal,
                            fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f))
                    }
                }
                HorizontalDivider(color = cs.outline.copy(alpha = 0.4f))
            }
            if (canAdd) item(key = "add") {
                OptionItem(Strings.Search.add(query), selected = false, fullScreen = true) {
                    vm.pickArbitrarySpecies(query)
                }
            }
        }
    }
}

// ============================ CO-OBSERVERS ============================

/** The co-observer name picker (#128): mirrors the species search - one list of the names you've used
 *  before (alphabetical, a switch showing the ones on this observation), then Artsobservasjoner
 *  users matching what you type, then a free-text "Legg til …" row as the fallback for offline or a
 *  name not found. Toggles apply live to the draft, so leaving is just a back press. Long-press a name
 *  to mark names for deletion, as in the notes list (#191). */
@Composable
fun CoObserverScreen(vm: MainViewModel) {
    val cs = MaterialTheme.colorScheme
    var q by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    // No auto-focus: the picker usually opens on names you already have, and a keyboard covering
    // half the list just to be dismissed is a chore. Tap the field when you do need to type.
    val query = q.trim()
    // Your names filter from the same length as the online search starts (it needs 3 letters: it
    // returns at most 15 substring hits), so typing updates both lists together, rather than your
    // names emptying out at 1-2 letters while the search hasn't begun. Without a search, from 1.
    val minQuery = if (Country.userSearchUrl != null) 3 else 1
    val filtering = query.length >= minQuery
    val matches = vm.coObserverOptions().filter { !filtering || fold(vm.coObsName(it)).contains(fold(query)) }
    // Names marked for deletion; non-empty means delete mode. A separate mode from the switches, so
    // deleting a name can't be mistaken for taking it off this observation (#191). Entered like
    // marking notes: long-press a name, optionally dragging to sweep a range.
    val marked = remember { mutableStateListOf<String>() }
    val marking = marked.isNotEmpty()
    BackHandler(enabled = marking) { marked.clear() }
    val haptic = LocalHapticFeedback.current
    val listState = rememberLazyListState()
    val orderedIndex = remember(matches) { matches.withIndex().associate { (i, n) -> n to i } }
    val ds = remember { DragSelect<String>({ marked.toSet() }, { marked.clear(); marked.addAll(it) }) }
    SelectDragAutoScroll(listState, matches, orderedIndex, ds)
    // Online name-search hits, debounced so typing a name doesn't fire a request per letter. People
    // already in your list are left out - they show among your own names above.
    var aoHits by remember { mutableStateOf(emptyList<AoUser>()) }
    var searching by remember { mutableStateOf(false) }
    LaunchedEffect(query) {
        aoHits = emptyList()
        searching = filtering && Country.userSearchUrl != null
        if (searching) { delay(400); aoHits = vm.searchUsers(query); searching = false }
    }
    val hits = aoHits.filter { it.alias !in vm.coObserverOptions() }
    val canAdd = canAddFreeText(query, searching, vm.coObserverOptions().map(vm::coObsName), aoHits)

    // Everything's live on the draft, so there's nothing to cancel: Ferdig, Back and system Back all
    // just return. Typed text is never added on the way out - that skipped the search (an unlinked
    // name); free text goes in only via the "Legg til" row.
    fun done() = vm.closeCoObs()
    Column(Modifier.fillMaxSize().bottomInsetPadding()) {
        if (marking) ScreenHeader(
            Strings.Notes.selected(marked.size),
            onCancel = { marked.clear() },
            cancelContent = { Text("✕", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 20.sp) },
            // No confirm: the undo snackbar is the safety net, as when deleting notes.
            trailing = {
                TextButton(onClick = { vm.forgetCoObs(marked.toSet()); marked.clear() }) {
                    Text(Strings.CoObs.remove, color = Color.White)
                }
            },
        ) else ScreenHeader(
            if (vm.dCoObs.isEmpty()) Strings.CoObs.title else Strings.CoObs.titleCount(vm.dCoObs.size),
            onCancel = { done() },
            trailing = { TextButton(onClick = { done() }) { Text(Strings.CoObs.done, color = Color.White) } },
        )
        Row(
            Modifier.fillMaxWidth().background(cs.surface).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(q, { q = it },
                Modifier.weight(1f).focusRequester(focus)
                    .border(1.dp, cs.outline, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(color = cs.onSurface),
                cursorBrush = SolidColor(cs.primary),
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Done),
                // Done just folds the keyboard away to show more results, as in the species search (#166).
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                decorationBox = { inner ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) {
                            if (q.isEmpty()) Text(Strings.CoObs.placeholder, color = cs.onSurfaceVariant)
                            inner()
                        }
                        if (q.isNotEmpty()) Text("✕", color = cs.onSurfaceVariant,
                            modifier = Modifier.clickable { q = ""; focus.requestFocus() }.padding(start = 8.dp))
                    }
                })
        }
        LazyColumn(
            Modifier.weight(1f).dragToSelect(listState, matches, orderedIndex, haptic, ds),
            state = listState, userScrollEnabled = !ds.active,
        ) {
            // Brukeraliases can look odd and people may not know they're public, so show one only
            // where a namesake from the same place is on screen too.
            val aliased = needsAlias(matches.mapNotNull { vm.coObsUsers[it] } + hits)
            items(matches, key = { it }) { name ->
                CoObsRow(name, vm.coObsUsers[name], on = name in vm.dCoObs, marked = if (marking) name in marked else null,
                    showAlias = name in aliased) {
                    // A no-drag long-press leaves a trailing tap; swallow it, as in the notes list.
                    if (ds.suppressTap) ds.suppressTap = false
                    else if (marking) { if (!marked.remove(name)) marked.add(name) } else vm.toggleCoObs(name)
                }
            }
            // AO users aren't in your list yet, so no switch: tapping one adds them, and the switch shows
            // up on their row above. Hidden while marking, with the search rows: nothing there can be deleted.
            if (!marking) items(hits, key = { "ao:${it.alias}" }) { u ->
                CoObsRow(u.name, u, on = null, marked = null, showAlias = u.alias in aliased) { vm.addAoUser(u); q = "" }
            }
            if (searching && !marking) item(key = "searching") {
                Text(Strings.CoObs.searching, color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp))
            }
            if (canAdd && !marking) item(key = "add") {
                OptionItem(Strings.CoObs.add(query), selected = false, fullScreen = true) { vm.addCoObs(query); q = "" }
            }
        }
        // Switching everyone off ("I'm alone now") is a rare housekeeping action, not the screen's
        // confirm - so it sits at the bottom, out of the top-right "confirm" spot. An outlined button
        // so it reads as one (#191); "Slå av" so it reads as acting on the switches, not deleting.
        // Hidden while the keyboard is up: you're typing a name then, and it'd ride up above the keys.
        // Greyed out rather than hidden with nobody on, so the list doesn't jump when you switch off
        // the last one.
        val keyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
        if (!marking && !keyboardOpen && vm.coObserverOptions().isNotEmpty()) {
            HorizontalDivider(color = cs.outline.copy(alpha = 0.4f))
            Box(Modifier.fillMaxWidth().background(cs.surface).padding(horizontal = 16.dp, vertical = 8.dp)) {
                OutlinedButton(onClick = { vm.clearCoObs() }, enabled = vm.dCoObs.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                    Text(Strings.CoObs.allOff)
                }
            }
        }
    }
}

/** A user's second line, styled as on Artsobservasjoner: place in italics, then (brukeralias) - but
 *  the alias only with [showAlias], when it's needed to tell namesakes apart (see [needsAlias]).
 *  Empty when there's neither. */
private fun coObsDetail(user: AoUser, showAlias: Boolean): AnnotatedString = buildAnnotatedString {
    if (user.city.isNotBlank()) withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(user.city) }
    if (showAlias) append(if (user.city.isNotBlank()) "  (${user.alias})" else "(${user.alias})")
}

/** One row in the co-observer picker. A trailing switch shows whether the name is on the observation;
 *  tapping the row flips it. [on] is null for a search hit not yet in your list: no switch, a tap adds
 *  them. While marking names for deletion ([marked] non-null) the row looks like a marked note: a
 *  leading [SelectCircle], the marked tint, and no switch. */
@Composable
private fun CoObsRow(name: String, user: AoUser?, on: Boolean?, marked: Boolean?, showAlias: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val detail = user?.let { coObsDetail(it, showAlias) }?.takeIf { it.isNotEmpty() }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .semantics { if (marked != null) selected = marked else if (on != null) toggleableState = ToggleableState(on) }
            .background(if (marked == true) cs.primaryContainer else cs.surface)
            .padding(start = 16.dp, top = 4.dp, bottom = 4.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (marked != null) {
            SelectCircle(marked)
            Spacer(Modifier.width(12.dp))
        }
        // A user shows their name (the stored text is their alias), with place and alias on a line of
        // their own: namesakes differ only there, so a long name mustn't cut it.
        // The theme's 24sp line height is sized for single lines; tighter here so two-line rows stay
        // compact.
        Column(Modifier.weight(1f)) {
            Text(user?.name ?: name, color = cs.onSurface, fontWeight = FontWeight.Medium,
                lineHeight = if (detail != null) 20.sp else TextUnit.Unspecified,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail != null) Text(detail, color = cs.onSurfaceVariant, fontSize = 13.sp,
                lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(12.dp))
        // The slot stays while marking (empty), so rows keep their height.
        Box(Modifier.size(52.dp, 32.dp), Alignment.Center) { if (marked == null && on != null) CoObsSwitch(on) }
    }
    HorizontalDivider(color = cs.outline.copy(alpha = 0.4f))
}

/** Display only (null onCheckedChange): the whole row is the tap target. The theme's outline is a
 *  pale divider grey, too faint for an off switch's thumb and border, so those take the secondary
 *  text grey. */
@Composable
private fun CoObsSwitch(on: Boolean, enabled: Boolean = true) {
    val cs = MaterialTheme.colorScheme
    Switch(
        checked = on, onCheckedChange = null, enabled = enabled,
        colors = SwitchDefaults.colors(uncheckedThumbColor = cs.onSurfaceVariant, uncheckedBorderColor = cs.onSurfaceVariant),
    )
}

// ============================ DETAIL ============================

/** The green behind the status bar (or notch) at the top of the screen, so the headers reach the
 *  screen's edge like a native app's. Every screen starts with a green header but the species
 *  search, so one band in [App] serves them all, and slides between screens never change it. */
@Composable
internal fun StatusBarBand() {
    Box(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.safeDrawing).background(MaterialTheme.colorScheme.primary))
}

/** Keeps a screen's bottom edge clear of the home indicator or gesture bar. [App] only keeps the
 *  sides and the keyboard clear, so a bottom bar can paint behind them: put this after its
 *  background. Zero while the keyboard is up (App's imePadding already covers that). */
@Composable
internal fun Modifier.bottomInsetPadding() = windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))

/** The screens' shared top bar. The back/dismiss action lives on the LEFT (not the right) so a
 *  thumb reaching the top-right corner - where it lands after typing, once the keyboard hides the
 *  Lagre button - can't leave the screen by accident (#59). Default leading control is "‹ Tilbake"
 *  for screens that discard nothing; a screen that throws away a draft passes a bare "✕" (the
 *  conventional dismiss - honest, since unlike a back chevron it doesn't imply the draft is kept)
 *  and routes [onCancel] through a confirm. Title is centred (Apple/Material modal convention),
 *  kept clear of the side controls so a long one ellipsizes instead of sliding under them.
 *  [trailing] is the optional right-side action. */
@Composable
internal fun ScreenHeader(
    title: String,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    cancelContent: @Composable () -> Unit = { Text("‹ ${Strings.back}", color = Color.White) },
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Layout(
        content = {
            TextButton(onClick = onCancel, modifier = Modifier.padding(start = headerLeadingInset)) { cancelContent() }
            Row(verticalAlignment = Alignment.CenterVertically) { trailing() }
            Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        modifier = modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primary),
    ) { measurables, constraints ->
        // The title gets the measured gap between the side controls, not a fixed inset: they grow
        // with large text, and on a narrow phone "+ Ny lokalitet" ran under "Velg lokalitet"
        // (#198). Centred on the screen while it fits, else shifted toward the narrower control.
        val w = constraints.maxWidth
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val lead = measurables[0].measure(loose)
        val trail = measurables[1].measure(loose)
        val title = measurables[2].measure(loose.copy(maxWidth = (w - lead.width - trail.width).coerceAtLeast(0)))
        val h = maxOf(lead.height, trail.height, title.height, constraints.minHeight)
        layout(w, h) {
            lead.placeRelative(0, (h - lead.height) / 2)
            trail.placeRelative(w - trail.width, (h - trail.height) / 2)
            val x = ((w - title.width) / 2).coerceIn(lead.width, maxOf(lead.width, w - trail.width - title.width))
            title.placeRelative(x, (h - title.height) / 2)
        }
    }
}

// ============================ SETTINGS ============================

/** Species-name language settings (#155): one obs-entry-style row per setting - primary + secondary
 *  language each open a small picker dialog ([DropdownRow]); a compact checkbox toggles whether search
 *  also matches the secondary language. Changes save and take effect immediately (via langPrefs). */
@Composable
fun SettingsScreen(vm: MainViewModel) {
    val cs = MaterialTheme.colorScheme
    val prefs = vm.langPrefs
    val langs = listOf(Lang.NORSK, Lang.SVENSK, Lang.LATIN)
    val labels = langs.map { Strings.Settings.langLabel(it) }
    fun langFor(label: String) = langs.first { Strings.Settings.langLabel(it) == label }
    Column(Modifier.fillMaxSize().background(cs.background).bottomInsetPadding()) {
        ScreenHeader(Strings.Settings.title, onCancel = { vm.closeSettings() })
        Column(Modifier.verticalScroll(rememberScrollState())) {
            DropdownRow(Strings.Settings.primaryHeader, Strings.Settings.langLabel(prefs.primary), labels, clearable = false) {
                vm.updateLangPrefs(prefs.copy(primary = langFor(it)))
            }
            DropdownRow(Strings.Settings.secondaryHeader, Strings.Settings.langLabel(prefs.secondary), labels, clearable = false) {
                vm.updateLangPrefs(prefs.copy(secondary = langFor(it)))
            }
            // Search matches the primary name only unless this is on, then the secondary too (#155).
            // Mirrors the "Usikker artsbestemming" checkbox row so it reads as the same kind of toggle.
            Row(
                Modifier.fillMaxWidth()
                    .clickable { vm.updateLangPrefs(prefs.copy(searchSecondary = !prefs.searchSecondary)) }
                    .background(cs.surface).padding(start = 16.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // fontSize matches FieldRow's label (14sp) so this row reads the same size as the two above.
                Text(Strings.Settings.searchSecondary, color = cs.onSurfaceVariant, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Checkbox(
                    checked = prefs.searchSecondary,
                    onCheckedChange = { vm.updateLangPrefs(prefs.copy(searchSecondary = it)) },
                )
            }
            HorizontalDivider(color = cs.outline.copy(alpha = 0.4f))
        }
    }
}

// ============================ ARCHIVE ============================

/** The archive (#153): where "deleted" observations go, restorable from here. Marking behaves
 *  exactly like the notes list (long-press to start - optionally dragged into a range with edge
 *  auto-scroll - leading circles, day-header toggles, a contextual bar over the header); the only
 *  difference is that a plain tap outside marking does nothing, since an archived note can't be
 *  opened. Restore is the single action; true deletion is deliberately absent. */
@Composable
fun ArchiveScreen(vm: MainViewModel) {
    val cs = MaterialTheme.colorScheme
    val selecting = vm.selectionMode
    val haptic = LocalHapticFeedback.current
    // System Back leaves selection mode first (like the list), then the screen.
    BackHandler { if (selecting) vm.clearSelection() else vm.closeArchive() }
    Column(Modifier.fillMaxSize().bottomInsetPadding()) {
        Box {
            // As tall as the list's status strip, so the rows start where the list's did and the
            // selection bar drawn over it has room for its outlined button (a title-height bar
            // leaves the outline 2dp of clearance).
            ScreenHeader(Strings.Archive.title, onCancel = { vm.closeArchive() },
                modifier = Modifier.heightIn(min = 64.dp))
            if (selecting) ArchiveSelectionBar(vm, Modifier.matchParentSize())
        }
        if (vm.archived.isEmpty()) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text(Strings.Archive.empty, color = cs.onSurfaceVariant, textAlign = TextAlign.Center)
            }
        } else {
            val listState = rememberLazyListState()
            val groups = groupNotesByDay(vm.archived, Strings.dateNames)
            val orderedIds = groups.flatMap { g -> g.notes.map { it.id } }
            val orderedIndex = remember(orderedIds) { orderedIds.withIndex().associate { (i, id) -> id to i } }
            val ds = remember { DragSelect<Long>({ vm.selected.toSet() }, vm::setSelection) }
            SelectDragAutoScroll(listState, orderedIds, orderedIndex, ds)
            LazyColumn(
                Modifier.fillMaxSize().dragToSelect(listState, orderedIds, orderedIndex, haptic, ds)
                    .scrollIndicator(listState),
                state = listState, userScrollEnabled = !ds.active,
            ) {
                groups.forEach { group ->
                    item(key = "day:${group.label}") {
                        val ids = group.notes.map { it.id }
                        DayHeader(
                            group.label, selecting,
                            allSelected = group.notes.all { it.id in vm.selected },
                            onToggle = { vm.toggleDay(ids) },
                            onLongPress = { vm.startSelectDay(ids) },
                        )
                    }
                    items(group.notes, key = { it.id }) { n ->
                        NoteRow(
                            n, vm.noteName(n), vm.langPrefs.primary == Lang.LATIN, vm.statusFor(n.latin),
                            selecting = selecting, selected = n.id in vm.selected,
                            onClick = {
                                if (ds.suppressTap) ds.suppressTap = false
                                // A tap marks rather than opens - there's no editor for an archived
                                // note, so the first tap starts marking instead of doing nothing.
                                else if (selecting) vm.toggleSelect(n.id) else vm.startSelect(n.id)
                            },
                        )
                    }
                }
            }
        }
    }
}

/** The archive's contextual bar while marking: count + close on the left, Gjenopprett on the
 *  right. Same transform-the-header idiom (and styling) as the list's [SelectionBar]. */
@Composable
private fun ArchiveSelectionBar(vm: MainViewModel, modifier: Modifier = Modifier) {
    Row(
        modifier.background(MaterialTheme.colorScheme.primary)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("✕", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 20.sp,
            modifier = Modifier.clip(CircleShape).clickable { vm.clearSelection() }.padding(6.dp))
        Spacer(Modifier.width(8.dp))
        SelectedCount(vm.selected.size, Modifier.weight(1f))
        Box(
            Modifier.clip(RoundedCornerShape(8.dp))
                .border(1.dp, Color.White.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                .clickable { vm.restoreSelected() }.padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(Strings.Archive.restore, color = Color.White, fontWeight = FontWeight.Medium, fontSize = 14.sp)
        }
    }
}

/** Muted, single-line hint shown in a batch-edit row where the marked notes disagree: a preview of
 *  their current values (shared value or the mix). Nothing when there's nothing to preview. */
@Composable
private fun BatchHint(text: String) {
    if (text.isNotBlank()) {
        // Extra pale (vs a normal secondary label) so it clearly reads as the notes' *current* values,
        // not something you've chosen to apply.
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun DetailScreen(vm: MainViewModel) {
    val cs = MaterialTheme.colorScheme
    val platform = rememberPlatformUi()
    // Batch mode (#120): the editor is changing the whole selection at once, not one note.
    val batch = vm.batchEditing
    // Same precondition for saving and for copying (copy commits the draft first). Batch edit acts on
    // existing notes, so Lagre is always allowed (an untouched batch just changes nothing).
    val canSave = batch || (vm.dSpecies.isNotBlank() && (vm.dLoc != null || vm.nearest() != null))
    // Leaving with unsaved work discards it but offers an undo (#122); an untouched edit just goes
    // back with nothing to undo. A batch edit touches nothing until Lagre, so leaving just returns.
    val leave = {
        when {
            batch -> vm.cancelBatchEdit()
            vm.draftHasChanges() -> vm.discardDraft()
            else -> vm.cancel()
        }
    }
    // System Back is the natural cancel (NN/g), so it must go through the same path as the ✕ -
    // otherwise an accidental back-swipe drops a started observation silently. This handler sits
    // inside DetailScreen, so it shadows the app-level one only while the editor is open.
    BackHandler { leave() }
    val focus = LocalFocusManager.current
    // Copying stays on this screen with a near-identical form, so slide the new draft in from the
    // right to make clear a fresh observation was created (#110). Fires only on copyToken changes,
    // not on a normal open: a fresh DetailScreen re-seeds `seen` to the current token.
    val slide = remember { Animatable(0f) }
    var seen by remember { mutableStateOf(vm.copyToken) }
    LaunchedEffect(vm.copyToken) {
        if (vm.copyToken != seen) {
            seen = vm.copyToken
            slide.snapTo(1f)
            slide.animateTo(0f, tween(durationMillis = 250))
        }
    }
    Column(Modifier.fillMaxSize()) {
        // This screen composes a draft, so leaving discards it: a bare ✕ (the conventional dismiss)
        // rather than a back chevron. No confirm - an undo snackbar catches a misfire instead (#122).
        ScreenHeader(
            when {
                batch -> Strings.Notes.editTitle(vm.selected.size)
                vm.isEditing -> Strings.Detail.titleEdit
                else -> Strings.Detail.titleNew
            },
            onCancel = leave,
            cancelContent = { Text("✕", color = Color.White, fontSize = 22.sp) },
            // Copying commits the current draft, then hands you a prefilled copy to tweak - so you
            // can rattle off a run of similar observations (every field carries over; #130). Shown
            // when adding too, not just editing - but not in batch mode (there's no single note to copy).
            trailing = {
                if (!batch) TextButton(
                    onClick = {
                        // Only claim a save when there was something to save - same test as the
                        // discard prompt. Copying an unchanged edit still makes a copy, silently.
                        val saved = vm.draftHasChanges()
                        val editing = vm.isEditing
                        val species = vm.nameForLatin(vm.dLatin, vm.dSpecies)
                        // Drop focus off any field (comments, count) so nothing stays selected
                        // on the fresh copy - jarring when copy just cleared the comments (#136).
                        focus.clearFocus()
                        vm.copyAsNew()
                        if (saved) {
                            val msg = if (editing) Strings.Detail.savedChangesToast(species)
                            else Strings.Detail.savedNewToast(species)
                            platform.toast(msg)
                        }
                    },
                    enabled = canSave,
                ) { Text(Strings.Detail.copy, color = if (canSave) Color.White else Color.White.copy(alpha = 0.5f)) }
            },
        )
        Column(
            Modifier.weight(1f)
                .graphicsLayer { translationX = slide.value * size.width }
                .verticalScroll(rememberScrollState())
                // Tapping empty space drops focus/caret out of any text field (the number
                // keyboard has no Done key, so this is the only way out of it). Taps on the
                // rows are consumed by their own clickables and never reach here.
                .pointerInput(Unit) { detectTapGestures(onTap = { focus.clearFocus() }) },
        ) {
            // Species first - it's the first thing you choose for a new observation.
            FieldRow(Strings.Detail.species, onClick = { vm.changeSpecies() }) {
                if (batch && vm.dSpecies.isBlank()) {
                    BatchHint(vm.batchPreview { it.species })
                } else {
                    // Common name keeps its full width; the latin is what gets ellipsized when tight.
                    val disp = vm.nameForLatin(vm.dLatin, vm.dSpecies)
                    Text(disp + if (vm.dUncertain && disp.isNotBlank()) "?" else "",
                        fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        fontStyle = if (vm.langPrefs.primary == Lang.LATIN) FontStyle.Italic else FontStyle.Normal)
                    // The chosen secondary name underneath (like the search results); Latin is italic.
                    val secondary = vm.secondaryNameForLatin(vm.dLatin)
                    if (secondary != null)
                        Text("  $secondary", color = cs.onSurfaceVariant,
                            fontStyle = if (vm.langPrefs.secondary == Lang.LATIN) FontStyle.Italic else FontStyle.Normal,
                            fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false))
                }
            }
            // Uncertain species determination -> "Usikker artsbestemming" on export (shows as "Vipe?").
            // Hidden in batch edit: the batch path doesn't carry this flag, so a checkbox there would
            // silently do nothing on save.
            if (!batch) {
                Row(
                    Modifier.fillMaxWidth().clickable { vm.dUncertain = !vm.dUncertain }
                        .background(cs.surface).padding(start = 16.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(Strings.Detail.uncertain, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
                    Checkbox(checked = vm.dUncertain, onCheckedChange = { vm.dUncertain = it })
                }
                HorizontalDivider(color = cs.outline.copy(alpha = 0.4f))
            }
            // Locality
            FieldRow(Strings.Detail.locality, onClick = { vm.openLocalityPicker() }) {
                val loc = vm.dLoc
                when {
                    loc != null -> {
                        Text(loc.lokalitet, fontWeight = FontWeight.Medium, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        vm.distanceTo(loc)?.let {
                            Text("  ${formatDistance(it)}", color = cs.onSurfaceVariant, fontSize = 13.sp, maxLines = 1)
                        }
                    }
                    batch -> BatchHint(vm.batchPreview { it.locName })   // notes differ: show the mix
                    else -> Text(Strings.findingPosition, color = cs.onSurfaceVariant)
                }
            }
            AntallRow(vm)
            DropdownRow(Strings.Detail.age, vm.dAge, Country.ages, batchHint = if (batch) vm.batchPreview { it.age } else "") { vm.dAge = it }
            DropdownRow(Strings.Detail.sex, vm.dSex, Country.sexes, batchHint = if (batch) vm.batchPreview { it.sex } else "") { vm.dSex = it }
            DropdownRow(Strings.Detail.activity, vm.dAct, vm.activityOptions(), fullScreen = true, batchHint = if (batch) vm.batchPreview { it.activity } else "") { vm.dAct = it }
            // Comments aren't batch-editable (rarely the same across notes), so hide them then.
            if (!batch) {
                CommentField(Strings.Detail.commentPublic, vm.dPub) { vm.dPub = it }
                CommentField(Strings.Detail.commentPrivate, vm.dPriv) { vm.dPriv = it }
            }
            // One row to save space; tap to open the from/to editor (point or range).
            val tMs = if (vm.dTime > 0) vm.dTime else nowMs()
            var showTime by remember { mutableStateOf(false) }
            FieldRow(Strings.Detail.time, onClick = { showTime = true }) {
                if (batch && vm.dTime <= 0L) BatchHint(vm.batchPreview { displayTimeRange(it.time, it.endTime, it.timeUnknown, Strings.dateNames) })
                else Text(displayTimeRange(tMs, vm.dEndTime, vm.dTimeUnknown, Strings.dateNames))
            }
            if (showTime) TimeDialog(vm) { showTime = false }
            // Co-observers (#128) sit last, below time: tap to open the name picker. Blank value when
            // it's just you. Batch-editable like the other fields - the typical fix is realizing a
            // string of notes went in without the party and adding it to all of them at once.
            FieldRow(Strings.Detail.coObservers, onClick = { vm.openCoObs() }) {
                if (vm.dCoObs.isNotEmpty())
                    // First names, as in the list header (#176) - the picker one tap away spells
                    // out the full ones, and short names fit more of the party on the row.
                    Text(vm.dCoObs.joinToString(", ") { shortCoObsName(vm.coObsName(it)) }, fontWeight = FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                else if (batch) BatchHint(vm.batchPreview { it.coObservers.joinToString(", ") { n -> shortCoObsName(vm.coObsName(n)) } })
            }
            // "Skjul t.o.m." (#181): keeps a vulnerable bird's site hidden from other users until
            // after this date, so a crowd doesn't turn up to search for it. Only the Norwegian
            // template has the column, so the row is absent where the value couldn't be exported.
            if (Country.hideUntilCol != null) HideUntilRow(vm, batch)
        }
        // Delete and save sit side by side - red left, green right - so the destructive action
        // isn't stacked directly under the thumb's path to Lagre, where it was easy to hit by
        // mistake (#96).
        Row(
            Modifier.fillMaxWidth().background(cs.surface).bottomInsetPadding().padding(horizontal = 14.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (vm.isEditing) {
                TextButton(onClick = { vm.delete() },
                    modifier = Modifier.weight(1f)) { Text(Strings.Detail.delete, color = cs.error) }
            }
            Button(onClick = { vm.save() },
                enabled = canSave,
                modifier = Modifier.weight(1f)) { Text(Strings.Detail.save) }
        }
    }
}

/** The "Skjul t.o.m." row: the chosen date, blank when the observation isn't hidden. Tapping it
 *  opens the platform date picker, which carries the clearing action itself - it's an AlertDialog
 *  underneath, so "Ikke skjul" takes over its cancel button. That keeps the row a plain FieldRow
 *  (no ✕ competing with the caret) and needs no wrapper dialog. */
@Composable
private fun HideUntilRow(vm: MainViewModel, batch: Boolean) {
    val platform = rememberPlatformUi()

    // Seeded on the current value, else a year past the observation - hiding runs to the far side
    // of a breeding season, so next year is a closer first guess than today.
    fun pick() {
        val seed = vm.dHideUntil ?: ((if (vm.dTime > 0) vm.dTime else nowMs()) + 365L * 24 * 60 * 60 * 1000)
        // "Ikke skjul" takes the picker's cancel slot: backing out without a change is still one
        // Back press or a tap outside, so the slot is better spent on the one action the picker
        // has no room for.
        platform.pickDate(seed, onPick = { vm.dHideUntil = it }, clearLabel = Strings.Detail.hideUntilNone, onClear = { vm.dHideUntil = null })
    }
    FieldRow(Strings.Detail.hideUntil, onClick = ::pick) {
        val until = vm.dHideUntil
        when {
            until != null -> Text(displayDate(until, Strings.dateNames), fontWeight = FontWeight.Medium, maxLines = 1,
                modifier = Modifier.weight(1f, fill = false))
            batch -> BatchHint(vm.batchPreview { it.hideUntil?.let { ms -> displayDate(ms, Strings.dateNames) } ?: "" })
            else -> {}
        }
    }
}

@Composable
private fun AntallRow(vm: MainViewModel) {
    val cs = MaterialTheme.colorScheme
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()

    // Local field value; reset when the draft changes. Focusing selects all so a
    // new number replaces the old one instead of appending.
    fun countText(c: Int) = if (c == UNKNOWN_COUNT) "" else c.toString()  // unknown shows a blank field
    var tfv by remember(vm.dTime, vm.isEditing) { mutableStateOf(TextFieldValue(countText(vm.dCount))) }
    fun set(n: Int) {
        vm.setCount(n)
        val s = countText(vm.dCount)
        tfv = TextFieldValue(s, selection = TextRange(s.length))
    }
    Row(
        Modifier.fillMaxWidth().background(cs.surface).padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(Strings.Detail.count, color = cs.onSurfaceVariant, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Row(
            Modifier.border(1.dp, cs.outline, RoundedCornerShape(10.dp)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Stepping past 1 down lands on "unknown"; stepping back up returns to 1.
            Box(Modifier.size(44.dp, 40.dp).clickable { set(if (vm.dCount <= 1) UNKNOWN_COUNT else vm.dCount - 1) }, Alignment.Center) {
                Text("−", fontSize = 22.sp, color = cs.onPrimaryContainer)
            }
            BasicTextField(
                value = tfv,
                onValueChange = { v ->
                    val digits = v.text.filter { it.isDigit() }  // no upper cap; a long number may overflow the box
                    tfv = v.copy(text = digits)
                    // An emptied field means "unknown", matching the field's blank
                    // display - otherwise dCount keeps its stale value and saves as 1.
                    vm.setCount(digits.toIntOrNull() ?: UNKNOWN_COUNT)
                },
                modifier = Modifier.width(64.dp).padding(vertical = 10.dp)
                    .onFocusChanged { f ->
                        NumberPadDone.visible = f.isFocused
                        // The focusing tap places the cursor *after* this callback runs, collapsing
                        // any selection set here - so defer a frame, then select all. Runs only on
                        // focus (never during typing), so it can't swallow a keystroke.
                        if (f.isFocused) scope.launch {
                            withFrameNanos {}
                            tfv = tfv.copy(selection = TextRange(0, tfv.text.length))
                        }
                    },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                textStyle = androidx.compose.ui.text.TextStyle(
                    textAlign = TextAlign.Center, fontWeight = FontWeight.Bold,
                    fontSize = 18.sp, color = cs.onSurface),
            )
            Box(Modifier.size(44.dp, 40.dp).clickable { set(if (vm.dCount == UNKNOWN_COUNT) 1 else vm.dCount + 1) }, Alignment.Center) {
                Text("+", fontSize = 22.sp, color = cs.onPrimaryContainer)
            }
        }
    }
    HorizontalDivider(color = cs.outline.copy(alpha = 0.4f))
}

@Composable
private fun DropdownRow(
    label: String,
    value: String,
    options: List<String>,
    fullScreen: Boolean = false,
    batchHint: String = "",
    clearable: Boolean = true,
    onSelect: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    FieldRow(label, onClick = { open = true }) {
        // A set value wins; otherwise, in batch mode, preview the marked notes' current values.
        // A multi-word value wraps at its spaces; a single word that can't fit ellipsizes instead of
        // breaking mid-word ("Vitenskapeli/g" with the largest text on a narrow phone, #198).
        if (value.isNotBlank()) Text(value, fontWeight = FontWeight.Medium, overflow = TextOverflow.Ellipsis,
            maxLines = if (' ' in value) Int.MAX_VALUE else 1)
        else BatchHint(batchHint)
    }
    if (!open) return

    // A leading clear row (unless the field is always-set, e.g. the language settings). A muted dash
    // (rather than the website's truly-blank option or a worded "Ingen"): it reads as "no value" at a
    // glance without looking like dead space or like just another option in the list.
    @Composable
    fun Option(opt: String) =
        if (opt.isEmpty()) OptionItem("—", value.isBlank(), fullScreen, none = true) { onSelect(""); open = false }
        else OptionItem(opt, opt == value, fullScreen) { onSelect(opt); open = false }
    val all = (if (clearable) listOf("") else emptyList()) + options
    if (fullScreen) {
        // Aktivitet's list is long; a full-screen selector styled like the species/locality
        // pickers beats scrolling inside a cramped dialog (#123). The short lists (alder, kjønn)
        // stay as plain dialogs.
        Dialog(onDismissRequest = { open = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column {
                    ScreenHeader(label, onCancel = { open = false })
                    LazyColumn(Modifier.weight(1f)) { items(all) { Option(it) } }
                }
            }
        }
    } else {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(label) },
            // A plain scrolling Column (the lists are short), sized to its widest option: rows that
            // fill the width would stretch the dialog to its maximum, nearly edge to edge on a
            // narrow phone (#198). At least the dialog's minimum inner width (280dp less its 24dp
            // padding each side), so the ✓ still sits at the dialog's edge.
            text = {
                Column(Modifier.widthIn(min = 232.dp).width(IntrinsicSize.Max).heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState())) {
                    all.forEach { Option(it) }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { open = false }) { Text(Strings.cancel) } },
        )
    }
}

@Composable
private fun OptionItem(text: String, selected: Boolean, fullScreen: Boolean, none: Boolean = false, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        // Full screen mirrors the species rows (surface-on-background, dividers); the dialog keeps
        // its tighter, divider-free list.
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .then(if (fullScreen) Modifier.background(cs.surface) else Modifier)
            .padding(horizontal = if (fullScreen) 16.dp else 4.dp, vertical = if (fullScreen) 9.dp else 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, Modifier.weight(1f),
            color = if (selected) cs.primary else if (none) cs.onSurfaceVariant else cs.onSurface,
            fontWeight = if (selected) FontWeight.Bold else if (fullScreen) FontWeight.Medium else FontWeight.Normal)
        if (selected) Text("✓", color = cs.primary, fontWeight = FontWeight.Bold)
    }
    if (fullScreen) HorizontalDivider(color = cs.outline.copy(alpha = 0.4f))
}

/** A comment as a one-line row like every other field: the text box used to sit inline and cost two
 *  rows' height each, which pushed the fields below it off screen. Tap to write it on a full-screen
 *  page; the row then previews the start of the comment, ellipsized. */
@Composable
private fun CommentField(label: String, value: String, onChange: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    var open by remember { mutableStateOf(false) }
    FieldRow(label, onClick = { open = true }) {
        if (value.isNotBlank())
            Text(value, fontWeight = FontWeight.Medium, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
    }
    if (!open) return

    val requester = remember { FocusRequester() }
    // Held as a TextFieldValue purely to place the caret at the end of an existing comment - a plain
    // String field starts it at offset 0, so you'd have to tap to the end before adding a word.
    var tfv by remember { mutableStateOf(TextFieldValue(value, selection = TextRange(value.length))) }
    Dialog(onDismissRequest = { open = false }, properties = fullScreenDialogProperties()) {
        Surface(Modifier.fillMaxSize(), color = cs.surface) {
            // Keeps the header's actions clear of the system bars and of the keyboard - the writing
            // area shrinks instead, like any notes app. On the Column, not the Surface, so the
            // surface colour still paints behind the bars; the header's green behind the status bar.
            Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))) {
                StatusBarBand()
                // Back and Ferdig both just close: typing writes straight to the draft, so there's
                // nothing to commit here and nothing a stray back press can lose (the draft itself
                // isn't saved until Lagre on the detail screen). Same as the co-observer picker.
                ScreenHeader(label, onCancel = { open = false }, trailing = {
                    TextButton(onClick = { open = false }) { Text(Strings.Detail.commentDone, color = Color.White) }
                })
                // The effect lives in here, with the field: the dialog's content is a subcomposition,
                // so requesting focus from the caller can run before the field is attached.
                LaunchedEffect(Unit) { requester.requestFocus() }   // keyboard up right away, no second tap
                // The page itself is the writing area (no outlined box): it fills the space, so a tap
                // anywhere lands in the text and a long comment wraps at the real page edges. Enter is
                // a Done key that finishes and goes back rather than a newline (a newline wouldn't
                // survive the export anyway - exportTsv flattens it to a space).
                BasicTextField(tfv, { tfv = it; onChange(it.text) },
                    Modifier.fillMaxSize().padding(16.dp).focusRequester(requester),
                    textStyle = LocalTextStyle.current.copy(color = cs.onSurface, fontSize = 16.sp),
                    cursorBrush = SolidColor(cs.primary),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { open = false }))
            }
        }
    }
}

/** Start/end time editor. Each row's date and time-of-day are tapped separately (opening the
 *  platform date/time dialog), so you can change only the date or only the time. Slutt mirrors
 *  Start (dEndTime == null), greyed, until you set a Slutt field, making a real range (possibly
 *  across midnight). Each row's "Nå" sets just that endpoint to this moment; "Ukjent klokkeslett"
 *  keeps the date but exports no time. */
@Composable
private fun TimeDialog(vm: MainViewModel, onDismiss: () -> Unit) {
    val platform = rememberPlatformUi()
    // Picks mutate the draft live, so snapshot the open-time values to restore if the edit is cancelled.
    val orig = remember { Triple(vm.dTime, vm.dEndTime, vm.dTimeUnknown) }
    fun cancel() { vm.dTime = orig.first; vm.dEndTime = orig.second; vm.dTimeUnknown = orig.third; onDismiss() }
    val start = if (vm.dTime > 0) vm.dTime else nowMs()
    val end = vm.dEndTime ?: start

    // The platform pickers: imperative dialogs shown on tap. They keep the untouched half of the
    // instant, so a date pick leaves the time alone and vice versa.
    fun pickDate(ms: Long, onSet: (Long) -> Unit) = platform.pickDate(ms, onSet)
    fun pickTime(ms: Long, onSet: (Long) -> Unit) = platform.pickTime(ms, onSet)

    // Keep the range non-inverted: moving the start forward drags the end with it; the end
    // can't be set before the start. An end that lands in the start's minute is dropped, so it
    // mirrors the start again; that's how you go back to a single point in time.
    fun setStart(ms: Long) { vm.dTime = ms; vm.dEndTime = vm.dEndTime?.let { clampEnd(ms, it) } }
    fun setEnd(ms: Long) { vm.dEndTime = clampEnd(start, ms) }
    AlertDialog(
        onDismissRequest = ::cancel,
        title = {
            Text(Strings.Time.title)
        },
        text = {
            // As wide as one row needs, not the dialog's maximum: like the option dialogs (#198).
            Column(Modifier.width(IntrinsicSize.Max)) {
                // Each row gets its own "Nå" so a single endpoint can be bumped to the current
                // moment (e.g. just the end time while still observing). It re-enables a
                // time-of-day that had been left unspecified.
                fun now() = nowMs().also { vm.dTimeUnknown = false }
                TimeHeader(Strings.Time.from)
                TimeRow(start, vm.dTimeUnknown,
                    onDate = { pickDate(start, ::setStart) }, onTime = { pickTime(start, ::setStart) },
                    onNow = { setStart(now()) })
                // While the end mirrors the start it's greyed, but still tappable to set a real end.
                TimeHeader(Strings.Time.to)
                TimeRow(end, vm.dTimeUnknown, dim = vm.dEndTime == null,
                    onDate = { pickDate(end, ::setEnd) }, onTime = { pickTime(end, ::setEnd) },
                    onNow = { setEnd(now()) })
                // The checkbox drops its own 48dp touch padding so it lines up with the Start/Slutt
                // headers; the whole row is the touch target instead.
                Row(
                    Modifier.fillMaxWidth().clickable { vm.dTimeUnknown = !vm.dTimeUnknown }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                        Checkbox(checked = vm.dTimeUnknown, onCheckedChange = { vm.dTimeUnknown = it })
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(Strings.Time.noTime)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(Strings.Time.done) } },
        dismissButton = { TextButton(onClick = ::cancel) { Text(Strings.cancel) } },
    )
}

/** The "Start"/"Slutt" header above a [TimeRow]. */
@Composable
private fun TimeHeader(label: String) {
    Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp,
        modifier = Modifier.padding(vertical = 8.dp))
}

/** A separately-tappable date and (unless the time is left unspecified) time-of-day, plus "Nå"
 *  pinned to the right so it doesn't shift with the date's width. Date and time flow, so large
 *  text wraps whole buttons instead of breaking text inside them. [dim] greys the date and time,
 *  for an end that only mirrors the start. */
@Composable
private fun TimeRow(ms: Long, timeUnknown: Boolean, dim: Boolean = false, onDate: () -> Unit, onTime: () -> Unit, onNow: () -> Unit) {
    val color = if (dim) MaterialTheme.colorScheme.outline else Color.Unspecified
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        FlowRow(Modifier.weight(1f)) {
            TextButton(onClick = onDate) { Text(displayDate(ms, Strings.dateNames), color = color, maxLines = 1) }
            if (!timeUnknown) TextButton(onClick = onTime) { Text(exportTime(ms), color = color, maxLines = 1) }
        }
        AssistChip(onClick = onNow, label = { Text(Strings.Time.now, maxLines = 1) })
    }
}

@Composable
private fun FieldRow(
    label: String? = null,
    onClick: (() -> Unit)? = null,
    surface: Boolean = true,
    divider: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val base = Modifier.fillMaxWidth()
        .let { if (onClick != null) it.clickable(onClick = onClick) else it }
        .let { if (surface) it.background(cs.surface) else it }
        .padding(horizontal = 16.dp, vertical = 10.dp)
    BoxWithConstraints(base) {
        val labelMax = maxWidth * 0.4f
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (label != null) {
                // widthIn(min) not a fixed width: short labels still align at 84dp, but a long one (e.g.
                // "Medobservatører") takes the width it needs on one line instead of wrapping to two.
                // Up to 40% of the row, though: past that it wraps rather than squeeze the value
                // into breaking mid-word (large text on a narrow phone, "Vitenska/pelig", #198).
                Text(label, color = cs.onSurfaceVariant, fontSize = 14.sp, maxLines = 2,
                    modifier = Modifier.widthIn(min = 84.dp, max = labelMax))
                Spacer(Modifier.width(12.dp))
            }
            // Labelless rows left-align their value (the value alone is self-explanatory).
            Row(Modifier.weight(1f), horizontalArrangement = if (label != null) Arrangement.End else Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically) { content() }
            if (onClick != null) Text("  ›", color = cs.outline, fontSize = 18.sp)
        }
    }
    if (divider) HorizontalDivider(color = cs.outline.copy(alpha = 0.4f))
}

// LocalityScreen (the map-based locality picker) lives in MapPicker.kt.

// ============================ EXPORT ============================

/** One numbered step: a circled number on the left, title + body on the right. */
@Composable
private fun Step(n: Int, title: String, body: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Box(
            Modifier.size(26.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Text("$n", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            body()
        }
    }
}

/**
 * Full-page export walkthrough. Defaults to all-at-once: copy every note, paste once. Field testing
 * showed people reaching for the share sheet, so copy → open → paste is spelled out as numbered
 * steps. Shown as an overlay over the list (via showExport), so it needs its own opaque background.
 */
@Composable
fun ExportScreen(vm: MainViewModel) {
    val cs = MaterialTheme.colorScheme
    val clip = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val platform = rememberPlatformUi()
    // Scope: whatever the export covers - the marked notes when opened from selection, else all.
    val exported = vm.exportNotes()
    val isSelection = vm.exportIsSelection()
    // remember'd: with a big batch the TSV runs to hundreds of KB, so don't rebuild it on every
    // recomposition.
    val text = remember(exported) { exportTsv(exported) }
    // The preview field shows only the first rows: laying out the FULL string in a text field is
    // what made this screen slow to open on a big batch. The copy button copies everything.
    val preview = remember(text) { text.lineSequence().take(20).joinToString("\n") }
    var copied by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(cs.background).bottomInsetPadding()) {
        ScreenHeader(Strings.Export.title, onCancel = { vm.closeExport() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Step(1, Strings.Export.step1) {
                // Unwrapped, so each line reads as one row of the paste.
                Text(preview,
                    Modifier.padding(top = 6.dp).fillMaxWidth().height(120.dp)
                        .border(1.dp, cs.outline, MaterialTheme.shapes.extraSmall)
                        .verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    fontSize = 11.sp, lineHeight = 14.sp, softWrap = false)
                Button(
                    onClick = {
                        // Setting the clipboard is suspending since Compose 1.9.
                        scope.launch {
                            clip.setClipEntry(plainTextClip(text))
                        }
                        copied = true
                    },
                    modifier = Modifier.padding(top = 6.dp),
                ) { Text(if (copied) Strings.Export.copied else Strings.Export.copy) }
            }
            Step(2, Strings.Export.step2) {
                Button(
                    onClick = {
                        platform.openUrl(Country.importUrl)
                    },
                    modifier = Modifier.padding(top = 6.dp),
                ) { Text(Strings.Export.open) }
            }
            Step(3, Strings.Export.step3) {
                Text(buildAnnotatedString {
                    append(Strings.Export.pasteBody)
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(Strings.Export.pasteEmphasis) }
                }, fontSize = 13.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
            Step(4, Strings.Export.step4) {
                Text(Strings.Export.step4Body,
                    fontSize = 13.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
            Step(5, Strings.Export.step5) {
                Text(Strings.Export.step5Body,
                    fontSize = 13.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                Text(if (isSelection) Strings.Export.clearSelected(exported.size) else Strings.Export.clearAll(exported.size),
                    color = cs.error, fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                    // A single note deletes straight through (the undo snackbar catches a mistap);
                    // more than one asks first, same rule as the list's Slett (#157).
                    modifier = Modifier.clickable {
                        if (exported.size > 1) confirmClear = true else { vm.clearExported(); vm.closeExport() }
                    }.padding(top = 10.dp))
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(if (isSelection) Strings.Export.clearSelectedTitle(exported.size) else Strings.Export.clearTitle) },
            text = {
                Text(Strings.Export.clearBody)
            },
            confirmButton = {
                Button(
                    onClick = { vm.clearExported(); confirmClear = false; vm.closeExport() },
                    colors = ButtonDefaults.buttonColors(containerColor = cs.error),
                ) { Text(Strings.Export.clearConfirm) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(Strings.cancel) } },
        )
    }
}
