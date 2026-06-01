package com.appobs

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Point
import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The locality picker, as a map. Localities are drawn as green disks at their real
 * radius (like the Artsobservasjoner site) over OpenStreetMap tiles, which osmdroid
 * caches on disk for offline reuse. Tap a disk to highlight it, then "Velg" confirms.
 */
@Composable
fun LocalityScreen(vm: MainViewModel) {
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current

    val mapView = remember {
        configureOsmdroid(ctx)
        MapView(ctx).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            val start = vm.dLoc ?: vm.nearest()
            controller.setZoom(16.0)
            controller.setCenter(GeoPoint(start?.lat ?: vm.fix?.lat ?: 63.7,
                                          start?.lon ?: vm.fix?.lon ?: 8.7))
        }
    }
    val overlay = remember {
        LocalityOverlay(vm.localities) { vm.pickLocality(it) }   // tap selects and returns
            .also { mapView.overlays.add(it) }
    }

    DisposableEffect(Unit) {
        mapView.onResume()
        onDispose { mapView.onPause(); mapView.onDetach() }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().background(cs.primary).padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Velg lokalitet", color = Color.White, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f))
            TextButton(onClick = { vm.backToDetail() }) { Text("Avbryt", color = Color.White) }
        }
        AndroidView(
            factory = { mapView },
            modifier = Modifier.weight(1f).fillMaxWidth(),
            update = { m ->
                // Only touch the overlay when something actually changed, so a GPS tick
                // mid-gesture doesn't force a redraw (which flickers the view).
                val newFix = vm.fix?.let { GeoPoint(it.lat, it.lon) }
                val newAcc = vm.fix?.accuracyM?.toFloat() ?: Float.NaN
                if (overlay.picked !== vm.dLoc || overlay.fix != newFix ||
                    overlay.accuracyM.toRawBits() != newAcc.toRawBits()) {
                    overlay.picked = vm.dLoc
                    overlay.fix = newFix
                    overlay.accuracyM = newAcc
                    m.invalidate()
                }
            },
        )
    }
}

internal fun configureOsmdroid(ctx: Context) {
    Configuration.getInstance().apply {
        userAgentValue = ctx.packageName               // required by the OSM tile policy
        osmdroidBasePath = File(ctx.cacheDir, "osmdroid")
        osmdroidTileCache = File(ctx.cacheDir, "osmdroid/tiles")
    }
}

/**
 * A small round minimap for the main screen: tightly zoomed on the GPS-nearest
 * locality (the one named in the status strip) with your position pin. Static and
 * non-interactive; tiles are the same cached OSM ones as the picker.
 */
@Composable
fun LocalityPreview(vm: MainViewModel, modifier: Modifier = Modifier) {
    vm.nearest() ?: return                       // nothing to preview until GPS settles
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val map = remember {
        configureOsmdroid(ctx)
        MapView(ctx).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(false)
            setOnTouchListener { _, _ -> true }   // static preview: swallow pan/zoom
            controller.setZoom(16.0)              // ~280 m across, so a ~100 m accuracy disk reads as a disk
        }
    }
    val overlay = remember { PreviewOverlay().also { map.overlays.add(it) } }
    DisposableEffect(Unit) {
        map.onResume()
        onDispose { map.onPause(); map.onDetach() }
    }
    AndroidView(
        factory = { map },
        modifier = modifier.size(96.dp).clip(CircleShape).border(2.dp, cs.outline, CircleShape),
        update = { m ->
            // Read live state HERE so the view redraws when the fix (incl. accuracy) changes.
            val f = vm.fix
            val n = vm.nearest()
            overlay.loc = n
            overlay.fix = f?.let { GeoPoint(it.lat, it.lon) }
            overlay.accuracyM = f?.accuracyM?.toFloat() ?: Float.NaN
            // Centre on the GPS fix (where you are) when available, else the locality.
            val cLat = f?.lat ?: n?.lat
            val cLon = f?.lon ?: n?.lon
            if (cLat != null && cLon != null) m.controller.setCenter(GeoPoint(cLat, cLon))
            m.invalidate()
        },
    )
}

/** Draws a single locality disk plus the GPS pin, for the main-screen minimap. */
private class PreviewOverlay : Overlay() {
    var loc: Locality? = null
    var fix: GeoPoint? = null
    var accuracyM: Float = Float.NaN

    private val fill = Paint().apply { style = Paint.Style.FILL; color = 0x553C8C28.toInt(); isAntiAlias = true }
    private val stroke = Paint().apply { style = Paint.Style.STROKE; color = 0xFF2E7D32.toInt(); strokeWidth = 3f; isAntiAlias = true }
    private val accFill = Paint().apply { style = Paint.Style.FILL; color = 0x332962FF.toInt(); isAntiAlias = true }
    private val accStroke = Paint().apply { style = Paint.Style.STROKE; color = 0xAA2962FF.toInt(); strokeWidth = 2.5f; isAntiAlias = true }
    private val gps = Paint().apply { style = Paint.Style.FILL; color = 0xFF2962FF.toInt(); isAntiAlias = true }
    private val gpsRing = Paint().apply { style = Paint.Style.STROKE; color = 0xFFFFFFFF.toInt(); strokeWidth = 3f; isAntiAlias = true }
    private val p = Point()

    override fun draw(c: Canvas, map: MapView, shadow: Boolean) {
        if (shadow) return
        val proj = map.projection
        val center = map.mapCenter
        val a = proj.toPixels(GeoPoint(center.latitude, center.longitude), null)
        val b = proj.toPixels(GeoPoint(center.latitude + 100.0 / 111_320.0, center.longitude), null)
        val ppm = abs(a.y - b.y) / 100.0
        loc?.let {
            val m = if (it.radius > 0) it.radius else 40.0
            val rPx = (m * ppm).toFloat().coerceIn(4f, 200f)
            proj.toPixels(GeoPoint(it.lat, it.lon), p)
            c.drawCircle(p.x.toFloat(), p.y.toFloat(), rPx, fill)
            c.drawCircle(p.x.toFloat(), p.y.toFloat(), rPx, stroke)
        }
        fix?.let {
            proj.toPixels(it, p)
            val px = p.x.toFloat(); val py = p.y.toFloat()
            if (!accuracyM.isNaN() && accuracyM > 0f) {
                val rAcc = (accuracyM * ppm).toFloat().coerceIn(6f, 400f)
                c.drawCircle(px, py, rAcc, accFill)
                c.drawCircle(px, py, rAcc, accStroke)
            }
            c.drawCircle(px, py, 7f, gps)
            c.drawCircle(px, py, 7f, gpsRing)
        }
    }
}

/** Draws each locality as a green disk at its real-world radius and resolves taps to
 *  the nearest locality. Public localities are green; private ones will be yellow once
 *  we have the allmenn flag. */
private class LocalityOverlay(
    private val localities: List<Locality>,
    private val onTap: (Locality) -> Unit,
) : Overlay() {
    // Fallback radius for localities whose footprint is unknown (radius == 0).
    private val defaultRadiusM = 40.0
    var picked: Locality? = null
    var fix: GeoPoint? = null
    var accuracyM: Float = Float.NaN

    private val fill = Paint().apply { style = Paint.Style.FILL; color = 0x553C8C28.toInt(); isAntiAlias = true }
    private val stroke = Paint().apply { style = Paint.Style.STROKE; color = 0xFF2E7D32.toInt(); strokeWidth = 2f; isAntiAlias = true }
    private val badgeFill = Paint().apply { style = Paint.Style.FILL; color = 0xFFFFFFFF.toInt(); isAntiAlias = true }
    private val badgeRing = Paint().apply { style = Paint.Style.STROKE; color = 0xFF1B5E20.toInt(); strokeWidth = 3f; isAntiAlias = true }
    private val check = Paint().apply { style = Paint.Style.STROKE; color = 0xFF1B5E20.toInt(); strokeWidth = 5f; strokeCap = Paint.Cap.ROUND; isAntiAlias = true }
    private val accFill = Paint().apply { style = Paint.Style.FILL; color = 0x222962FF.toInt(); isAntiAlias = true }
    private val accStroke = Paint().apply { style = Paint.Style.STROKE; color = 0x882962FF.toInt(); strokeWidth = 2f; isAntiAlias = true }
    private val gps = Paint().apply { style = Paint.Style.FILL; color = 0xFF2962FF.toInt(); isAntiAlias = true }
    private val gpsRing = Paint().apply { style = Paint.Style.STROKE; color = 0xFFFFFFFF.toInt(); strokeWidth = 3f; isAntiAlias = true }
    private val labelFill = Paint().apply { color = 0xFF18250F.toInt(); textSize = 28f; textAlign = Paint.Align.CENTER; isAntiAlias = true }
    private val labelHalo = Paint().apply { color = 0xF2FFFFFF.toInt(); textSize = 28f; textAlign = Paint.Align.CENTER; isAntiAlias = true; style = Paint.Style.STROKE; strokeWidth = 4f }
    private val p = Point()

    /** Pixels per metre at the current zoom, from projecting a 100 m north step. */
    private fun pxPerMeter(map: MapView): Double {
        val c = map.mapCenter
        val a = map.projection.toPixels(GeoPoint(c.latitude, c.longitude), null)
        val b = map.projection.toPixels(GeoPoint(c.latitude + 100.0 / 111_320.0, c.longitude), null)
        return abs(a.y - b.y) / 100.0
    }

    private fun radiusPx(loc: Locality, ppm: Double): Float {
        val m = if (loc.radius > 0) loc.radius else defaultRadiusM
        return (m * ppm).toFloat().coerceIn(3f, 260f)
    }

    private val lineH = 32f

    /** Draw [name] centred on (cx, cy), wrapped so it doesn't get too wide. */
    private fun drawLabel(c: Canvas, name: String, cx: Float, cy: Float) {
        val lines = wrapLabel(name, 210f)
        var ty = cy - (lines.size - 1) * lineH / 2f + 10f
        for (line in lines) {
            c.drawText(line, cx, ty, labelHalo)
            c.drawText(line, cx, ty, labelFill)
            ty += lineH
        }
    }

    private fun wrapLabel(name: String, maxW: Float): List<String> {
        if (labelFill.measureText(name) <= maxW) return listOf(name)
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (word in name.split(" ")) {
            val trial = if (sb.isEmpty()) word else "$sb $word"
            if (sb.isEmpty() || labelFill.measureText(trial) <= maxW) {
                sb.setLength(0); sb.append(trial)
            } else {
                out.add(sb.toString()); sb.setLength(0); sb.append(word)
            }
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }

    override fun draw(c: Canvas, map: MapView, shadow: Boolean) {
        if (shadow) return
        val proj = map.projection
        val ppm = pxPerMeter(map)
        val bb = map.boundingBox
        val w = map.width; val h = map.height
        val showLabels = map.zoomLevelDouble >= 15.5     // names only when zoomed in enough to read them
        var pickX = 0f; var pickY = 0f; var pickR = 0f; var pickShown = false
        for (loc in localities) {
            if (bb != null && (loc.lat > bb.latNorth || loc.lat < bb.latSouth ||
                    loc.lon < bb.lonWest || loc.lon > bb.lonEast)) continue
            proj.toPixels(GeoPoint(loc.lat, loc.lon), p)
            val rPx = radiusPx(loc, ppm)
            if (p.x < -rPx || p.x > w + rPx || p.y < -rPx || p.y > h + rPx) continue
            val px = p.x.toFloat(); val py = p.y.toFloat()
            c.drawCircle(px, py, rPx, fill)
            c.drawCircle(px, py, rPx, stroke)
            if (showLabels) drawLabel(c, loc.lokalitet, px, py)   // name on top of the disk, wrapped
            if (loc === picked) { pickX = px; pickY = py; pickR = rPx; pickShown = true }
        }
        if (pickShown) {                              // selected: a checkmark badge at the lower-right
            val bx = pickX + pickR * 0.7f + 6f
            val by = pickY + pickR * 0.7f + 6f
            c.drawCircle(bx, by, 24f, badgeFill)
            c.drawCircle(bx, by, 24f, badgeRing)
            c.drawLine(bx - 11f, by + 1f, bx - 3f, by + 12f, check)
            c.drawLine(bx - 3f, by + 12f, bx + 13f, by - 11f, check)
        }
        fix?.let {
            proj.toPixels(it, p)
            val px = p.x.toFloat(); val py = p.y.toFloat()
            if (!accuracyM.isNaN() && accuracyM > 0f) {
                val rAcc = (accuracyM * ppm).toFloat().coerceIn(8f, 600f)
                c.drawCircle(px, py, rAcc, accFill)
                c.drawCircle(px, py, rAcc, accStroke)
            }
            c.drawCircle(px, py, 11f, gps)            // bigger GPS dot
            c.drawCircle(px, py, 11f, gpsRing)
        }
    }

    // onSingleTapUp (not ...Confirmed) so selection is instant instead of waiting out
    // the double-tap timeout. Double-tap-to-zoom is sacrificed; pinch still zooms.
    override fun onSingleTapUp(e: MotionEvent, map: MapView): Boolean {
        val proj = map.projection
        val ppm = pxPerMeter(map)
        var best: Locality? = null
        var bestD = Float.MAX_VALUE
        for (loc in localities) {
            proj.toPixels(GeoPoint(loc.lat, loc.lon), p)
            val d = hypot((p.x - e.x), (p.y - e.y))
            // accept a tap inside the disk, or within a comfortable touch radius
            if (d < bestD && d <= maxOf(radiusPx(loc, ppm), 44f)) { bestD = d; best = loc }
        }
        best?.let { onTap(it); return true }
        return false
    }
}
