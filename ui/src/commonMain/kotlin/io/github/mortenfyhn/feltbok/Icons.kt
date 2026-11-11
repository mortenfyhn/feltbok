package io.github.mortenfyhn.feltbok

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

// Material's "add" and "remove" icons, inlined like GroupIcon. A text "+" or "−" sits wherever the
// font puts it in its line box (2-3 pt low on iOS), so round buttons draw these instead: they're
// centred by geometry, on every platform and text size.
internal val AddIcon = materialIcon("Add", "M19,13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z")
internal val RemoveIcon = materialIcon("Remove", "M19,13H5v-2h14v2z")

private fun materialIcon(name: String, path: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
    .addPath(addPathNodes(path), fill = SolidColor(Color.Black)).build()
