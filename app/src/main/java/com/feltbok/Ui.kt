@file:OptIn(ExperimentalMaterial3Api::class)

package com.feltbok

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
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
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            StatusStrip(vm)
            if (vm.notes.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), Alignment.Center) {
                    Text(
                        "Ingen notater enda.\nTrykk + for å legge til.",
                        color = cs.onSurfaceVariant, textAlign = TextAlign.Center,
                    )
                }
            } else {
                Text(
                    "I dag · ${vm.notes.size} ${if (vm.notes.size == 1) "notat" else "notater"}",
                    color = cs.onSurfaceVariant, fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
                )
                // Bottom padding so the last row scrolls clear of the floating footer (Synk / Tilbakemelding / version).
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 56.dp)) {
                    items(vm.notes, key = { it.id }) { n ->
                        NoteRow(n, vm.redStatus(n.latin), vm.distanceToNote(n)) { vm.editNote(n) }
                    }
                }
            }
        }
        // Round minimap on the left of the top bar, clipping the top and left edges equally.
        // Hidden for now (unclear if it's useful); flip SHOW_MINIMAP to bring it back.
        if (SHOW_MINIMAP)
            LocalityPreview(vm, Modifier.align(Alignment.TopStart).offset(x = (-6).dp, y = (-6).dp))
        FloatingActionButton(
            onClick = { vm.startAdd() },
            containerColor = cs.primary, contentColor = Color.White,
            modifier = Modifier.align(Alignment.BottomEnd).padding(18.dp),
        ) { Text("+", fontSize = 28.sp) }
        Text(BuildConfig.GIT_VERSION, color = cs.onSurfaceVariant.copy(alpha = 0.6f),
            fontSize = 11.sp, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 34.dp))
        Row(Modifier.align(Alignment.BottomStart), verticalAlignment = Alignment.CenterVertically) {
            Text("⟳ Synk", color = cs.primary, fontWeight = FontWeight.Medium, fontSize = 13.sp,
                modifier = Modifier.clickable { vm.openSync() }.padding(14.dp))
            Text("Tilbakemelding", color = cs.primary, fontWeight = FontWeight.Medium, fontSize = 13.sp,
                modifier = Modifier.clickable { showFeedback = true }.padding(14.dp))
        }
    }
}

/** Lets people send feedback without knowing what GitHub is: a friendly chooser between
 *  filing a GitHub issue (via a guided issue form) and plain email. */
@Composable
private fun FeedbackDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    fun open(intent: android.content.Intent) {
        runCatching { ctx.startActivity(intent) }
        onDismiss()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tilbakemelding") },
        text = { Text("Har du funnet en feil eller har et forslag? Velg hvordan du vil ta kontakt.") },
        confirmButton = {
            TextButton(onClick = {
                open(android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://github.com/mortenfyhn/feltbok/issues/new?template=tilbakemelding.yml")))
            }) { Text("GitHub Issue") }
        },
        dismissButton = {
            TextButton(onClick = {
                val body = "\n\n---\nFeltbok ${BuildConfig.GIT_VERSION}"
                open(android.content.Intent(android.content.Intent.ACTION_SENDTO,
                    android.net.Uri.parse("mailto:morten.fyhn.amundsen+feltbok@gmail.com" +
                        "?subject=" + android.net.Uri.encode("Feltbok-tilbakemelding") +
                        "&body=" + android.net.Uri.encode(body))))
            }) { Text("Send e-post") }
        },
    )
}

@Composable
private fun StatusStrip(vm: MainViewModel) {
    val cs = MaterialTheme.colorScheme
    val near = vm.nearest()
    Row(
        // start padding leaves room for the round minimap overlaid on the left (when shown).
        Modifier.fillMaxWidth().background(cs.primary)
            .padding(start = if (SHOW_MINIMAP) 104.dp else 14.dp, end = 14.dp, top = 11.dp, bottom = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val edge = near?.let { l -> vm.distanceTo(l)?.let { (it - l.radius).coerceAtLeast(0.0) } }
        Column(Modifier.weight(1f)) {
            Text(if (near != null) "${near.lokalitet}, ${near.kommune}" else "Finner posisjon…",
                color = Color.White, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (edge != null)
                Text(if (edge < 10) "du er her" else "${formatDistance(edge)} unna",
                    color = Color.White.copy(alpha = 0.82f), fontSize = 12.sp)
        }
        if (vm.notes.isNotEmpty()) {
            Spacer(Modifier.width(10.dp))
            Box(
                Modifier.clip(RoundedCornerShape(8.dp)).background(Color.White)
                    .clickable { vm.openExport() }.padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text("Eksporter", color = cs.primary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
        }
    }
}

/** Rødlista 2021 category badge (e.g. NT, VU) - red bold caps next to a species name. */
@Composable
private fun RedStatus(code: String) {
    if (code.isBlank()) return
    Text(code, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold,
        fontSize = 11.sp, letterSpacing = 0.5.sp, modifier = Modifier.padding(start = 6.dp))
}

@Composable
private fun NoteRow(n: Note, status: String, distance: Double?, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).background(cs.surface)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = cs.onPrimaryContainer, fontWeight = FontWeight.Bold)) { append("${n.count} ") }
                append(if (n.uncertain) "${n.species}?" else n.species)
            },
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
        )
        RedStatus(status)
        Spacer(Modifier.weight(1f))
        if (n.locName.isNotBlank()) {
            Text(n.locName, color = cs.onSurface, fontSize = 13.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 130.dp))
            Spacer(Modifier.width(8.dp))
        }
        distance?.let {
            Text(formatDistance(it), color = cs.onSurfaceVariant, fontSize = 12.sp)
            Spacer(Modifier.width(8.dp))
        }
        Text(shortTime(n.time), color = cs.onSurfaceVariant, fontSize = 13.sp)
    }
    HorizontalDivider(color = cs.outline.copy(alpha = 0.4f))
}

// ============================ SEARCH ============================

/** The blank-search quick list: your recent picks first, then your most-used species,
 *  then the most common ones to fill the screen. */
private fun speciesQuickList(vm: MainViewModel): List<Species> {
    val recentList = vm.recent.mapNotNull { name -> vm.species.firstOrNull { it.norsk == name } }
    val seen = recentList.mapTo(HashSet()) { it.norsk }
    val frequent = vm.species
        .filter { it.norsk !in seen && vm.useCount(it.norsk) > 0 }
        .sortedByDescending { vm.useCount(it.norsk) }
    frequent.forEach { seen.add(it.norsk) }
    val common = vm.species.filter { it.norsk !in seen }   // already in Norway-wide frequency order
    return (recentList + frequent + common).take(40)
}

@Composable
fun SearchScreen(vm: MainViewModel) {
    val cs = MaterialTheme.colorScheme
    var q by remember { mutableStateOf("") }
    // Suggestions are computed off the typing path: the field updates instantly while a
    // debounced effect refreshes `results` a moment later, so keystrokes never wait on it.
    var results by remember { mutableStateOf(speciesQuickList(vm)) }
    LaunchedEffect(q) {
        if (q.isBlank()) {
            results = speciesQuickList(vm)
        } else {
            delay(70)  // coalesce fast keystrokes
            val fq = foldQuery(q)
            results = vm.species.indices
                .mapNotNull { i -> fuzzyRank(fq, vm.foldedNorsk[i])?.let { i to it } }
                .sortedWith(compareBy({ it.second }, { -vm.useCount(vm.species[it.first].norsk) }))
                .map { vm.species[it.first] }
        }
    }
    // Auto-focus with the keyboard up so you can type the moment the screen opens.
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().background(cs.surface).padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(q, { q = it }, Modifier.weight(1f).focusRequester(focus), singleLine = true,
                placeholder = { Text("Søk art…") },
                // autoCorrect off: don't want the IME "correcting" species names, and the
                // gesture/compose path it drives is what crashes on swipe-typing.
                keyboardOptions = KeyboardOptions(autoCorrect = false, imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { results.firstOrNull()?.let { vm.pickSpecies(it) } }))
            TextButton(onClick = { vm.cancelSearch() }) { Text("Avbryt") }
        }
        LazyColumn(Modifier.weight(1f)) {
            items(results, key = { it.latin }) { s ->   // norsk has homonyms (e.g. Rødhalevarsler); latin is unique
                Row(
                    Modifier.fillMaxWidth().clickable { vm.pickSpecies(s) }.background(cs.surface)
                        .padding(horizontal = 16.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(s.norsk, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    RedStatus(s.status)
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

@Composable
fun DetailScreen(vm: MainViewModel) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize()) {
        Text(
            if (vm.isEditing) "Endre observasjon" else "Ny observasjon",
            color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().background(cs.primary).padding(vertical = 9.dp),
        )
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            // Species first - it's the first thing you choose for a new observation.
            FieldRow("Art", onClick = { vm.changeSpecies() }) {
                Text(vm.dSpecies + if (vm.dUncertain && vm.dSpecies.isNotBlank()) "?" else "",
                    fontWeight = FontWeight.Medium, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                RedStatus(vm.redStatus(vm.dLatin))
                if (vm.dLatin.isNotBlank())
                    Text("  ${vm.dLatin}", color = cs.onSurfaceVariant, fontStyle = FontStyle.Italic,
                        fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            // Uncertain species determination -> "Usikker artsbestemmelse" on export (shows as "Vipe?").
            Row(
                Modifier.fillMaxWidth().clickable { vm.dUncertain = !vm.dUncertain }
                    .background(cs.surface).padding(start = 16.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Usikker art", color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
                Checkbox(checked = vm.dUncertain, onCheckedChange = { vm.dUncertain = it })
            }
            HorizontalDivider(color = cs.outline.copy(alpha = 0.4f))
            // Locality
            FieldRow("Lokalitet", onClick = { vm.openLocalityPicker() }) {
                val loc = vm.dLoc
                if (loc == null) {
                    Text("Finner posisjon…", color = cs.onSurfaceVariant)
                } else {
                    Text(loc.lokalitet, fontWeight = FontWeight.Medium, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    vm.distanceTo(loc)?.let {
                        Text("  ${formatDistance(it)}", color = cs.onSurfaceVariant, fontSize = 13.sp, maxLines = 1)
                    }
                }
            }
            AntallRow(vm)
            DropdownRow("Alder", vm.dAge, Options.ages) { vm.dAge = it }
            DropdownRow("Aktivitet", vm.dAct, vm.activityOptions()) { vm.dAct = it }
            DropdownRow("Kjønn", vm.dSex, Options.sexes) { vm.dSex = it }
            CommentField("Åpen kommentar", vm.dPub, vm.lastPub) { vm.dPub = it }
            CommentField("Privat kommentar", vm.dPriv, vm.lastPriv) { vm.dPriv = it }
            // One row to save space; tap to open the from/to editor (point or range).
            val tMs = if (vm.dTime > 0) vm.dTime else System.currentTimeMillis()
            var showTime by remember { mutableStateOf(false) }
            FieldRow("Tid", onClick = { showTime = true }) { Text(displayTimeRange(tMs, vm.dEndTime)) }
            if (showTime) TimeDialog(vm) { showTime = false }
        }
        if (vm.isEditing) {
            HorizontalDivider(color = cs.outline.copy(alpha = 0.4f))
            TextButton(
                onClick = { vm.delete() },
                modifier = Modifier.fillMaxWidth().background(cs.surface).padding(vertical = 4.dp),
            ) { Text("Slett observasjon", color = cs.error) }
        }
        Row(
            Modifier.fillMaxWidth().background(cs.surface).padding(horizontal = 14.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(onClick = { vm.cancel() }, modifier = Modifier.weight(1f)) { Text("Avbryt") }
            Button(onClick = { vm.save() },
                enabled = vm.dSpecies.isNotBlank() && (vm.dLoc != null || vm.nearest() != null),
                modifier = Modifier.weight(1.7f)) { Text("Lagre") }
        }
    }
}


@Composable
private fun AntallRow(vm: MainViewModel) {
    val cs = MaterialTheme.colorScheme
    // Local field value; reset when the draft changes. Tapping selects all so a
    // new number replaces the old one instead of appending.
    var tfv by remember(vm.dTime, vm.isEditing) { mutableStateOf(TextFieldValue(vm.dCount.toString())) }
    var justFocused by remember { mutableStateOf(false) }
    fun set(n: Int) {
        val c = n.coerceAtLeast(1); vm.setCount(c)
        tfv = TextFieldValue(c.toString(), selection = TextRange(c.toString().length))
    }
    Row(
        Modifier.fillMaxWidth().background(cs.surface).padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Antall", color = cs.onSurfaceVariant, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Row(
            Modifier.border(1.dp, cs.outline, RoundedCornerShape(10.dp)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(44.dp, 40.dp).clickable { set(vm.dCount - 1) }, Alignment.Center) {
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
                        digits.toIntOrNull()?.let { vm.setCount(it) }
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
            Box(Modifier.size(44.dp, 40.dp).clickable { set(vm.dCount + 1) }, Alignment.Center) {
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
                    item { OptionItem("Ingen", value.isBlank()) { onSelect(""); open = false } }
                    items(options) { opt -> OptionItem(opt, opt == value) { onSelect(opt); open = false } }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { open = false }) { Text("Avbryt") } },
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
private fun CommentField(label: String, value: String, previous: String, onChange: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val focus = LocalFocusManager.current
    Column(Modifier.background(cs.surface).padding(horizontal = 16.dp, vertical = 5.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = cs.onSurfaceVariant, fontSize = 13.sp,
                modifier = Modifier.weight(1f).padding(bottom = 2.dp))
            if (value.isBlank() && previous.isNotBlank()) {
                Text("som forrige", color = cs.primary, fontSize = 13.sp,
                    modifier = Modifier.clickable { onChange(previous) }.padding(bottom = 2.dp))
            }
        }
        // Single-line with a "Done" action so the keyboard closes (a multi-line field leaves
        // the tester stuck - Enter just inserts a newline). Comments are short anyway.
        OutlinedTextField(value, onChange, Modifier.fillMaxWidth(), singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }))
    }
    HorizontalDivider(color = cs.outline.copy(alpha = 0.4f))
}

/** From/to time editor. A single point by default; ticking "Tidsrom" reveals the
 *  Til date+time, which lets an observation span a period (and across midnight). */
@Composable
private fun TimeDialog(vm: MainViewModel, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val start = if (vm.dTime > 0) vm.dTime else System.currentTimeMillis()
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
    val range = vm.dEndTime != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tid") },
        text = {
            Column {
                Row(
                    Modifier.fillMaxWidth().clickable { vm.dEndTime = if (range) null else start }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Tidsrom", color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
                    Checkbox(checked = range, onCheckedChange = { vm.dEndTime = if (it) start else null })
                }
                FieldRow(if (range) "Fra dato" else "Dato",
                    onClick = { pickDate(start, ::setStart) }) { Text(exportDate(start)) }
                FieldRow(if (range) "Fra kl." else "Klokkeslett",
                    onClick = { pickTime(start, ::setStart) }) { Text(exportTime(start)) }
                vm.dEndTime?.let { end ->
                    FieldRow("Til dato", onClick = { pickDate(end, ::setEnd) }) { Text(exportDate(end)) }
                    FieldRow("Til kl.", onClick = { pickTime(end, ::setEnd) }) { Text(exportTime(end)) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Ferdig") } },
    )
}

@Composable
private fun FieldRow(label: String, onClick: (() -> Unit)? = null, content: @Composable RowScope.() -> Unit) {
    val cs = MaterialTheme.colorScheme
    val base = Modifier.fillMaxWidth()
        .let { if (onClick != null) it.clickable(onClick = onClick) else it }
        .background(cs.surface).padding(horizontal = 16.dp, vertical = 10.dp)
    Row(base, verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = cs.onSurfaceVariant, fontSize = 14.sp, modifier = Modifier.width(84.dp))
        Spacer(Modifier.width(12.dp))
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically) { content() }
        if (onClick != null) Text("  ›", color = cs.outline, fontSize = 18.sp)
    }
    HorizontalDivider(color = cs.outline.copy(alpha = 0.4f))
}

// LocalityScreen (the map-based locality picker) lives in MapPicker.kt.

// ============================ EXPORT ============================

@Composable
fun ExportDialog(vm: MainViewModel) {
    val cs = MaterialTheme.colorScheme
    val clip = LocalClipboardManager.current
    val ctx = LocalContext.current
    val text = vm.exportText()
    var confirmClear by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { vm.closeExport() },
        title = { Text("Eksporter til Artsobservasjoner") },
        text = {
            Column {
                // Field testing showed people reaching for the share sheet; spell out the
                // copy-then-paste flow so the text lands in the right place.
                Text(
                    "Trykk Kopiér, åpne Artsobservasjoner og lim teksten inn under "
                        + "«Importer observasjoner». Ikke del via Quick Share e.l. – bare lim inn.",
                    fontSize = 13.sp,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
                // Direct link to the import page, so people don't have to hunt for it.
                Text(
                    "Åpne importsiden ›", color = cs.primary, fontWeight = FontWeight.Medium,
                    fontSize = 13.sp,
                    modifier = Modifier.clickable {
                        runCatching {
                            ctx.startActivity(android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse("https://www.artsobservasjoner.no/ImportSighting")))
                        }
                    }.padding(bottom = 10.dp),
                )
                OutlinedTextField(text, {}, Modifier.fillMaxWidth().height(170.dp), readOnly = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp))
                TextButton(onClick = { confirmClear = true }) {
                    Text("Slett alle notater", color = cs.error)
                }
            }
        },
        confirmButton = {
            Button(onClick = { clip.setText(AnnotatedString(text)); vm.closeExport() }) { Text("Kopiér") }
        },
        dismissButton = { TextButton(onClick = { vm.closeExport() }) { Text("Lukk") } },
    )
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Slett alle notater?") },
            text = {
                Text("Sletter alle ${vm.notes.size} notatene. Gjør dette først når du har "
                    + "importert og publisert på Artsobservasjoner.")
            },
            confirmButton = {
                Button(
                    onClick = { vm.clearAll(); confirmClear = false; vm.closeExport() },
                    colors = ButtonDefaults.buttonColors(containerColor = cs.error),
                ) { Text("Slett alle") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Avbryt") } },
        )
    }
}
