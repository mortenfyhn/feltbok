package com.appobs

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Point
import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
    var picked by remember { mutableStateOf(vm.dLoc) }

    val mapView = remember {
        Configuration.getInstance().apply {
            userAgentValue = ctx.packageName               // required by the OSM tile policy
            osmdroidBasePath = File(ctx.cacheDir, "osmdroid")
            osmdroidTileCache = File(ctx.cacheDir, "osmdroid/tiles")
        }
        MapView(ctx).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            val start = vm.dLoc ?: vm.nearest()
            controller.setZoom(15.0)
            controller.setCenter(GeoPoint(start?.lat ?: vm.fix?.lat ?: 63.7,
                                          start?.lon ?: vm.fix?.lon ?: 8.7))
        }
    }
    val overlay = remember {
        LocalityOverlay(vm.localities, radiusMeters = 50.0) { picked = it; mapView.invalidate() }
            .also { mapView.overlays.add(it) }
    }
    overlay.picked = picked
    overlay.fix = vm.fix?.let { GeoPoint(it.lat, it.lon) }

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
        AndroidView(factory = { mapView }, modifier = Modifier.weight(1f).fillMaxWidth()) { it.invalidate() }
        Row(
            Modifier.fillMaxWidth().background(cs.surface).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val p = picked
            if (p == null) {
                Text("Trykk på en lokalitet i kartet", color = cs.onSurfaceVariant,
                    modifier = Modifier.weight(1f))
            } else {
                Column(Modifier.weight(1f)) {
                    Text(p.lokalitet, fontWeight = FontWeight.SemiBold)
                    val sub = listOfNotNull(
                        p.context.ifBlank { null },
                        vm.distanceTo(p)?.let { formatDistance(it) },
                    ).joinToString(" · ")
                    if (sub.isNotBlank()) Text(sub, color = cs.onSurfaceVariant, fontSize = 12.sp)
                }
                Button(onClick = { vm.pickLocality(p) }) { Text("Velg") }
            }
        }
    }
}

/** Draws each locality as a green disk at its real-world radius and resolves taps to
 *  the nearest locality. Public localities are green; private ones will be yellow once
 *  we have the allmenn flag. */
private class LocalityOverlay(
    private val localities: List<Locality>,
    private val radiusMeters: Double,
    private val onTap: (Locality) -> Unit,
) : Overlay() {
    var picked: Locality? = null
    var fix: GeoPoint? = null

    private val fill = Paint().apply { style = Paint.Style.FILL; color = 0x553C8C28.toInt(); isAntiAlias = true }
    private val stroke = Paint().apply { style = Paint.Style.STROKE; color = 0xFF2E7D32.toInt(); strokeWidth = 2f; isAntiAlias = true }
    private val pickedFill = Paint().apply { style = Paint.Style.FILL; color = 0x993C8C28.toInt(); isAntiAlias = true }
    private val pickedStroke = Paint().apply { style = Paint.Style.STROKE; color = 0xFF1B5E20.toInt(); strokeWidth = 4f; isAntiAlias = true }
    private val gps = Paint().apply { style = Paint.Style.FILL; color = 0xFF2962FF.toInt(); isAntiAlias = true }
    private val gpsRing = Paint().apply { style = Paint.Style.STROKE; color = 0xFFFFFFFF.toInt(); strokeWidth = 3f; isAntiAlias = true }
    private val p = Point()

    /** Pixels per metre at the current zoom, from projecting a 100 m north step. */
    private fun pxPerMeter(map: MapView): Double {
        val c = map.mapCenter
        val a = map.projection.toPixels(GeoPoint(c.latitude, c.longitude), null)
        val b = map.projection.toPixels(GeoPoint(c.latitude + 100.0 / 111_320.0, c.longitude), null)
        return abs(a.y - b.y) / 100.0
    }

    override fun draw(c: Canvas, map: MapView, shadow: Boolean) {
        if (shadow) return
        val proj = map.projection
        val rPx = (radiusMeters * pxPerMeter(map)).toFloat().coerceIn(3f, 220f)
        val bb = map.boundingBox
        val w = map.width; val h = map.height
        for (loc in localities) {
            if (bb != null && (loc.lat > bb.latNorth || loc.lat < bb.latSouth ||
                    loc.lon < bb.lonWest || loc.lon > bb.lonEast)) continue
            proj.toPixels(GeoPoint(loc.lat, loc.lon), p)
            if (p.x < -rPx || p.x > w + rPx || p.y < -rPx || p.y > h + rPx) continue
            val isPick = loc === picked
            c.drawCircle(p.x.toFloat(), p.y.toFloat(), rPx, if (isPick) pickedFill else fill)
            c.drawCircle(p.x.toFloat(), p.y.toFloat(), rPx, if (isPick) pickedStroke else stroke)
        }
        fix?.let {
            proj.toPixels(it, p)
            c.drawCircle(p.x.toFloat(), p.y.toFloat(), 9f, gps)
            c.drawCircle(p.x.toFloat(), p.y.toFloat(), 9f, gpsRing)
        }
    }

    override fun onSingleTapConfirmed(e: MotionEvent, map: MapView): Boolean {
        val proj = map.projection
        val threshold = (radiusMeters * pxPerMeter(map)).toFloat().coerceAtLeast(70f)
        var best: Locality? = null
        var bestD = Float.MAX_VALUE
        for (loc in localities) {
            proj.toPixels(GeoPoint(loc.lat, loc.lon), p)
            val d = hypot((p.x - e.x), (p.y - e.y))
            if (d < bestD) { bestD = d; best = loc }
        }
        if (best != null && bestD <= threshold) { onTap(best); return true }
        return false
    }
}
