package io.github.mortenfyhn.feltbok

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest

// Name suggestions for a new locality (#137): a "Navneforslag" link under the name field opens the
// nearby place names, and tapping one fills the field. Nothing is filled in without that tap, so a
// name is always a choice. Follows the map as you pan; silent when offline.

class PlaceSuggestions {
    var list by mutableStateOf<List<PlaceSuggestion>>(emptyList())
    var loading by mutableStateOf(false)
}

/** Looks up names for [center] while [active], again each time [moves] emits and the map settles. */
@Composable
fun rememberPlaceSuggestions(
    vm: MainViewModel,
    active: Boolean,
    moves: Flow<*>,
    center: () -> Pair<Double, Double>?,
): PlaceSuggestions {
    val s = remember { PlaceSuggestions() }
    val currentCenter by rememberUpdatedState(center)
    LaunchedEffect(active) {
        s.list = emptyList()
        if (!active) return@LaunchedEffect
        moves.collectLatest {
            s.list = emptyList()                        // names for the old spot are wrong once you pan
            delay(100)                                  // let panning settle; the next move cancels this
            val (lat, lon) = currentCenter() ?: return@collectLatest
            s.loading = true
            try {
                s.list = vm.placeNames(lat, lon)
            } finally {
                s.loading = false
            }
        }
    }
    return s
}

/** Drops focus (closing the keyboard) when the map is touched, without consuming the touch. */
@Composable
fun Modifier.clearFocusOnPress(): Modifier {
    val focusManager = LocalFocusManager.current
    return pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                if (awaitPointerEvent(PointerEventPass.Initial).type == PointerEventType.Press) focusManager.clearFocus()
            }
        }
    }
}

/** The "Ny lokalitet" panel: name and Lagre beside the platform's radius wheel ([content]). The form is at
 *  least as tall as the wheel, so Lagre sits at the bottom. */
@Composable
fun NewLocalityPanel(
    name: String,
    onName: (String) -> Unit,
    places: PlaceSuggestions,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var wheelH by remember { mutableIntStateOf(0) }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(
            Modifier.weight(1f).heightIn(min = with(LocalDensity.current) { wheelH.toDp() }),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NewLocalityNameField(name, onName, places)
            }
            Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) { Text(Strings.Picker.save) }
        }
        Column(Modifier.onSizeChanged { wheelH = it.height }, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(Strings.Picker.radiusLabel, color = cs.onSurfaceVariant, fontSize = 12.sp)
            content()
        }
    }
}

@Composable
private fun NewLocalityNameField(name: String, onName: (String) -> Unit, places: PlaceSuggestions) {
    val cs = MaterialTheme.colorScheme
    val focusManager = LocalFocusManager.current
    var showList by remember { mutableStateOf(false) }
    Text(Strings.Picker.nameLabel, color = cs.onSurfaceVariant, fontSize = 12.sp)   // as "Radius" beside it
    // BasicTextField for the compact height, as in the species search (Ui.kt).
    BasicTextField(
        name, onName,
        Modifier.fillMaxWidth().border(1.dp, cs.outline, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
        singleLine = true,
        textStyle = LocalTextStyle.current.copy(color = cs.onSurface),
        cursorBrush = SolidColor(cs.primary),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
        decorationBox = { inner ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    // What an empty field saves as.
                    if (name.isEmpty()) Text(Strings.Picker.namePlaceholder, color = cs.onSurfaceVariant)
                    inner()
                }
                if (name.isNotEmpty()) Text("✕", color = cs.onSurfaceVariant,
                    modifier = Modifier.clickable { onName("") }.padding(start = 8.dp))
            }
        },
    )
    // Coming and going only takes from the space above Lagre, so nothing else moves.
    if (places.loading || places.list.isNotEmpty()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (places.list.isNotEmpty()) {
                Text(
                    Strings.Picker.suggestions(places.list.size), color = cs.primary, fontSize = 13.sp,
                    modifier = Modifier.clickable { showList = true },
                )
            }
            if (places.loading) CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp, color = cs.onSurfaceVariant)
        }
    }
    if (showList) PlaceNameDialog(places.list, onPick = { onName(it); showList = false }, onDismiss = { showList = false })
}

@Composable
private fun PlaceNameDialog(suggestions: List<PlaceSuggestion>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Strings.Picker.suggestionsTitle) },
        text = {
            LazyColumn(Modifier.heightIn(max = 400.dp)) {
                items(suggestions) { s ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(s.name) }.padding(vertical = 11.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(s.name, color = cs.onSurface)
                            Text(s.type, color = cs.onSurfaceVariant, fontSize = 12.sp)
                        }
                        Text(Strings.Picker.meters(s.distanceM), color = cs.onSurfaceVariant, fontSize = 13.sp)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(Strings.cancel) } },
    )
}
