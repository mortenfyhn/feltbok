package com.appobs

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.Overlay
import kotlinx.coroutines.delay
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * The locality picker, as a map. Localities are drawn as green disks at their real
 * radius (like the Artsobservasjoner site) over OpenStreetMap tiles, which osmdroid
 * caches on disk for offline reuse. Tap a disk to highlight it, then "Velg" confirms.
 */
@Composable
fun LocalityScreen(vm: MainViewModel) {
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    var tapped by remember { mutableStateOf<Locality?>(null) }

    val mapView = remember {
        configureOsmdroid(ctx)
        MapView(ctx).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)  // use our own buttons
            if (vm.mapLat != 0.0) {                  // restore the last camera if we have one
                controller.setZoom(vm.mapZoom)
                controller.setCenter(GeoPoint(vm.mapLat, vm.mapLon))
            } else {
                val start = vm.dLoc ?: vm.nearest()
                controller.setZoom(16.0)
                controller.setCenter(GeoPoint(start?.lat ?: vm.fix?.lat ?: 63.7,
                                              start?.lon ?: vm.fix?.lon ?: 8.7))
            }
        }
    }
    val overlay = remember {
        LocalityOverlay(vm.localities) { tapped = it; mapView.invalidate() }   // highlight, then go
            .also { mapView.overlays.add(it) }
    }
    // Flash the tapped locality highlighted, then return to the entry screen.
    LaunchedEffect(tapped) { tapped?.let { delay(300); vm.pickLocality(it) } }

    DisposableEffect(Unit) {
        mapView.onResume()
        onDispose {
            vm.mapZoom = mapView.zoomLevelDouble     // keep the camera for next time
            vm.mapLat = mapView.mapCenter.latitude
            vm.mapLon = mapView.mapCenter.longitude
            mapView.onPause(); mapView.onDetach()
        }
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
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                factory = { mapView },
                modifier = Modifier.fillMaxSize().clipToBounds(),  // keep the map off the toolbar
                update = { m ->
                    // Only touch the overlay when something actually changed, so a GPS tick
                    // mid-gesture doesn't force a redraw (which flickers the view).
                    val sel = tapped ?: vm.dLoc      // highlight the just-tapped one, else the current pick
                    val newFix = vm.fix?.let { GeoPoint(it.lat, it.lon) }
                    val newAcc = vm.fix?.accuracyM?.toFloat() ?: Float.NaN
                    if (overlay.picked !== sel || overlay.fix != newFix ||
                        overlay.accuracyM.toRawBits() != newAcc.toRawBits()) {
                        overlay.picked = sel
                        overlay.fix = newFix
                        overlay.accuracyM = newAcc
                        m.invalidate()
                    }
                },
            )
            // One-handed zoom: thumb-reachable buttons (pinch is awkward one-handed).
            Column(
                Modifier.align(Alignment.BottomEnd).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ZoomButton("+") { mapView.controller.zoomIn() }
                ZoomButton("−") { mapView.controller.zoomOut() }
            }
        }
    }
}

@Composable
private fun ZoomButton(label: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier.size(52.dp).clip(CircleShape).background(Color.White)
            .border(1.dp, cs.outline, CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = cs.primary, fontSize = 28.sp, fontWeight = FontWeight.Bold) }
}

internal fun configureOsmdroid(ctx: Context) {
    Configuration.getInstance().apply {
        userAgentValue = ctx.packageName               // required by the OSM tile policy
        osmdroidBasePath = File(ctx.cacheDir, "osmdroid")
        osmdroidTileCache = File(ctx.cacheDir, "osmdroid/tiles")
        animationSpeedDefault = 200                    // snappier zoom (default ~1s lags fast pinches)
        animationSpeedShort = 100
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
            // Centre on the locality so it's always shown, with your GPS pin beside it.
            val cLat = n?.lat ?: f?.lat
            val cLon = n?.lon ?: f?.lon
            if (cLat != null && cLon != null) m.controller.setCenter(GeoPoint(cLat, cLon))
            m.invalidate()
        },
    )
}

/** Draws a single locality disk plus the GPS pin, for the main-screen minimap. */
/** Artsobservasjoner only lets a locality's radius be one of these fixed values, so any
 *  other number is a computed coordinateUncertaintyInMeters - i.e. a point locality, not a
 *  circle. 300 m is GBIF's "unknown" default (a third of all points), so it's a point too.
 *  Points draw as a small fixed dot; only these values draw a real radius circle. */
private val CIRCLE_RADII_M = setOf(1, 5, 10, 25, 50, 75, 100, 125, 150, 200, 250, 400, 500,
    750, 1000, 1500, 2000, 2500, 3000, 5000)
private fun isCircleLocality(radiusM: Double) = radiusM.toInt() in CIRCLE_RADII_M
private const val POINT_DOT_PX = 11f

private class PreviewOverlay : Overlay() {
    var loc: Locality? = null
    var fix: GeoPoint? = null
    var accuracyM: Float = Float.NaN

    private val fill = Paint().apply { style = Paint.Style.FILL; color = 0x553C8C28.toInt(); isAntiAlias = true }
    private val stroke = Paint().apply { style = Paint.Style.STROKE; color = 0xFF2E7D32.toInt(); strokeWidth = 3f; isAntiAlias = true }
    private val privFill = Paint().apply { style = Paint.Style.FILL; color = 0x55E0A100.toInt(); isAntiAlias = true }
    private val privStroke = Paint().apply { style = Paint.Style.STROKE; color = 0xFFB8860B.toInt(); strokeWidth = 3f; isAntiAlias = true }
    private val accFill = Paint().apply { style = Paint.Style.FILL; color = 0x332962FF.toInt(); isAntiAlias = true }
    private val accStroke = Paint().apply { style = Paint.Style.STROKE; color = 0xAA2962FF.toInt(); strokeWidth = 2.5f; isAntiAlias = true }
    private val gps = Paint().apply { style = Paint.Style.FILL; color = 0xFF2962FF.toInt(); isAntiAlias = true }
    private val gpsRing = Paint().apply { style = Paint.Style.STROKE; color = 0xFFFFFFFF.toInt(); strokeWidth = 3f; isAntiAlias = true }
    private val p = Point()
    private val path = Path()

    override fun draw(c: Canvas, map: MapView, shadow: Boolean) {
        if (shadow) return
        val proj = map.projection
        val center = map.mapCenter
        val a = proj.toPixels(GeoPoint(center.latitude, center.longitude), null)
        val b = proj.toPixels(GeoPoint(center.latitude + 100.0 / 111_320.0, center.longitude), null)
        val ppm = abs(a.y - b.y) / 100.0
        loc?.let {
            val fp = if (it.public) fill else privFill
            val sp = if (it.public) stroke else privStroke
            if (it.polygon.isNotEmpty()) {            // real footprint
                path.rewind()
                for ((j, v) in it.polygon.withIndex()) {
                    proj.toPixels(GeoPoint(v[0], v[1]), p)
                    if (j == 0) path.moveTo(p.x.toFloat(), p.y.toFloat())
                    else path.lineTo(p.x.toFloat(), p.y.toFloat())
                }
                path.close()
                c.drawPath(path, fp)
                c.drawPath(path, sp)
            } else {
                val rPx = if (isCircleLocality(it.radius)) (it.radius * ppm).toFloat().coerceIn(POINT_DOT_PX, 200f)
                          else POINT_DOT_PX
                proj.toPixels(GeoPoint(it.lat, it.lon), p)
                c.drawCircle(p.x.toFloat(), p.y.toFloat(), rPx, fp)
                c.drawCircle(p.x.toFloat(), p.y.toFloat(), rPx, sp)
            }
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
    var picked: Locality? = null
    var fix: GeoPoint? = null
    var accuracyM: Float = Float.NaN

    private val fill = Paint().apply { style = Paint.Style.FILL; color = 0x553C8C28.toInt(); isAntiAlias = true }
    private val stroke = Paint().apply { style = Paint.Style.STROKE; color = 0xFF2E7D32.toInt(); strokeWidth = 2f; isAntiAlias = true }
    private val privFill = Paint().apply { style = Paint.Style.FILL; color = 0x55E0A100.toInt(); isAntiAlias = true }    // private = yellow
    private val privStroke = Paint().apply { style = Paint.Style.STROKE; color = 0xFFB8860B.toInt(); strokeWidth = 2f; isAntiAlias = true }
    private val selFill = Paint().apply { style = Paint.Style.FILL; color = 0xDD4CAF50.toInt(); isAntiAlias = true }
    private val selFillFaint = Paint().apply { style = Paint.Style.FILL; color = 0x224CAF50.toInt(); isAntiAlias = true }
    private val selStroke = Paint().apply { style = Paint.Style.STROKE; color = 0xFF1B5E20.toInt(); strokeWidth = 6f; isAntiAlias = true }
    // Private picks keep the same bold-highlight shape but in the yellow (privFill) hue.
    private val selFillPriv = Paint().apply { style = Paint.Style.FILL; color = 0xDDE0A100.toInt(); isAntiAlias = true }
    private val selFillFaintPriv = Paint().apply { style = Paint.Style.FILL; color = 0x22E0A100.toInt(); isAntiAlias = true }
    private val selStrokePriv = Paint().apply { style = Paint.Style.STROKE; color = 0xFF6B4F00.toInt(); strokeWidth = 6f; isAntiAlias = true }
    private val accFill = Paint().apply { style = Paint.Style.FILL; color = 0x222962FF.toInt(); isAntiAlias = true }
    private val accStroke = Paint().apply { style = Paint.Style.STROKE; color = 0x882962FF.toInt(); strokeWidth = 2f; isAntiAlias = true }
    private val gps = Paint().apply { style = Paint.Style.FILL; color = 0xFF2962FF.toInt(); isAntiAlias = true }
    private val gpsRing = Paint().apply { style = Paint.Style.STROKE; color = 0xFFFFFFFF.toInt(); strokeWidth = 3f; isAntiAlias = true }
    private val labelFill = Paint().apply { color = 0xFF18250F.toInt(); textSize = 38f; textAlign = Paint.Align.CENTER; isAntiAlias = true }
    private val labelHalo = Paint().apply { color = 0xF2FFFFFF.toInt(); textSize = 38f; textAlign = Paint.Align.CENTER; isAntiAlias = true; style = Paint.Style.STROKE; strokeWidth = 5f }
    private val p = Point()
    private val path = Path()

    /** Pixels per metre at the current zoom, from projecting a 100 m north step. */
    private fun pxPerMeter(map: MapView): Double {
        val c = map.mapCenter
        val a = map.projection.toPixels(GeoPoint(c.latitude, c.longitude), null)
        val b = map.projection.toPixels(GeoPoint(c.latitude + 100.0 / 111_320.0, c.longitude), null)
        return abs(a.y - b.y) / 100.0
    }

    private fun radiusPx(loc: Locality, ppm: Double): Float {
        if (!isCircleLocality(loc.radius)) return POINT_DOT_PX   // point locality: small fixed dot
        return (loc.radius * ppm).toFloat().coerceAtLeast(POINT_DOT_PX)  // circle: scales with zoom
    }

    /** Does the locality's geographic footprint overlap the visible map bounds? Uses the
     *  polygon's vertex extent (or the circle's radius), so a large shape whose centre is
     *  off-screen still draws while it covers the view. */
    private fun boundsVisible(loc: Locality, bb: BoundingBox): Boolean {
        val latMin: Double; val latMax: Double; val lonMin: Double; val lonMax: Double
        if (loc.polygon.isNotEmpty()) {
            var laMin = 90.0; var laMax = -90.0; var loMin = 180.0; var loMax = -180.0
            for (v in loc.polygon) {
                laMin = min(laMin, v[0]); laMax = max(laMax, v[0])
                loMin = min(loMin, v[1]); loMax = max(loMax, v[1])
            }
            latMin = laMin; latMax = laMax; lonMin = loMin; lonMax = loMax
        } else {
            val dLat = loc.radius / 111_320.0
            val dLon = loc.radius / (111_320.0 * cos(Math.toRadians(loc.lat)))
            latMin = loc.lat - dLat; latMax = loc.lat + dLat
            lonMin = loc.lon - dLon; lonMax = loc.lon + dLon
        }
        return !(latMax < bb.latSouth || latMin > bb.latNorth ||
                 lonMax < bb.lonWest || lonMin > bb.lonEast)
    }

    /** Draw a locality's real polygon, or its radius circle if it's a point locality. */
    private fun drawShape(c: Canvas, proj: Projection, loc: Locality,
                          cx: Float, cy: Float, rPx: Float, fp: Paint, sp: Paint) {
        if (loc.polygon.isNotEmpty()) {
            path.rewind()
            for ((j, v) in loc.polygon.withIndex()) {
                proj.toPixels(GeoPoint(v[0], v[1]), p)
                if (j == 0) path.moveTo(p.x.toFloat(), p.y.toFloat())
                else path.lineTo(p.x.toFloat(), p.y.toFloat())
            }
            path.close()
            c.drawPath(path, fp)
            c.drawPath(path, sp)
        } else {
            c.drawCircle(cx, cy, rPx, fp)
            c.drawCircle(cx, cy, rPx, sp)
        }
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
        val showLabels = map.zoomLevelDouble >= 15.5     // names only when zoomed in enough to read them
        for (loc in localities) {
            if (loc === picked) continue              // drawn highlighted on top, after the loop
            // Cull by the locality's geographic bounds, not just its centre: a big polygon
            // (or wide circle) can fill the view while its centre point sits off-screen.
            if (bb != null && !boundsVisible(loc, bb)) continue
            proj.toPixels(GeoPoint(loc.lat, loc.lon), p)
            val rPx = radiusPx(loc, ppm)
            val px = p.x.toFloat(); val py = p.y.toFloat()
            if (loc.public) drawShape(c, proj, loc, px, py, rPx, fill, stroke)
            else drawShape(c, proj, loc, px, py, rPx, privFill, privStroke)   // private -> yellow
            if (showLabels) drawLabel(c, loc.lokalitet, px, py)   // name at the locality point, wrapped
        }
        picked?.let { pl ->                           // selected: bold outline, drawn on top
            proj.toPixels(GeoPoint(pl.lat, pl.lon), p)
            val cx = p.x.toFloat(); val cy = p.y.toFloat()
            // Areas get a faint fill (so localities inside stay visible) + a bold outline;
            // small points get a solid fill so the dot stands out.
            val area = pl.polygon.isNotEmpty()
            val fp = if (pl.public) (if (area) selFillFaint else selFill)
                     else (if (area) selFillFaintPriv else selFillPriv)
            val sp = if (pl.public) selStroke else selStrokePriv
            drawShape(c, proj, pl, cx, cy, radiusPx(pl, ppm), fp, sp)
            if (showLabels) drawLabel(c, pl.lokalitet, cx, cy)
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
