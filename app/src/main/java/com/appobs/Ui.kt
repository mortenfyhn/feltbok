@file:OptIn(ExperimentalMaterial3Api::class)

package com.appobs

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
                LazyColumn(Modifier.weight(1f)) {
                    items(vm.notes, key = { it.id }) { n ->
                        NoteRow(n, vm.distanceToNote(n)) { vm.editNote(n) }
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
    }
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
        Text(if (near != null) "${near.lokalitet}, ${near.kommune}" else "Finner posisjon…",
            color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
            maxLines = 2, overflow = TextOverflow.Ellipsis)
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

@Composable
private fun NoteRow(n: Note, distance: Double?, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).background(cs.surface)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = cs.onPrimaryContainer, fontWeight = FontWeight.Bold)) { append("${n.count} ") }
                append(n.species)
            },
            modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (n.locName.isNotBlank()) {
            Text(n.locName, color = cs.onSurface, fontSize = 13.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 130.dp))
            Spacer(Modifier.width(8.dp))
        }
        distance?.let {
            Text(formatDistance(it), color = cs.onSurfaceVariant, fontSize = 12.sp)
            Spacer(Modifier.width(8.dp))
        }
        Text(shortTime(n.id), color = cs.onSurfaceVariant, fontSize = 13.sp)
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
            // Species
            FieldRow("Art", onClick = { vm.changeSpecies() }) {
                Text(vm.dSpecies, fontWeight = FontWeight.Medium, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (vm.dLatin.isNotBlank())
                    Text("  ${vm.dLatin}", color = cs.onSurfaceVariant, fontStyle = FontStyle.Italic,
                        fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            AntallRow(vm)
            DropdownRow("Alder", vm.dAge, Options.ages) { vm.dAge = it }
            DropdownRow("Aktivitet", vm.dAct, vm.activityOptions()) { vm.dAct = it }
            DropdownRow("Kjønn", vm.dSex, Options.sexes) { vm.dSex = it }
            CommentField("Åpen kommentar", vm.dPub, vm.lastPub) { vm.dPub = it }
            CommentField("Privat kommentar", vm.dPriv, vm.lastPriv) { vm.dPriv = it }
            FieldRow("Tidspunkt") {
                Text(displayTime(if (vm.dTime > 0) vm.dTime else System.currentTimeMillis()))
            }
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
    Column(Modifier.background(cs.surface).padding(horizontal = 16.dp, vertical = 5.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = cs.onSurfaceVariant, fontSize = 13.sp,
                modifier = Modifier.weight(1f).padding(bottom = 2.dp))
            if (value.isBlank() && previous.isNotBlank()) {
                Text("som forrige", color = cs.primary, fontSize = 13.sp,
                    modifier = Modifier.clickable { onChange(previous) }.padding(bottom = 2.dp))
            }
        }
        OutlinedTextField(value, onChange, Modifier.fillMaxWidth(), minLines = 1)
    }
    HorizontalDivider(color = cs.outline.copy(alpha = 0.4f))
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
    val text = vm.exportText()
    var confirmClear by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { vm.closeExport() },
        title = { Text("Eksporter til Artsobservasjoner") },
        text = {
            Column {
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

@Composable
private fun SectionLabel(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp,
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp, vertical = 6.dp))
}
