// The locality picker drawn as MapLibre layers instead of on a Compose Canvas, so the shapes render
// in the same frame as the tiles and stay glued to the map while panning and zooming.
//
// Kotlin still decides *what* to draw - the same cull, declutter, colours and label priorities as
// LocalityOverlay - and hands MapLibre GeoJSON for the area around the view. MapLibre draws it and
// places the labels with its own collision pass (symbol sort key = Android's label priority).
package io.github.mortenfyhn.feltbok

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import org.maplibre.compose.expressions.dsl.case
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.convertToColor
import org.maplibre.compose.expressions.dsl.convertToNumber
import org.maplibre.compose.expressions.dsl.convertToString
import org.maplibre.compose.expressions.dsl.feature
import org.maplibre.compose.expressions.dsl.format
import org.maplibre.compose.expressions.dsl.span
import org.maplibre.compose.expressions.dsl.switch
import org.maplibre.compose.expressions.dsl.textOffset
import org.maplibre.compose.expressions.value.SymbolAnchor
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.FillLayer
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import kotlin.math.max

/** One frame's worth of GeoJSON for the layers, built off the main thread by [buildLayerData]. */
@Immutable
class LayerData(
    val shapes: String,
    val dots: String,
    val labels: String,
    val picked: String,
    val pickedLabel: String,
    val gpsAcc: String,
    val gpsDot: String,
) {
    companion object {
        private const val EMPTY = """{"type":"FeatureCollection","features":[]}"""
        val Empty = LayerData(EMPTY, EMPTY, EMPTY, EMPTY, EMPTY, EMPTY, EMPTY)
    }
}

/** Android px → dp on the reference phone (see MapPicker.kt's ANDROID_DENSITY). */
private fun apx(px: Float) = (px / 2.625f).dp

private fun rgba(argb: Long): String {
    val a = ((argb shr 24) and 0xFF) / 255.0
    return "rgba(${(argb shr 16) and 0xFF},${(argb shr 8) and 0xFF},${argb and 0xFF},$a)"
}

private fun StringBuilder.str(s: String) {
    append('"')
    for (c in s) when (c) {
        '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append(' ')
        else -> append(c)
    }
    append('"')
}

private class Collection {
    val sb = StringBuilder("""{"type":"FeatureCollection","features":[""")
    private var first = true
    fun feature(geometry: StringBuilder.() -> Unit, props: StringBuilder.() -> Unit) {
        if (!first) sb.append(',')
        first = false
        sb.append("""{"type":"Feature","geometry":""")
        sb.geometry()
        sb.append(""","properties":{""")
        sb.props()
        sb.append("}}")
    }
    fun json() = sb.append("]}").toString()
}

private fun StringBuilder.point(lat: Double, lon: Double) {
    append("""{"type":"Point","coordinates":[""").append(lon).append(',').append(lat).append("]}")
}

private fun StringBuilder.polygon(ring: List<DoubleArray>) {
    append("""{"type":"Polygon","coordinates":[[""")
    ring.forEachIndexed { i, v -> if (i > 0) append(','); append('[').append(v[1]).append(',').append(v[0]).append(']') }
    val f = ring.first(); val l = ring.last()
    if (f[0] != l[0] || f[1] != l[1]) append(",[").append(f[1]).append(',').append(f[0]).append(']')
    append("]]}")
}

/** [lat, lon] ring approximating a [radiusM] circle, for MapLibre, which has no geodesic circles. */
private fun circleRing(lat: Double, lon: Double, radiusM: Double, steps: Int = 48): List<DoubleArray> {
    val dLat = radiusM / 111_320.0
    val dLon = radiusM / (111_320.0 * kotlin.math.cos(lat * kotlin.math.PI / 180))
    return List(steps + 1) { i ->
        val a = 2 * kotlin.math.PI * i / steps
        doubleArrayOf(lat + dLat * kotlin.math.sin(a), lon + dLon * kotlin.math.cos(a))
    }
}

/**
 * The layers' data for localities overlapping [bb] ([latS, lonW, latN, lonE], padded beyond the view
 * so a pan reveals already-drawn shapes) at osmdroid zoom [zoom] - LocalityOverlay.draw's choices,
 * as GeoJSON.
 */
fun buildLayerData(
    localities: List<Locality>, picked: Locality?, fix: GpsFix?,
    bb: DoubleArray, zoom: Double, ppm: Double,
): LayerData {
    val shapes = Collection(); val dots = Collection()
    // One label collection for all localities: MapLibre only weighs label priorities (the sort key)
    // against each other within a layer, so dot and footprint labels must share one.
    val labels = Collection()
    fun visible(l: Locality): Boolean {
        val b = l.cullBounds
        return !(b[1] < bb[0] || b[0] > bb[2] || b[3] < bb[1] || b[2] > bb[3])
    }
    fun labelSpan(l: Locality) =
        if (l.polygon.isEmpty() && l.radius <= 0.0) ((if (l.isSuper) 500.0 else 60.0) * ppm).toFloat() else screenSpanPx(l, ppm)
    fun addLabel(l: Locality, priority: Float, to: Collection?) {
        val centredLabel = l.polygon.isNotEmpty() || (l.radius > 0.0 && l.radius * ppm >= 15f)
        val halo = when { !l.public -> MapPalette.LabelHaloYellow; l.isSuper -> MapPalette.LabelHaloSuper; else -> MapPalette.LabelHaloGreen }
        val a = l.labelAnchor
        (to ?: labels).feature({ point(a[0], a[1]) }) {
            append("\"name\":"); str(l.lokalitet)
            append(",\"pos\":\"").append(if (centredLabel) "centre" else "below").append('"')
            append(",\"halo\":\"").append(rgba(halo)).append('"')
            append(",\"sort\":").append(-priority.coerceAtMost(1e9f))   // lower sorts first = wins
        }
    }
    for (l in localities) {
        if (l === picked || !visible(l)) continue
        val span = screenSpanPx(l, ppm)
        if (declutteredAtZoom(zoom, span)) continue
        val big = span > 140f
        val fill = when {
            !l.public -> if (big) MapPalette.YellowFillPale else MapPalette.YellowFill
            l.isSuper -> when {
                big -> MapPalette.SuperFillPale
                l.polygon.isEmpty() && l.radius <= 0.0 -> MapPalette.SuperFillSolid
                else -> MapPalette.SuperFill
            }
            else -> if (big) MapPalette.GreenFillPale else MapPalette.GreenFill
        }
        val stroke = when { !l.public -> MapPalette.YellowStroke; l.isSuper -> MapPalette.SuperStroke; else -> MapPalette.GreenStroke }
        val props: StringBuilder.() -> Unit = {
            append("\"fill\":\"").append(rgba(fill)).append("\",\"stroke\":\"").append(rgba(stroke)).append('"')
        }
        when {
            l.polygon.isNotEmpty() -> shapes.feature({ polygon(l.polygon) }, props)
            // A circle too small to show its real size draws as the fixed dot, like radiusPx's floor.
            l.radius > 0.0 && l.radius * ppm >= 15f -> shapes.feature({ polygon(circleRing(l.lat, l.lon, l.radius)) }, props)
            else -> dots.feature({ point(l.lat, l.lon) }, props)
        }
        val ls = labelSpan(l)
        if (ls >= 14f) addLabel(l, ls, null)
    }
    val picked1 = Collection(); val pickedLabel = Collection()
    picked?.let { pl ->
        val faint = selectedFillIsFaint(pl, ppm)
        val fill = if (pl.public) (if (faint) MapPalette.SelFillFaint else MapPalette.SelFill) else (if (faint) MapPalette.SelFillFaintPriv else MapPalette.SelFillPriv)
        val stroke = if (pl.public) MapPalette.SelStroke else MapPalette.SelStrokePriv
        val ring = when {
            pl.polygon.isNotEmpty() -> pl.polygon
            else -> circleRing(pl.lat, pl.lon, max(pl.radius, 15.0 / ppm))
        }
        picked1.feature({ polygon(ring) }) {
            append("\"fill\":\"").append(rgba(fill)).append("\",\"stroke\":\"").append(rgba(stroke)).append('"')
        }
        addLabel(pl, Float.MAX_VALUE, pickedLabel)
    }
    val gpsAcc = Collection(); val gpsDot = Collection()
    fix?.let { f ->
        if (!f.accuracyM.isNaN() && f.accuracyM > 0f) {
            val r = (f.accuracyM.toDouble()).coerceIn(8.0 / ppm, 600.0 / ppm)
            gpsAcc.feature({ polygon(circleRing(f.lat, f.lon, r)) }) {}
        }
        gpsDot.feature({ point(f.lat, f.lon) }) {}
    }
    return LayerData(shapes.json(), dots.json(), labels.json(), picked1.json(), pickedLabel.json(),
        gpsAcc.json(), gpsDot.json())
}

/** No line simplification and full-detail tiles: the default simplifies small circles into visible
 *  polygons, where Android draws them round. */
private val ExactShapes: org.maplibre.compose.sources.GeoJsonOptions.Builder.() -> Unit = {
    tolerance = 0f
    maxZoom = 22
}

private val LabelFont = const(listOf("Noto Sans Regular"))

// Android draws 38 px labels wrapped at 210 px on the reference phone; Noto Sans runs larger than
// the system font at the same size, so it's scaled down to match the Canvas renderer's look.
private const val LABEL_SP = 38f / 2.625f * 0.94f
private val LabelSize = const(LABEL_SP.sp)
private val LabelMaxWidth = const((210f / 2.625f / LABEL_SP).em)

/** The layers themselves, in LocalityOverlay's paint order: shapes, dots, the pick, labels, GPS. */
@Composable
fun LocalityLayers(d: LayerData) {
    val shapes = rememberGeoJsonSource(GeoJsonData.JsonString(d.shapes), block = ExactShapes)
    val dots = rememberGeoJsonSource(GeoJsonData.JsonString(d.dots))
    val picked = rememberGeoJsonSource(GeoJsonData.JsonString(d.picked), block = ExactShapes)
    val gpsAcc = rememberGeoJsonSource(GeoJsonData.JsonString(d.gpsAcc), block = ExactShapes)
    val gpsDot = rememberGeoJsonSource(GeoJsonData.JsonString(d.gpsDot))
    val labels = rememberGeoJsonSource(GeoJsonData.JsonString(d.labels))
    val pickedLabel = rememberGeoJsonSource(GeoJsonData.JsonString(d.pickedLabel))
    val fill = feature["fill"].convertToColor()
    val stroke = feature["stroke"].convertToColor()

    FillLayer(id = "loc-fill", source = shapes, color = fill)
    LineLayer(id = "loc-line", source = shapes, color = stroke, width = const(apx(2f)))
    CircleLayer(id = "loc-dots", source = dots, color = fill, radius = const(apx(15f)),
        strokeColor = stroke, strokeWidth = const(apx(2f)))
    FillLayer(id = "pick-fill", source = picked, color = fill)
    LineLayer(id = "pick-line", source = picked, color = stroke, width = const(apx(6f)))
    // Labels: 38 px text with a type-tinted halo, wrapped at ~210 px, padded 8 px - as on Android.
    val text = format(span(feature["name"].convertToString()))
    val halo = feature["halo"].convertToColor()
    val sort = feature["sort"].convertToNumber()
    val labelColor = const(Color(MapPalette.LabelText.toInt()))
    // A footprint's name sits centred on it; a dot's name sits just below the dot (labelFor).
    val below = feature["pos"]
    SymbolLayer(id = "labels", source = labels, sortKey = sort, textField = text, textFont = LabelFont,
        textSize = LabelSize,
        textColor = labelColor, textHaloColor = halo, textHaloWidth = const(1.5.dp),
        textMaxWidth = LabelMaxWidth, textPadding = const(apx(8f)), textLineHeight = const(1.0f.em),
        textAnchor = switch(below, case("below", const(SymbolAnchor.Top)), fallback = const(SymbolAnchor.Center)),
        textOffset = switch(below, case("below", textOffset(0.dp, apx(15f + 6f))), fallback = textOffset(0.dp, 0.dp)))
    // The pick always gets its name, and others keep clear of it.
    SymbolLayer(id = "label-picked", source = pickedLabel, textField = text, textFont = LabelFont,
        textSize = LabelSize,
        textColor = labelColor, textHaloColor = halo, textHaloWidth = const(1.5.dp),
        textMaxWidth = LabelMaxWidth, textLineHeight = const(1.0f.em), textAllowOverlap = const(true))
    // The GPS fix on top of everything, labels included, as on Android.
    FillLayer(id = "gps-acc", source = gpsAcc, color = const(Color(MapPalette.GpsAccFillFaint.toInt())))
    LineLayer(id = "gps-acc-line", source = gpsAcc, color = const(Color(MapPalette.GpsAccStrokeFaint.toInt())), width = const(apx(2f)))
    CircleLayer(id = "gps-dot", source = gpsDot, color = const(Color(MapPalette.Gps.toInt())), radius = const(apx(18f)),
        strokeColor = const(Color.White), strokeWidth = const(apx(3f)))
}
