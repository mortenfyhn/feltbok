@file:OptIn(ExperimentalMaterial3Api::class)

package com.appobs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ============================ LIST ============================

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
                    items(vm.notes, key = { it.id }) { n -> NoteRow(n) { vm.editNote(n) } }
                }
            }
        }
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
    val fix = vm.fix
    val acc = when {
        fix == null -> "søker"
        fix.accuracyM.isNaN() -> "GPS"
        else -> "±${fix.accuracyM.toInt()} m"
    }
    Row(
        Modifier.fillMaxWidth().background(cs.primary).padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (near != null) "📍 ${near.lokalitet}, ${near.kommune}" else "📍 Finner posisjon…",
            color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Text(acc, color = Color.White, fontSize = 12.sp,
            modifier = Modifier.background(Color(0x33FFFFFF), RoundedCornerShape(10.dp))
                .padding(horizontal = 8.dp, vertical = 1.dp))
        if (vm.notes.isNotEmpty()) {
            Spacer(Modifier.width(6.dp))
            TextButton(onClick = { vm.openExport() }) {
                Text("Eksporter", color = Color.White, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun NoteRow(n: Note, onClick: () -> Unit) {
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
                overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 150.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(shortTime(n.id), color = cs.onSurfaceVariant, fontSize = 13.sp)
    }
    HorizontalDivider(color = cs.outline.copy(alpha = 0.4f))
}

// ============================ SEARCH ============================

@Composable
fun SearchScreen(vm: MainViewModel) {
    val cs = MaterialTheme.colorScheme
    var q by remember { mutableStateOf("") }
    val results = if (q.isBlank())
        vm.recent.mapNotNull { name -> vm.species.firstOrNull { it.norsk == name } }
    else
        vm.species.filter { it.norsk.contains(q, true) || it.latin.contains(q, true) }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().background(cs.surface).padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(q, { q = it }, Modifier.weight(1f), singleLine = true,
                placeholder = { Text("Søk art…") })
            TextButton(onClick = { vm.cancelSearch() }) { Text("Avbryt") }
        }
        if (q.isBlank()) SectionLabel("Nylig brukt")
        LazyColumn(Modifier.weight(1f)) {
            items(results, key = { it.norsk }) { s ->
                Column(Modifier.fillMaxWidth().clickable { vm.pickSpecies(s) }.background(cs.surface)
                    .padding(horizontal = 16.dp, vertical = 13.dp)) {
                    Text(s.norsk, fontWeight = FontWeight.Medium)
                    if (s.latin.isNotBlank())
                        Text(s.latin, color = cs.onSurfaceVariant, fontStyle = FontStyle.Italic, fontSize = 13.sp)
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
            modifier = Modifier.fillMaxWidth().background(cs.primary).padding(13.dp),
        )
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            // Locality
            FieldRow("Lokalitet", onClick = { vm.openLocalityPicker() }) {
                val loc = vm.dLoc
                if (loc == null) {
                    Text("Finner posisjon…", color = cs.onSurfaceVariant)
                } else {
                    Text(loc.lokalitet, fontWeight = FontWeight.Medium)
                    vm.distanceTo(loc)?.let {
                        Text("  ${formatDistance(it)}", color = cs.onSurfaceVariant, fontSize = 13.sp)
                    }
                }
            }
            // Species
            FieldRow("Art", onClick = { vm.changeSpecies() }) {
                Text(vm.dSpecies, fontWeight = FontWeight.Medium)
                if (vm.dLatin.isNotBlank())
                    Text("  ${vm.dLatin}", color = cs.onSurfaceVariant, fontStyle = FontStyle.Italic, fontSize = 13.sp)
            }
            AntallRow(vm)
            DropdownRow("Alder", vm.dAge, Options.ages) { vm.dAge = it }
            DropdownRow("Aktivitet", vm.dAct, Options.activities) { vm.dAct = it }
            DropdownRow("Kjønn", vm.dSex, Options.sexes) { vm.dSex = it }
            CommentField("Åpen kommentar", vm.dPub) { vm.dPub = it }
            CommentField("Privat kommentar", vm.dPriv) { vm.dPriv = it }
            FieldRow("Tidspunkt") {
                Text(displayTime(if (vm.dTime > 0) vm.dTime else System.currentTimeMillis()))
            }
            if (vm.isEditing) {
                TextButton(onClick = { vm.delete() }, modifier = Modifier.padding(8.dp)) {
                    Text("Slett observasjon", color = cs.error)
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().background(cs.surface).padding(horizontal = 14.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(onClick = { vm.cancel() }, modifier = Modifier.weight(1f)) { Text("Avbryt") }
            Button(onClick = { vm.save() }, enabled = vm.dSpecies.isNotBlank(),
                modifier = Modifier.weight(1.7f)) { Text("Lagre") }
        }
    }
}

@Composable
private fun AntallRow(vm: MainViewModel) {
    val cs = MaterialTheme.colorScheme
    // Local text so the field can be edited freely; reset when the draft changes.
    var text by remember(vm.dTime, vm.isEditing) { mutableStateOf(vm.dCount.toString()) }
    fun set(n: Int) { val c = n.coerceAtLeast(1); vm.setCount(c); text = c.toString() }
    Row(
        Modifier.fillMaxWidth().background(cs.surface).padding(horizontal = 16.dp, vertical = 10.dp),
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
                value = text,
                onValueChange = { v ->
                    text = v.filter { it.isDigit() }.take(4)
                    text.toIntOrNull()?.let { vm.setCount(it) }
                },
                modifier = Modifier.width(60.dp).padding(vertical = 10.dp),
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
    val cs = MaterialTheme.colorScheme
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth().clickable { open = true }.background(cs.surface)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, color = cs.onSurfaceVariant, fontSize = 14.sp, modifier = Modifier.width(84.dp))
            Spacer(Modifier.weight(1f))
            if (value.isNotBlank()) Text(value, fontWeight = FontWeight.Medium)
            Text("  ›", color = cs.outline, fontSize = 18.sp)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("—", color = cs.onSurfaceVariant) },
                onClick = { onSelect(""); open = false })
            options.forEach { opt ->
                DropdownMenuItem(text = { Text(opt) }, onClick = { onSelect(opt); open = false })
            }
        }
    }
    HorizontalDivider(color = cs.outline.copy(alpha = 0.4f))
}

@Composable
private fun CommentField(label: String, value: String, onChange: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.background(cs.surface).padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(label, color = cs.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(bottom = 4.dp))
        OutlinedTextField(value, onChange, Modifier.fillMaxWidth(), minLines = 2)
    }
    HorizontalDivider(color = cs.outline.copy(alpha = 0.4f))
}

@Composable
private fun FieldRow(label: String, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val base = Modifier.fillMaxWidth()
        .let { if (onClick != null) it.clickable(onClick = onClick) else it }
        .background(cs.surface).padding(horizontal = 16.dp, vertical = 14.dp)
    Row(base, verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = cs.onSurfaceVariant, fontSize = 14.sp, modifier = Modifier.width(84.dp))
        Spacer(Modifier.width(12.dp))
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically) { content() }
        if (onClick != null) Text("  ›", color = cs.outline, fontSize = 18.sp)
    }
    HorizontalDivider(color = cs.outline.copy(alpha = 0.4f))
}

// ============================ LOCALITY ============================

@Composable
fun LocalityScreen(vm: MainViewModel) {
    val cs = MaterialTheme.colorScheme
    var q by remember { mutableStateOf("") }
    val sorted = vm.localities
        .map { it to (vm.distanceTo(it) ?: Double.MAX_VALUE) }
        .filter { q.isBlank() || it.first.lokalitet.contains(q, true) }
        .sortedBy { it.second }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().background(cs.surface).padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(q, { q = it }, Modifier.weight(1f), singleLine = true,
                placeholder = { Text("Søk lokalitet…") })
            TextButton(onClick = { vm.backToDetail() }) { Text("Avbryt") }
        }
        LazyColumn(Modifier.weight(1f)) {
            items(sorted, key = { it.first.id.ifBlank { it.first.lokalitet } }) { (loc, d) ->
                val selected = loc == vm.dLoc
                Row(
                    Modifier.fillMaxWidth().clickable { vm.pickLocality(loc) }
                        .background(if (selected) cs.primaryContainer else cs.surface)
                        .padding(horizontal = 16.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(loc.lokalitet, fontWeight = FontWeight.SemiBold,
                            color = if (selected) cs.onPrimaryContainer else cs.onSurface)
                        if (loc.context.isNotBlank())
                            Text(loc.context, color = cs.onSurfaceVariant, fontSize = 12.5.sp)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        if (selected) Text("✓ valgt", color = cs.primary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        if (d != Double.MAX_VALUE)
                            Text(formatDistance(d), color = cs.onSurfaceVariant, fontSize = 13.sp)
                    }
                }
                HorizontalDivider(color = cs.outline.copy(alpha = 0.4f))
            }
        }
    }
}

// ============================ EXPORT ============================

@Composable
fun ExportDialog(vm: MainViewModel) {
    val cs = MaterialTheme.colorScheme
    val text = remember { vm.exportText() }
    val clip = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = { vm.closeExport() },
        title = { Text("Eksporter til Artsobservasjoner") },
        text = {
            Column {
                Text("Lim inn i Importer observasjoner (gammel side). Navn + registrert koordinat kobles til offisiell lokalitet.",
                    color = cs.onSurfaceVariant, fontSize = 13.sp)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(text, {}, Modifier.fillMaxWidth().height(180.dp), readOnly = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp))
            }
        },
        confirmButton = {
            Button(onClick = { clip.setText(AnnotatedString(text)); vm.closeExport() }) { Text("Kopiér") }
        },
        dismissButton = { TextButton(onClick = { vm.closeExport() }) { Text("Lukk") } },
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp,
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp, vertical = 6.dp))
}
