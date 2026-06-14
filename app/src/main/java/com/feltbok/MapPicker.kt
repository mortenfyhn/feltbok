package com.feltbok

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.NumberPicker
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.Overlay
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max

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
    var newMode by remember { mutableStateOf(false) }      // placing a brand-new spot
    var newRadius by remember { mutableStateOf(100) }
    var newName by remember { mutableStateOf("") }
    var sheetH by remember { mutableStateOf(0) }           // bottom-panel height (px), to centre the crosshair

    val mapView = remember {
        configureOsmdroid(ctx)
        MapView(ctx).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)  // use our own buttons
            // Keep the last zoom. When editing an observation or changing the current locality,
            // centre on that chosen locality so you adjust around it, not your current position.
            // For a new observation re-centre on the current GPS fix so it shows where you are now
            // (falling back to the picked/nearest locality, then a default).
            controller.setZoom(if (vm.mapZoom >= 1.0) vm.mapZoom else 16.0)
            val focus = if (vm.isEditing || vm.pickingCurrent) vm.pickerFocus else null
            val lat = focus?.lat ?: vm.fix?.lat ?: vm.dLoc?.lat ?: vm.nearest()?.lat ?: 63.7
            val lon = focus?.lon ?: vm.fix?.lon ?: vm.dLoc?.lon ?: vm.nearest()?.lon ?: 8.7
            controller.setCenter(GeoPoint(lat, lon))
        }
    }
    val overlay = remember {
        LocalityOverlay { tapped = it; mapView.invalidate() }   // highlight, then go
            .also { mapView.overlays.add(it) }
    }
    // Refresh the overlay's plain-List copy whenever the locality set changes (async load
    // finishing, or a new spot placed) or the private-locality toggle flips - not every frame.
    LaunchedEffect(vm.localities.size, vm.showPrivate) {
        // Always keep your brand-new spots on the map: "Vis private" hides the many synced privates
        // (#88), but a spot you just placed must stay selectable for the next observation (#99).
        overlay.localities = vm.localities.filter { vm.showPrivate || it.public || it.newLoc }
        mapView.invalidate()
    }
    // Flash the tapped locality highlighted, then return to the entry screen.
    LaunchedEffect(tapped) { tapped?.let { delay(300); vm.pickLocality(it) } }

    DisposableEffect(Unit) {
        mapView.onResume()
        onDispose {
            vm.mapZoom = mapView.zoomLevelDouble     // keep the zoom for next time
            mapView.onPause(); mapView.onDetach()
        }
    }

    overlay.tapsBlocked = newMode                          // in new-spot mode, pan instead of select

    // Own the system back so it matches the header's "tilbake": in new-spot mode just close
    // that panel, otherwise leave the picker. Shadows the app-level handler (#70).
    val back = { if (newMode) newMode = false else vm.leaveLocalityPicker() }
    BackHandler { back() }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            title = if (newMode) Strings.Picker.titleNew else Strings.Picker.titlePick,
            onCancel = back,
        ) {
            if (!newMode) TextButton(onClick = { newRadius = fitRadius(mapView); newMode = true }) { Text(Strings.Picker.newButton, color = Color.White) }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                factory = { mapView },
                modifier = Modifier.fillMaxSize().clipToBounds(),  // keep the map off the toolbar
                update = { m ->
                    // Only touch the overlay when something actually changed, so a GPS tick
                    // mid-gesture doesn't force a redraw (which flickers the view).
                    val sel = tapped ?: vm.pickerFocus  // highlight the just-tapped one, else the current pick
                    val newFix = vm.fix?.let { GeoPoint(it.lat, it.lon) }
                    val newAcc = vm.fix?.accuracyM?.toFloat() ?: Float.NaN
                    val nr = if (newMode) newRadius.toDouble() else -1.0
                    val off = if (newMode) sheetH.toFloat() else 0f
                    if (overlay.picked !== sel || overlay.fix != newFix ||
                        overlay.accuracyM.toRawBits() != newAcc.toRawBits() ||
                        overlay.newRadiusM != nr || overlay.newOffsetPx != off) {
                        overlay.picked = sel
                        overlay.fix = newFix
                        overlay.accuracyM = newAcc
                        overlay.newRadiusM = nr
                        overlay.newOffsetPx = off
                        m.invalidate()
                    }
                },
            )
            // Toggle private (non-public) localities off the map - they clutter it when reporting
            // birds (issue #88). Only offered when there are any to hide.
            if (!newMode && vm.localities.any { !it.public }) PrivateToggle(
                show = vm.showPrivate,
                onToggle = vm::toggleShowPrivate,
                modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
            )
            // One-handed zoom: thumb-reachable buttons (the new-spot panel covers them).
            if (!newMode) Column(
                Modifier.align(Alignment.BottomEnd).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ZoomButton("+") { mapView.controller.zoomIn() }
                ZoomButton("−") { mapView.controller.zoomOut() }
            }
            // New-spot panel OVERLAYS the map (so the map doesn't resize/jump); the overlay
            // draws the crosshair above it, at the centre of the visible map.
            // Wheel + form side by side so the white panel stays short and leaves the map visible.
            if (newMode) Row(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(Color.White).padding(14.dp)
                    .onSizeChanged { sheetH = it.height },
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = newName, onValueChange = { newName = it }, singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        label = { Text(Strings.Picker.nameLabel) },
                        placeholder = { Text(Strings.Picker.namePlaceholder) }, modifier = Modifier.fillMaxWidth(),
                    )
                    Text(Strings.Picker.adjustHint, color = cs.onSurfaceVariant, fontSize = 12.sp)
                    Button(
                        onClick = {
                            val gp = mapView.projection.fromPixels(mapView.width / 2, (mapView.height - sheetH) / 2)
                            vm.createNewLocality(gp.latitude, gp.longitude, newRadius, newName)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(Strings.Picker.save) }
                }
                AndroidView(factory = { c ->
                    NumberPicker(c).apply {
                        minValue = 0
                        maxValue = RADII.lastIndex
                        displayedValues = RADII.map { Strings.Picker.meters(it) }.toTypedArray()
                        wrapSelectorWheel = false
                        // Block the inner EditText so long-pressing a value can't pop the keyboard.
                        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                        value = RADII.indexOf(newRadius)
                        setOnValueChangedListener { _, _, i -> newRadius = RADII[i] }
                    }
                }, update = { it.value = RADII.indexOf(newRadius) })
            }
        }
    }
}

// Radii Artsobservasjoner allows for a circle locality. No 0/"punkt": the paste-import
// rejects a 0 m Nøyaktighet ("må ha ... et positivt heltall"), so 1 m is the effective point.
private val RADII = listOf(1, 5, 10, 25, 50, 75, 100, 125, 150, 200, 250, 300, 400, 500, 750, 1000, 1500, 2000, 2500, 3000, 5000)

/** Largest preset radius whose circle comfortably fits the current map view, so a new spot's
 *  circle is actually visible at the current zoom instead of overflowing off-screen (#67). */
private fun fitRadius(map: MapView): Int {
    val proj = map.projection
    val a = proj.toPixels(GeoPoint(map.mapCenter.latitude, map.mapCenter.longitude), null)
    val b = proj.toPixels(GeoPoint(map.mapCenter.latitude + 100.0 / 111_320.0, map.mapCenter.longitude), null)
    val ppm = abs(a.y - b.y) / 100.0
    val maxR = minOf(map.width, map.height) * 0.25 / ppm   // radius ~ a quarter of the shorter side
    return RADII.lastOrNull { it <= maxR } ?: RADII.first()
}

/** Show/hide the private localities (issue #88): a checkbox sitting straight on the map, its label
 *  white-haloed so it stays legible over any tiles. Checked = the user's private spots are shown. */
@Composable
private fun PrivateToggle(show: Boolean, onToggle: () -> Unit, modifier: Modifier) {
    Row(
        modifier.clickable(onClick = onToggle).padding(8.dp),   // padding enlarges the tap target
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.scale(1.2f), contentAlignment = Alignment.Center) {
            // Fill white right up under the checkbox outline so no map shows through the ring.
            if (!show) Box(Modifier.size(18.dp).background(Color.White, RoundedCornerShape(2.dp)))
            Checkbox(checked = show, onCheckedChange = null)
        }
        val label = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Box(Modifier.padding(start = 3.dp)) {       // clear the visually-scaled checkbox
            // Halo for contrast; round join/cap so sharp corners (e.g. bottom of the "V")
            // don't grow jagged miter spikes.
            Text(Strings.Picker.privateToggle, color = Color.White,
                style = label.copy(drawStyle = Stroke(width = 6f, join = StrokeJoin.Round, cap = StrokeCap.Round)))
            Text(Strings.Picker.privateToggle, color = Color.Black, style = label)
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
// Radius is Artsobservasjoner's authoritative locality radius (accuracy in metres):
// 0 = a point locality -> a small fixed dot; > 0 = a real circle of that radius.
// Polygons draw their own shape and ignore radius.
private const val POINT_DOT_PX = 15f

/** Below this zoom, localities whose on-screen footprint is smaller than
 *  [DECLUTTER_MIN_SPAN_PX] (px half-extent) are hidden, so a far-out view of many
 *  harvested kommuner isn't a wall of dots. Tune these two to taste. */
private const val DECLUTTER_ZOOM = 14.0
private const val DECLUTTER_MIN_SPAN_PX = 24f

/** A locality's name appears once its on-screen footprint reaches this px half-extent, so a
 *  huge area ("Sjøområdene utenfor Østmarkneset") reveals its name at a low zoom while a cluster
 *  of small private spots (Festningsparken) only labels up once you zoom right in. Point
 *  localities have no real footprint, so they're treated as [POINT_LABEL_EXTENT_M] across. */
private const val LABEL_MIN_SPAN_PX = 34f
private const val POINT_LABEL_EXTENT_M = 60.0

/** Footprint (px half-extent) above which a locality is treated as "large": faded when
 *  unselected so it doesn't dominate, and drawn translucent when selected so the localities
 *  inside it stay visible. */
private const val LARGE_FOOTPRINT_PX = 140f

/** Roughly how big the locality's real footprint is on screen (px half-extent): the polygon's
 *  extent, the circle's true radius, or 0 for a point locality. Drives fading big localities,
 *  culling tiny ones when zoomed far out, and the selected-pick fill choice. */
internal fun screenSpanPx(loc: Locality, ppm: Double): Float {
    val b = loc.polyBounds ?: return (loc.radius * ppm).toFloat()
    val mLat = (b[1] - b[0]) * 111_320.0
    val mLon = (b[3] - b[2]) * 111_320.0 * cos(Math.toRadians((b[0] + b[1]) / 2))
    return (max(mLat, mLon) / 2.0 * ppm).toFloat()
}

/** A selected locality is drawn with a translucent fill when its footprint is large — a polygon
 *  area OR a wide circle like Uttian — so the localities sitting inside stay visible and tappable;
 *  only small dots get a solid fill so they stand out. The wide-circle case is the regression to
 *  guard: a circle is radius-based, not a polygon, so a polygon-only test paints over its insides. */
internal fun selectedFillIsFaint(loc: Locality, ppm: Double): Boolean =
    loc.polygon.isNotEmpty() || screenSpanPx(loc, ppm) > LARGE_FOOTPRINT_PX

/** Antialiased fill/stroke Paints from an ARGB literal, to cut the overlays' Paint boilerplate. */
private fun fillPaint(argb: Long) =
    Paint().apply { style = Paint.Style.FILL; color = argb.toInt(); isAntiAlias = true }
private fun strokePaint(argb: Long, width: Float) =
    Paint().apply { style = Paint.Style.STROKE; color = argb.toInt(); strokeWidth = width; isAntiAlias = true }

/** Trace [polygon] ([lat,lon] vertices) into [path] in screen pixels, reusing [p] as scratch. */
private fun tracePolygon(path: Path, polygon: List<DoubleArray>, proj: Projection, p: Point) {
    path.rewind()
    for ((j, v) in polygon.withIndex()) {
        proj.toPixels(GeoPoint(v[0], v[1]), p)
        if (j == 0) path.moveTo(p.x.toFloat(), p.y.toFloat())
        else path.lineTo(p.x.toFloat(), p.y.toFloat())
    }
    path.close()
}

private class PreviewOverlay : Overlay() {
    var loc: Locality? = null
    var fix: GeoPoint? = null
    var accuracyM: Float = Float.NaN

    private val fill = fillPaint(MapPalette.GreenFill)
    private val stroke = strokePaint(MapPalette.GreenStroke, 3f)
    private val privFill = fillPaint(MapPalette.YellowFill)
    private val privStroke = strokePaint(MapPalette.YellowStroke, 3f)
    private val accFill = fillPaint(MapPalette.GpsAccFill)
    private val accStroke = strokePaint(MapPalette.GpsAccStroke, 2.5f)
    private val gps = fillPaint(MapPalette.Gps)
    private val gpsRing = strokePaint(MapPalette.GpsRing, 3f)
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
                tracePolygon(path, it.polygon, proj, p)
                c.drawPath(path, fp)
                c.drawPath(path, sp)
            } else {
                val rPx = if (it.radius > 0.0) (it.radius * ppm).toFloat().coerceIn(POINT_DOT_PX, 200f)
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
    private val onTap: (Locality) -> Unit,
) : Overlay() {
    // A plain-List snapshot, refreshed from the composable only when the data changes - iterating
    // the live SnapshotStateList every frame got expensive once the table grew past ~10k localities.
    var localities: List<Locality> = emptyList()
    var picked: Locality? = null
    var fix: GeoPoint? = null
    var accuracyM: Float = Float.NaN
    var newRadiusM: Double = -1.0    // >= 0: drawing a new-spot crosshair + radius at the visible centre
    var newOffsetPx: Float = 0f      // bottom-panel height: shifts the crosshair up to the visible centre
    var tapsBlocked = false          // ignore taps (so the map pans) while placing a new spot

    // New-spot marker: the own-locality yellow, but with a thick dashed outline + crosshair so it
    // reads as "being placed, not saved yet" rather than an already-created locality (#68).
    private val newFill = fillPaint(MapPalette.YellowFill)
    private val newStroke = strokePaint(MapPalette.YellowStroke, 6f)
        .apply { pathEffect = DashPathEffect(floatArrayOf(20f, 14f), 0f) }
    private val newCross = strokePaint(MapPalette.YellowStroke, 4f)
    private val fill = fillPaint(MapPalette.GreenFill)
    private val fillPale = fillPaint(MapPalette.GreenFillPale)
    private val stroke = strokePaint(MapPalette.GreenStroke, 2f)
    private val privFill = fillPaint(MapPalette.YellowFill)
    private val privFillPale = fillPaint(MapPalette.YellowFillPale)
    private val privStroke = strokePaint(MapPalette.YellowStroke, 2f)
    private val selFill = fillPaint(MapPalette.SelFill)
    private val selFillFaint = fillPaint(MapPalette.SelFillFaint)
    private val selStroke = strokePaint(MapPalette.SelStroke, 6f)

    // Private picks keep the same bold-highlight shape but in the yellow hue.
    private val selFillPriv = fillPaint(MapPalette.SelFillPriv)
    private val selFillFaintPriv = fillPaint(MapPalette.SelFillFaintPriv)
    private val selStrokePriv = strokePaint(MapPalette.SelStrokePriv, 6f)
    private val accFill = fillPaint(MapPalette.GpsAccFillFaint)
    private val accStroke = strokePaint(MapPalette.GpsAccStrokeFaint, 2f)
    private val gps = fillPaint(MapPalette.Gps)
    private val gpsRing = strokePaint(MapPalette.GpsRing, 3f)
    private val labelFill = Paint().apply { color = MapPalette.LabelText.toInt(); textSize = 38f; textAlign = Paint.Align.CENTER; isAntiAlias = true }
    private val labelHalo = Paint().apply { color = MapPalette.LabelHalo.toInt(); textSize = 38f; textAlign = Paint.Align.CENTER; isAntiAlias = true; style = Paint.Style.STROKE; strokeWidth = 5f }
    private val p = Point()
    private val path = Path()

    /** Pixels per metre at the current zoom, from projecting a 100 m north step. */
    private fun pxPerMeter(map: MapView): Double {
        // Derive from zoom + latitude (Web Mercator) instead of projecting two points each
        // frame - the projected version jitters mid-pan, making big circles (Uttian) flicker.
        val mpp = 156543.03392 * cos(Math.toRadians(map.mapCenter.latitude)) /
            Math.pow(2.0, map.zoomLevelDouble)
        return 1.0 / mpp
    }

    private fun radiusPx(loc: Locality, ppm: Double): Float {
        if (loc.radius <= 0.0) return POINT_DOT_PX              // point locality: small fixed dot
        return (loc.radius * ppm).toFloat().coerceAtLeast(POINT_DOT_PX)  // circle of the real radius
    }

    /** Does the locality's geographic footprint overlap the visible map bounds? Uses the
     *  polygon's vertex extent (or the circle's radius), so a large shape whose centre is
     *  off-screen still draws while it covers the view. */
    private fun boundsVisible(loc: Locality, bb: BoundingBox): Boolean {
        val b = loc.cullBounds   // [latMin, latMax, lonMin, lonMax], precomputed once
        return !(b[1] < bb.latSouth || b[0] > bb.latNorth ||
            b[3] < bb.lonWest || b[2] > bb.lonEast)
    }

    /** Draw a locality's real polygon, or its radius circle if it's a point locality. */
    private fun drawShape(c: Canvas, proj: Projection, loc: Locality,
        cx: Float, cy: Float, rPx: Float, fp: Paint, sp: Paint) {
        if (loc.polygon.isNotEmpty()) {
            tracePolygon(path, loc.polygon, proj, p)
            c.drawPath(path, fp)
            c.drawPath(path, sp)
        } else {
            c.drawCircle(cx, cy, rPx, fp)
            c.drawCircle(cx, cy, rPx, sp)
        }
    }

    /** Reveal a locality's name once its footprint is big enough on screen, so huge localities
     *  show their names at a low zoom and tiny ones only when you zoom right in. Point localities
     *  have no real size, so they get a nominal extent and appear only when zoomed in close. */
    private fun labelVisible(loc: Locality, ppm: Double): Boolean {
        val spanPx = if (loc.polygon.isEmpty() && loc.radius <= 0.0) (POINT_LABEL_EXTENT_M * ppm).toFloat()
        else screenSpanPx(loc, ppm)
        return spanPx >= LABEL_MIN_SPAN_PX
    }

    private val lineH = 32f

    /** Draw [name] below the marker (which has pixel radius [markerR]), wrapped so it
     *  doesn't get too wide. Below, not centred, so it never hides a small dot. */
    private fun drawLabel(c: Canvas, name: String, cx: Float, cy: Float, markerR: Float) {
        val lines = wrapLabel(name, 210f)
        var ty = cy + markerR.coerceAtMost(40f) + lineH    // first baseline clears the marker
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
        // Zoomed far out, thousands of point/small localities collapse to a clutter of dots.
        // Hide the small ones until the view is zoomed in; big footprints (areas, wide
        // circles) still read as shapes and keep drawing. The active pick is culled-exempt.
        val showSmall = map.zoomLevelDouble >= DECLUTTER_ZOOM
        for (loc in localities) {
            if (loc === picked) continue              // drawn highlighted on top, after the loop
            // Cull by the locality's geographic bounds, not just its centre: a big polygon
            // (or wide circle) can fill the view while its centre point sits off-screen.
            if (bb != null && !boundsVisible(loc, bb)) continue
            val span = screenSpanPx(loc, ppm)
            if (!showSmall && span < DECLUTTER_MIN_SPAN_PX) continue   // declutter the far-out view
            proj.toPixels(GeoPoint(loc.lat, loc.lon), p)
            val rPx = radiusPx(loc, ppm)
            val px = p.x.toFloat(); val py = p.y.toFloat()
            // Fade large localities (big circles/polygons) so they don't dominate the map.
            // New spots have public=false, so they draw yellow like the user's own.
            val big = span > LARGE_FOOTPRINT_PX
            val fp = if (loc.public) (if (big) fillPale else fill) else (if (big) privFillPale else privFill)
            drawShape(c, proj, loc, px, py, rPx, fp, if (loc.public) stroke else privStroke)
            // Reveal the name by footprint size, not a flat zoom: big areas label early, tiny spots late.
            if (labelVisible(loc, ppm)) drawLabel(c, loc.lokalitet, px, py, rPx)
        }
        picked?.let { pl ->                           // selected: bold outline, drawn on top
            proj.toPixels(GeoPoint(pl.lat, pl.lon), p)
            val cx = p.x.toFloat(); val cy = p.y.toFloat()
            // Any large pick — a polygon OR a wide circle like Uttian — gets a faint fill so the
            // localities sitting inside it stay visible and tappable; only small dots get a solid
            // fill so they stand out.
            val faint = selectedFillIsFaint(pl, ppm)
            val fp = if (pl.public) (if (faint) selFillFaint else selFill)
            else (if (faint) selFillFaintPriv else selFillPriv)
            val sp = if (pl.public) selStroke else selStrokePriv
            drawShape(c, proj, pl, cx, cy, radiusPx(pl, ppm), fp, sp)
            if (labelVisible(pl, ppm)) drawLabel(c, pl.lokalitet, cx, cy, radiusPx(pl, ppm))
        }
        fix?.let {
            proj.toPixels(it, p)
            val px = p.x.toFloat(); val py = p.y.toFloat()
            if (!accuracyM.isNaN() && accuracyM > 0f) {
                val rAcc = (accuracyM * ppm).toFloat().coerceIn(8f, 600f)
                c.drawCircle(px, py, rAcc, accFill)
                c.drawCircle(px, py, rAcc, accStroke)
            }
            c.drawCircle(px, py, 18f, gps)            // big enough to spot at a glance (#119)
            c.drawCircle(px, py, 18f, gpsRing)
        }
        if (newRadiusM >= 0.0) {                      // placing a new spot: crosshair + radius
            val cx = map.width / 2f                    // fixed screen position = centre of the
            val cy = (map.height - newOffsetPx) / 2f   // map area visible above the bottom panel
            if (newRadiusM > 0.0) {                    // a point locality is just the crosshair, no circle
                val r = (newRadiusM * ppm).toFloat().coerceAtLeast(10f)
                c.drawCircle(cx, cy, r, newFill)
                c.drawCircle(cx, cy, r, newStroke)
            }
            c.drawLine(cx - 22f, cy, cx + 22f, cy, newCross)
            c.drawLine(cx, cy - 22f, cx, cy + 22f, newCross)
        }
    }

    // onSingleTapUp (not ...Confirmed) so selection is instant instead of waiting out
    // the double-tap timeout. Double-tap-to-zoom is sacrificed; pinch still zooms.
    override fun onSingleTapUp(e: MotionEvent, map: MapView): Boolean {
        if (tapsBlocked) return false                 // placing a new spot: let taps pan, not select
        val proj = map.projection
        val ppm = pxPerMeter(map)
        val tap = proj.fromPixels(e.x.toInt(), e.y.toInt())
        var best: Locality? = null
        var bestD = Float.MAX_VALUE
        // Prefer a locality whose real footprint actually contains the tap, so tapping inside a
        // polygon near its edge keeps that locality even when a neighbour's centre is closer (#63).
        // When several footprints contain the tap (overlapping localities) the nearest centre wins -
        // the tiebreak that already worked well for overlaps.
        for (loc in localities) {
            if (!localityContains(loc, tap.latitude, tap.longitude)) continue
            proj.toPixels(GeoPoint(loc.lat, loc.lon), p)
            val d = hypot((p.x - e.x), (p.y - e.y))
            if (d < bestD) { bestD = d; best = loc }
        }
        if (best == null) {
            // Nothing contains the tap: fall back to the nearest centre within a comfortable touch
            // radius, so point localities and small dots stay tappable.
            for (loc in localities) {
                proj.toPixels(GeoPoint(loc.lat, loc.lon), p)
                val d = hypot((p.x - e.x), (p.y - e.y))
                if (d < bestD && d <= maxOf(radiusPx(loc, ppm), 44f)) { bestD = d; best = loc }
            }
        }
        best?.let { onTap(it); return true }
        return false
    }
}
