@file:OptIn(ExperimentalMaterial3Api::class)

package com.feltbok

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

// ============================ LIST ============================

/** Round GPS minimap on the main screen — hidden for now; flip to re-enable (code kept). */
private const val SHOW_MINIMAP = false

@Composable
fun ListScreen(vm: MainViewModel) {
    val cs = MaterialTheme.colorScheme
    var showFeedback by remember { mutableStateOf(false) }
    if (showFeedback) FeedbackDialog { showFeedback = false }
    val selecting = vm.selected.isNotEmpty()
    // While marking notes, system Back clears the marks rather than exiting the app.
    BackHandler(enabled = selecting) { vm.clearSelection() }
    var confirmDelete by remember { mutableStateOf(false) }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(Strings.Notes.deleteTitle(vm.selected.size)) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; vm.deleteSelected() }) {
                    Text(Strings.Notes.deleteConfirm, color = cs.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(Strings.cancel) } },
        )
    }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            StatusStrip(vm)
            // The list area holds the notes (or the empty hint) and the floating + button.
            // The footer below sits in normal flow, so it can never overlap a note row (#28).
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (vm.notes.isEmpty()) {
                    Box(Modifier.fillMaxSize(), Alignment.Center) {
                        Text(
                            Strings.Notes.empty,
                            color = cs.onSurfaceVariant, textAlign = TextAlign.Center,
                        )
                    }
                } else {
                    Column(Modifier.fillMaxSize()) {
                        // While marking, a contextual bar tops the list: how many are marked, a
                        // delete action, and a ✕ to leave selection mode.
                        if (selecting) {
                            // Actions are clickable Text (not TextButton) carrying the same 13sp +
                            // 9.dp vertical padding as the per-day headers, so the bar reads as one
                            // of them (TextButton's 48.dp min height would make it tower over them).
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(Strings.Notes.selected(vm.selected.size),
                                    color = cs.onSurface, fontSize = 13.sp,
                                    modifier = Modifier.weight(1f).padding(start = 16.dp, top = 9.dp, bottom = 9.dp))
                                Text(Strings.Notes.deleteSelected, color = cs.error, fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.clickable { confirmDelete = true }
                                        .padding(horizontal = 16.dp, vertical = 9.dp))
                                Text("✕", color = cs.onSurfaceVariant, fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.clickable { vm.clearSelection() }
                                        .padding(horizontal = 16.dp, vertical = 9.dp))
                            }
                        }
                        // Bottom padding so the last row scrolls clear of the floating + button.
                        // EXPERIMENT (#103): group by kommune instead of by day. Each header's
                        // Eksporter opens the walkthrough scoped to that kommune (the import form
                        // takes one kommune per paste), so you run the flow once per group.
                        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 84.dp)) {
                            groupNotesByKommune(vm.notes, vm.localities).forEach { group ->
                                item(key = "kommune:${group.kommune}") {
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            Strings.Notes.header(
                                                group.kommune.ifBlank { Strings.Export.unknownKommune }, group.notes.size),
                                            color = cs.onSurfaceVariant, fontSize = 13.sp,
                                            modifier = Modifier.weight(1f).padding(start = 16.dp, top = 9.dp, bottom = 9.dp),
                                        )
                                        // A filled (primary green) pill so the per-kommune export
                                        // reads as the header's action. Shorter than the default
                                        // button height so it sits neatly in the header row.
                                        Button(
                                            onClick = { vm.openExport(group.kommune) },
                                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                                            modifier = Modifier.padding(end = 8.dp).heightIn(min = 32.dp),
                                        ) { Text(Strings.Notes.export, fontSize = 13.sp) }
                                    }
                                }
                                items(group.notes.sortedByDescending { it.time }, key = { it.id }) { n ->
                                    NoteRow(
                                        n, vm.statusFor(n.latin), selected = n.id in vm.selected,
                                        // In selection mode a tap toggles the mark; otherwise it edits.
                                        onClick = { if (selecting) vm.toggleSelect(n.id) else vm.editNote(n) },
                                        onLongClick = { vm.toggleSelect(n.id) },
                                    )
                                }
                            }
                        }
                    }
                }
                // Hidden while marking notes — the contextual bar's delete is the action then.
                if (!selecting) FloatingActionButton(
                    onClick = { vm.startAdd() },
                    containerColor = cs.primary, contentColor = Color.White,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(18.dp),
                ) { Text("+", fontSize = 28.sp) }
            }
            // Footer bar: Synk / Tilbakemelding, with the version tucked at the right.
            HorizontalDivider(color = cs.outline.copy(alpha = 0.4f))
            // The two buttons sit at the edges; the version is overlaid dead-centre on the screen
            // (a weighted slot would centre it between the buttons, which is off-centre since the
            // labels differ in width). It overflows visibly, so the full string stays readable:
            // clean in the gap on release ("v0.7"), spilling over the buttons on a long dev build.
            Box(Modifier.fillMaxWidth().background(cs.surface), Alignment.Center) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    // First time it's an invitation; once you've synced some, it's a refresh.
                    Text(if (vm.localities.any { it.mine }) Strings.Sync.update else Strings.Sync.fetch,
                        color = cs.primary, fontWeight = FontWeight.Medium, fontSize = 13.sp,
                        maxLines = 1, modifier = Modifier.clickable { vm.openSync() }.padding(horizontal = 14.dp, vertical = 10.dp))
                    Spacer(Modifier.weight(1f))
                    Text(Strings.Notes.feedback, color = cs.primary, fontWeight = FontWeight.Medium, fontSize = 13.sp,
                        maxLines = 1, modifier = Modifier.clickable { showFeedback = true }.padding(horizontal = 14.dp, vertical = 10.dp))
                }
                Text(BuildConfig.GIT_VERSION, color = cs.onSurfaceVariant.copy(alpha = 0.6f),
                    fontSize = 11.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Visible,
                    modifier = Modifier.align(Alignment.BottomCenter).offset(y = 5.dp))
            }
        }
        // Round minimap on the left of the top bar, clipping the top and left edges equally.
        // Hidden for now (unclear if it's useful); flip SHOW_MINIMAP to bring it back.
        if (SHOW_MINIMAP)
            LocalityPreview(vm, Modifier.align(Alignment.TopStart).offset(x = (-6).dp, y = (-6).dp))
    }
}

/** Lets people send feedback without knowing what GitHub is: a friendly chooser between
 *  filing a GitHub issue (via a guided issue form) and plain email. */
@Composable
private fun FeedbackDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    // Version + device + OS we'd otherwise have to ask the reporter for; auto-filled into
    // both the issue form and the email.
    val tech = "Feltbok ${BuildConfig.GIT_VERSION}\n" +
        "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n" +
        "Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})"
    fun open(intent: android.content.Intent) {
        runCatching { ctx.startActivity(intent) }
        onDismiss()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Strings.Feedback.title) },
        text = { Text(Strings.Feedback.body) },
        confirmButton = {
            TextButton(onClick = {
                // Issue forms accept query params keyed by field id, so we prefill "teknisk".
                open(android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://github.com/mortenfyhn/feltbok/issues/new?template=tilbakemelding.yml" +
                        "&teknisk=" + android.net.Uri.encode(tech))))
            }) { Text(Strings.Feedback.githubIssue) }
        },
        dismissButton = {
            TextButton(onClick = {
                // A mailto intent can't pre-attach files, so we just nudge for a screenshot.
                val body = "\n\n---\n${Strings.Feedback.mailHint}\n$tech"
                open(android.content.Intent(android.content.Intent.ACTION_SENDTO,
                    android.net.Uri.parse("mailto:morten.fyhn.amundsen+feltbok@gmail.com" +
                        "?subject=" + android.net.Uri.encode(Strings.Feedback.mailSubject) +
                        "&body=" + android.net.Uri.encode(body))))
            }) { Text(Strings.Feedback.email) }
        },
    )
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
        val edge = cur?.let { l -> vm.distanceTo(l)?.let { (it - l.radius).coerceAtLeast(0.0) } }
        // Distance to the locality edge and GPS accuracy share one line so neither crowds the
        // export button. The accuracy tells you whether to trust the fix / wait for a better one.
        val fix = vm.fix
        val subtitle = listOfNotNull(
            edge?.let { if (it < 10) Strings.Notes.youAreHere else Strings.Notes.distanceAway(formatDistance(it)) },
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
                Text(if (cur != null) "${cur.lokalitet}, ${cur.kommune}" else Strings.findingPosition,
                    color = Color.White, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (cur != null) Text(" ›", color = Color.White, fontWeight = FontWeight.SemiBold)
            }
            Text(subtitle, color = Color.White.copy(alpha = 0.82f), fontSize = 12.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        // Export now lives per-kommune on each list group's header (the import form scopes one
        // kommune per paste), so the status strip just shows the current locality.
    }
}

private val REDLIST_CODES = setOf("RE", "CR", "EN", "VU", "NT", "DD")

/** Conservation/alien-risk badge (e.g. VU, SE) - bold caps next to a species name. Colour flags the
 *  concern at a glance: Rødlista 2021 red (vulnerable), Fremmedartslista 2023 SE/HI/PH black (invasive,
 *  high risk), LO/NK grey. Purely a label - no tap target, so it never swallows a tap meant for the
 *  row it sits in (e.g. opening/marking a note). */
@Composable
private fun StatusBadge(code: String) {
    if (code.isBlank()) return
    val color = when {
        code in REDLIST_CODES -> MaterialTheme.colorScheme.error
        code == "SE" || code == "HI" || code == "PH" -> Color.Black
        else -> MaterialTheme.colorScheme.onSurfaceVariant   // LO/NK (and any unknown)
    }
    Text(code, color = color, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 0.5.sp,
        modifier = Modifier.padding(start = 6.dp))
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
            style = LocalTextStyle.current.copy(platformStyle = PlatformTextStyle(includeFontPadding = false)))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NoteRow(n: Note, status: String, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val hasLoc = n.locName.isNotBlank()
    // A plain Row can't split width by need: weights divide the slack by ratio and never hand one
    // child's leftover to another. So we measure by hand — time pinned right, species first claim on
    // the rest, locality takes what's left. A short species lets a long locality fill the gap; a long
    // species pushes the locality to ellipsize. Whoever is short frees its room for the other.
    Layout(
        content = {
            Row(verticalAlignment = Alignment.CenterVertically) {   // [0] count + species + red status
                Text(
                    buildAnnotatedString {
                        val count = if (n.count == UNKNOWN_COUNT) "?" else n.count.toString()
                        withStyle(SpanStyle(color = cs.onPrimaryContainer, fontWeight = FontWeight.Bold)) { append("$count ") }
                        append(if (n.uncertain) "${n.species}?" else n.species)
                    },
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                StatusBadge(status)
                CommentBadge(n)
            }
            // End-align so a truncated name's "…" hugs the right edge instead of leaving the
            // ellipsized box's trailing slack, keeping every locality flush against the timestamp.
            Text(n.locName, color = cs.onSurface, fontSize = 13.sp, textAlign = TextAlign.End,   // [1] locality
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(rowTime(n.time), color = cs.onSurfaceVariant, fontSize = 13.sp)   // [2] timestamp
        },
        modifier = Modifier.fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .background(if (selected) cs.primaryContainer else cs.surface)
            .padding(horizontal = 16.dp, vertical = 9.dp),
    ) { measurables, constraints ->
        val w = constraints.maxWidth
        val gap = 8.dp.roundToPx()
        val time = measurables[2].measure(Constraints(maxWidth = w))
        val leftBudget = (w - time.width - gap).coerceAtLeast(0)               // room left of the timestamp
        val species = measurables[0].measure(Constraints(maxWidth = leftBudget))
        val locBudget = if (hasLoc) (leftBudget - species.width - gap).coerceAtLeast(0) else 0
        val loc = measurables[1].measure(Constraints(maxWidth = locBudget))
        val h = maxOf(species.height, loc.height, time.height)
        layout(w, h) {
            species.placeRelative(0, (h - species.height) / 2)
            time.placeRelative(w - time.width, (h - time.height) / 2)
            if (hasLoc) loc.placeRelative(w - time.width - gap - loc.width, (h - loc.height) / 2)
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
    LaunchedEffect(Unit) { focus.requestFocus() }
    // Snap back to the top on every new result set. Otherwise the LazyColumn keeps its scroll
    // anchored to the previously-visible item across searches, so after typing a few queries back
    // and forth the top row was a stale, low-ranked match instead of the best one (#64).
    val listState = rememberLazyListState()
    LaunchedEffect(results) { listState.scrollToItem(0) }
    Column(Modifier.fillMaxSize()) {
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
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { results.firstOrNull()?.let { vm.pickSpecies(it) } }),
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
                    // Dev build only (SHOW_SEARCH_TAGS): show each blank-list row's two rank
                    // contributions - your history (eg) vs here-and-now context (her), which sum to the
                    // rank - to eyeball the blend. Only meaningful with a blank query.
                    if (BuildConfig.SHOW_SEARCH_TAGS && q.isBlank()) {
                        val tag = vm.searchTag(s.norsk)
                        val pin = if (tag.pinned) "📌 " else ""
                        Text("${pin}min %.2f · her %.2f".format(tag.personal, tag.context),
                            color = cs.onSurfaceVariant, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(end = 8.dp))
                    }
                    Text(s.norsk, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    StatusBadge(s.status)
                    if (s.latin.isNotBlank()) {
                        Spacer(Modifier.width(8.dp))
                        Text(s.latin, color = cs.onSurfaceVariant, fontStyle = FontStyle.Italic,
                            fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f))
                    }
                }
                HorizontalDivider(color = cs.outline.copy(alpha = 0.4f))
            }
        }
    }
}

// ============================ DETAIL ============================

/** The screens' shared top bar. The back/dismiss action lives on the LEFT (not the right) so a
 *  thumb reaching the top-right corner - where it lands after typing, once the keyboard hides the
 *  Lagre button - can't leave the screen by accident (#59). Default leading control is "‹ Tilbake"
 *  for screens that discard nothing; a screen that throws away a draft passes a bare "✕" (the
 *  conventional dismiss - honest, since unlike a back chevron it doesn't imply the draft is kept)
 *  and routes [onCancel] through a confirm. Title is centred (Apple/Material modal convention),
 *  padded so a long one ellipsizes instead of sliding under the side controls. [trailing] is the
 *  optional right-side action. */
@Composable
internal fun ScreenHeader(
    title: String,
    onCancel: () -> Unit,
    cancelContent: @Composable () -> Unit = { Text("‹ ${Strings.back}", color = Color.White) },
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Box(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center,
    ) {
        Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp,
            textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 96.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onCancel) { cancelContent() }
            Row(verticalAlignment = Alignment.CenterVertically) { trailing() }
        }
    }
}

@Composable
fun DetailScreen(vm: MainViewModel) {
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    // Same precondition for saving and for copying (copy commits the draft first).
    val canSave = vm.dSpecies.isNotBlank() && (vm.dLoc != null || vm.nearest() != null)
    var confirmDiscard by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    // Confirm only when leaving would actually lose work (NN/g); editing a note and leaving it
    // untouched just goes back, no pointless prompt.
    val leave = { if (vm.draftHasChanges()) confirmDiscard = true else vm.cancel() }
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
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(if (vm.isEditing) Strings.Detail.discardTitleEdit else Strings.Detail.discardTitleNew) },
            confirmButton = {
                TextButton(onClick = { confirmDiscard = false; vm.cancel() }) {
                    Text(Strings.Detail.discardConfirm, color = cs.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text(Strings.Detail.discardKeep) } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(Strings.Detail.deleteTitle) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; vm.delete() }) {
                    Text(Strings.Detail.deleteConfirm, color = cs.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(Strings.cancel) } },
        )
    }
    Column(Modifier.fillMaxSize()) {
        // This screen composes a draft, so leaving discards it: a bare ✕ (the conventional dismiss)
        // rather than a back chevron, and confirm before actually throwing the draft away.
        ScreenHeader(
            if (vm.isEditing) Strings.Detail.titleEdit else Strings.Detail.titleNew,
            onCancel = leave,
            cancelContent = { Text("✕", color = Color.White, fontSize = 22.sp) },
            // Copying commits the current draft, then hands you a prefilled copy to tweak - so you
            // can rattle off a run of similar observations (every field carries over; #130). Shown
            // when adding too, not just editing.
            trailing = {
                TextButton(
                    onClick = {
                        // Only claim a save when there was something to save - same test as the
                        // discard prompt. Copying an unchanged edit still makes a copy, silently.
                        val saved = vm.draftHasChanges()
                        val editing = vm.isEditing
                        val species = vm.dSpecies
                        vm.copyAsNew()
                        if (saved) {
                            val msg = if (editing) Strings.Detail.savedChangesToast(species)
                                      else Strings.Detail.savedNewToast(species)
                            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
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
                // Common name keeps its full width; the latin is what gets ellipsized when space is tight.
                Text(vm.dSpecies + if (vm.dUncertain && vm.dSpecies.isNotBlank()) "?" else "",
                    fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (vm.dLatin.isNotBlank())
                    Text("  ${vm.dLatin}", color = cs.onSurfaceVariant, fontStyle = FontStyle.Italic,
                        fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false))
            }
            // Locality
            FieldRow(Strings.Detail.locality, onClick = { vm.openLocalityPicker() }) {
                val loc = vm.dLoc
                if (loc == null) {
                    Text(Strings.findingPosition, color = cs.onSurfaceVariant)
                } else {
                    Text(loc.lokalitet, fontWeight = FontWeight.Medium, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    vm.distanceTo(loc)?.let {
                        Text("  ${formatDistance(it)}", color = cs.onSurfaceVariant, fontSize = 13.sp, maxLines = 1)
                    }
                }
            }
            AntallRow(vm)
            DropdownRow(Strings.Detail.age, vm.dAge, Options.ages) { vm.dAge = it }
            DropdownRow(Strings.Detail.sex, vm.dSex, Options.sexes) { vm.dSex = it }
            DropdownRow(Strings.Detail.activity, vm.dAct, vm.activityOptions()) { vm.dAct = it }
            CommentField(Strings.Detail.commentPublic, vm.dPub) { vm.dPub = it }
            CommentField(Strings.Detail.commentPrivate, vm.dPriv) { vm.dPriv = it }
            // One row to save space; tap to open the from/to editor (point or range).
            val tMs = if (vm.dTime > 0) vm.dTime else System.currentTimeMillis()
            var showTime by remember { mutableStateOf(false) }
            FieldRow(Strings.Detail.time, onClick = { showTime = true }) { Text(displayTimeRange(tMs, vm.dEndTime)) }
            if (showTime) TimeDialog(vm) { showTime = false }
        }
        // Delete and save sit side by side - red left, green right - so the destructive action
        // isn't stacked directly under the thumb's path to Lagre, where it was easy to hit by
        // mistake (#96).
        Row(
            Modifier.fillMaxWidth().background(cs.surface).padding(horizontal = 14.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (vm.isEditing) {
                TextButton(onClick = { confirmDelete = true },
                    modifier = Modifier.weight(1f)) { Text(Strings.Detail.delete, color = cs.error) }
            }
            Button(onClick = { vm.save() },
                enabled = canSave,
                modifier = Modifier.weight(1f)) { Text(Strings.Detail.save) }
        }
    }
}

@Composable
private fun AntallRow(vm: MainViewModel) {
    val cs = MaterialTheme.colorScheme

    // Local field value; reset when the draft changes. Tapping selects all so a
    // new number replaces the old one instead of appending.
    fun countText(c: Int) = if (c == UNKNOWN_COUNT) "" else c.toString()  // unknown shows a blank field
    var tfv by remember(vm.dTime, vm.isEditing) { mutableStateOf(TextFieldValue(countText(vm.dCount))) }
    var justFocused by remember { mutableStateOf(false) }
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
                    val digits = v.text.filter { it.isDigit() }.take(4)
                    if (justFocused && digits == tfv.text) {
                        // First event after focusing is the tap placing the cursor;
                        // ignore it and keep everything selected so typing replaces.
                        justFocused = false
                        tfv = tfv.copy(selection = TextRange(0, tfv.text.length))
                    } else {
                        justFocused = false
                        tfv = v.copy(text = digits)
                        // An emptied field means "unknown", matching the field's blank
                        // display - otherwise dCount keeps its stale value and saves as 1.
                        vm.setCount(digits.toIntOrNull() ?: UNKNOWN_COUNT)
                    }
                },
                modifier = Modifier.width(64.dp).padding(vertical = 10.dp)
                    .onFocusChanged { f ->
                        justFocused = f.isFocused
                        if (f.isFocused) tfv = tfv.copy(selection = TextRange(0, tfv.text.length))
                    },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
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
private fun DropdownRow(label: String, value: String, options: List<String>, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    FieldRow(label, onClick = { open = true }) {
        if (value.isNotBlank()) Text(value, fontWeight = FontWeight.Medium)
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(label) },
            text = {
                LazyColumn(Modifier.heightIn(max = 400.dp)) {
                    item { OptionItem(Strings.Detail.dropdownNone, value.isBlank()) { onSelect(""); open = false } }
                    items(options) { opt -> OptionItem(opt, opt == value) { onSelect(opt); open = false } }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { open = false }) { Text(Strings.cancel) } },
        )
    }
}

@Composable
private fun OptionItem(text: String, selected: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 13.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, Modifier.weight(1f), color = if (selected) cs.primary else cs.onSurface,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
        if (selected) Text("✓", color = cs.primary, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun CommentField(label: String, value: String, onChange: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val focus = LocalFocusManager.current
    Column(Modifier.background(cs.surface).padding(horizontal = 16.dp, vertical = 5.dp)) {
        Text(label, color = cs.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(bottom = 2.dp))
        // Single-line with a "Done" action so the keyboard closes (a multi-line field leaves
        // the tester stuck - Enter just inserts a newline). Comments are short anyway.
        OutlinedTextField(value, onChange, Modifier.fillMaxWidth(), singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }))
    }
    HorizontalDivider(color = cs.outline.copy(alpha = 0.4f))
}

/** From/to time editor. All four rows show at once; Til mirrors Fra (dEndTime == null) until you
 *  set a Til field directly, which decouples it into a real range — possibly spanning midnight. */
@Composable
private fun TimeDialog(vm: MainViewModel, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    // Picks mutate the draft live, so snapshot the open-time values to restore if the edit is cancelled.
    val orig = remember { vm.dTime to vm.dEndTime }
    fun cancel() { vm.dTime = orig.first; vm.dEndTime = orig.second; onDismiss() }
    val start = if (vm.dTime > 0) vm.dTime else System.currentTimeMillis()
    val end = vm.dEndTime ?: start
    fun pickDate(ms: Long, onSet: (Long) -> Unit) {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = ms }
        android.app.DatePickerDialog(ctx, { _, y, mo, d ->
            cal.set(java.util.Calendar.YEAR, y); cal.set(java.util.Calendar.MONTH, mo)
            cal.set(java.util.Calendar.DAY_OF_MONTH, d); onSet(cal.timeInMillis)
        }, cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH),
            cal.get(java.util.Calendar.DAY_OF_MONTH)).show()
    }
    fun pickTime(ms: Long, onSet: (Long) -> Unit) {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = ms }
        android.app.TimePickerDialog(ctx, { _, h, mi ->
            cal.set(java.util.Calendar.HOUR_OF_DAY, h); cal.set(java.util.Calendar.MINUTE, mi)
            onSet(cal.timeInMillis)
        }, cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE), true).show()
    }

    // Keep the range non-inverted: moving the start forward drags the end with it; the end
    // can't be set before the start.
    fun setStart(ms: Long) { vm.dTime = ms; vm.dEndTime?.let { if (it < ms) vm.dEndTime = ms } }
    fun setEnd(ms: Long) { vm.dEndTime = maxOf(ms, start) }
    AlertDialog(
        onDismissRequest = ::cancel,
        title = { Text(Strings.Time.title) },
        text = {
            Column {
                TimeGroupHeader(Strings.Time.from)
                FieldRow(surface = false, divider = false,
                    onClick = { pickDate(start, ::setStart) }) { Text(displayDate(start)) }
                FieldRow(surface = false, divider = false,
                    onClick = { pickTime(start, ::setStart) }) { Text(exportTime(start)) }
                TimeGroupHeader(Strings.Time.to)
                FieldRow(surface = false, divider = false,
                    onClick = { pickDate(end, ::setEnd) }) { Text(displayDate(end)) }
                FieldRow(surface = false, divider = false,
                    onClick = { pickTime(end, ::setEnd) }) { Text(exportTime(end)) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(Strings.Time.done) } },
        dismissButton = { TextButton(onClick = ::cancel) { Text(Strings.cancel) } },
    )
}

@Composable
private fun TimeGroupHeader(text: String) {
    Text(text, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold,
        fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
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
    Row(base, verticalAlignment = Alignment.CenterVertically) {
        if (label != null) {
            Text(label, color = cs.onSurfaceVariant, fontSize = 14.sp, modifier = Modifier.width(84.dp))
            Spacer(Modifier.width(12.dp))
        }
        // Labelless rows left-align their value (the value alone is self-explanatory).
        Row(Modifier.weight(1f), horizontalArrangement = if (label != null) Arrangement.End else Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically) { content() }
        if (onClick != null) Text("  ›", color = cs.outline, fontSize = 18.sp)
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
 * Full-page export walkthrough for ONE kommune (the import form scopes the whole paste to a single
 * kommune). Opened from a list group's Eksporter, so you run it once per kommune. Field testing
 * showed people reaching for the share sheet, so copy → open → paste is spelled out as numbered
 * steps. Shown as an overlay over the list (via showExport), so it needs its own opaque background.
 */
@Composable
fun ExportScreen(vm: MainViewModel) {
    val cs = MaterialTheme.colorScheme
    val clip = LocalClipboardManager.current
    val ctx = LocalContext.current
    val kommune = vm.exportKommune ?: return
    val label = kommune.ifBlank { Strings.Export.unknownKommune }
    val text = exportTsv(vm.exportNotesFor(kommune))
    var copied by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(cs.background)) {
        ScreenHeader("${Strings.Export.title} ($label)", onCancel = { vm.closeExport() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Step(1, Strings.Export.step1) {
                OutlinedTextField(text, {},
                    Modifier.fillMaxWidth().height(120.dp).padding(top = 6.dp), readOnly = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp))
                Button(
                    onClick = { clip.setText(AnnotatedString(text)); copied = true },
                    modifier = Modifier.padding(top = 6.dp),
                ) { Text(if (copied) Strings.Export.copied else Strings.Export.copy) }
            }
            Step(2, Strings.Export.step2) {
                Button(
                    onClick = {
                        runCatching {
                            ctx.startActivity(android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse("https://www.artsobservasjoner.no/ImportSighting")))
                        }
                    },
                    modifier = Modifier.padding(top = 6.dp),
                ) { Text(Strings.Export.open) }
            }
            Step(3, Strings.Export.step3) {
                Text(buildAnnotatedString {
                    append(Strings.Export.pasteBefore)
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(label) }
                    append(Strings.Export.pasteAfter)
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(Strings.Export.pasteEmphasis) }
                }, fontSize = 13.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
            Step(4, Strings.Export.step4) {
                Text(Strings.Export.step4Body,
                    fontSize = 13.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
            Step(5, Strings.Export.step5) {
                Text(Strings.Export.step5Body,
                    fontSize = 13.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                Text(Strings.Export.clear(label), color = cs.error, fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                    modifier = Modifier.clickable { confirmClear = true }.padding(top = 10.dp))
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(Strings.Export.clearTitle(label)) },
            text = {
                Text(Strings.Export.clearBody)
            },
            confirmButton = {
                Button(
                    onClick = { vm.clearKommune(kommune); confirmClear = false; vm.closeExport() },
                    colors = ButtonDefaults.buttonColors(containerColor = cs.error),
                ) { Text(Strings.Export.clearConfirm) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(Strings.cancel) } },
        )
    }
}
