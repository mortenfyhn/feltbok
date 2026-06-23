package io.github.mortenfyhn.feltbok

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.graphics.RectF
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.NumberPicker
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.Overlay
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max

/** osmdroid's [CopyrightOverlay] re-reads the tile source's (English) notice on every frame in
 *  draw(Canvas, MapView, Boolean), clobbering any setCopyrightNotice. Override that overload to draw
 *  our fixed Norwegian credit instead. */
private class NorwegianCopyrightOverlay(ctx: android.content.Context) : CopyrightOverlay(ctx) {
    init { setCopyrightNotice("© OpenStreetMap-bidragsytere") }
    override fun draw(c: Canvas, map: MapView, shadow: Boolean) {
        if (!shadow) draw(c, map.projection)
    }
}

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
            // Clamp zoom: 4 still shows all of Norway; 23 zooms in tight enough to place a 1 m
            // spot (tiles upscale past MAPNIK's 19, but the geometry stays exact). Disable map
            // wrapping so zooming out can't tile the world into a grid of Earths, and pin the
            // scrollable area to a single world so you can't pan off into the grey void around it.
            setMinZoomLevel(4.0)
            setMaxZoomLevel(23.0)
            isHorizontalMapRepetitionEnabled = false
            isVerticalMapRepetitionEnabled = false
            setScrollableAreaLimitDouble(BoundingBox(85.0, 180.0, -85.0, -180.0))
            overlays.add(NorwegianCopyrightOverlay(ctx))    // required by the OSM tile policy
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)  // use our own buttons
            controller.setZoom(if (vm.mapZoom >= 1.0) vm.mapZoom else 16.0)   // keep the last zoom
            val (lat, lon) = pickerCenter(
                focused = vm.isEditing || vm.fromCopy || vm.pickingCurrent,
                focus = vm.pickerFocus, fix = vm.fix, dLoc = vm.dLoc, nearest = vm.nearest(),
            )
            controller.setCenter(GeoPoint(lat, lon))
        }
    }
    val overlay = remember {
        LocalityOverlay { tapped = it; mapView.invalidate() }   // highlight, then go
            .also { mapView.overlays.add(it) }
    }
    // Refresh the overlay's plain-List copy whenever the locality set changes (async load
    // finishing, or a new spot placed) - not every frame. Copy out of the SnapshotStateList:
    // draw() iterates this every frame, and snapshot reads in that hot loop make zoom lag.
    LaunchedEffect(vm.localities.size) {
        overlay.localities = vm.localities.toList()
        mapView.invalidate()
    }
    // Flash the tapped locality highlighted, then return to the entry screen.
    LaunchedEffect(tapped) { tapped?.let { delay(300); vm.pickLocality(it) } }

    // Whether the map is already (about) centred on the GPS fix — drives the re-centre button's
    // enabled state. Recomputed when the map moves (listener) and when a new fix arrives.
    var centered by remember { mutableStateOf(false) }
    fun recomputeCentered() {
        val f = vm.fix
        centered = f != null && mapView.width > 0 && run {
            val pt = mapView.projection.toPixels(GeoPoint(f.lat, f.lon), null)
            hypot((pt.x - mapView.width / 2).toFloat(), (pt.y - mapView.height / 2).toFloat()) < 24f
        }
    }
    LaunchedEffect(vm.fix) { recomputeCentered() }

    DisposableEffect(Unit) {
        mapView.onResume()
        val listener = object : MapListener {
            override fun onScroll(e: ScrollEvent?): Boolean { recomputeCentered(); return false }
            override fun onZoom(e: ZoomEvent?): Boolean { recomputeCentered(); return false }
        }
        mapView.addMapListener(listener)
        onDispose {
            mapView.removeMapListener(listener)
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
            // One-handed zoom + re-centre: thumb-reachable buttons (the new-spot panel covers them).
            if (!newMode) Column(
                Modifier.align(Alignment.BottomEnd).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Re-centre on the current GPS fix (keeps the current zoom); only shown when there's
                // a fix and the map isn't already there.
                if (vm.fix != null && !centered) RecenterButton {
                    vm.fix?.let {
                        mapView.controller.stopPanning()   // kill any fling momentum, else it fights the animateTo
                        mapView.controller.animateTo(GeoPoint(it.lat, it.lon))
                    }
                }
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
                    Text(Country.adjustHint, color = cs.onSurfaceVariant, fontSize = 12.sp)
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

@Composable
private fun ZoomButton(label: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier.size(52.dp).clip(CircleShape).background(Color.White)
            .border(1.dp, cs.outline, CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = cs.primary, fontSize = 28.sp, fontWeight = FontWeight.Bold) }
}

/** Re-centre control: the same round white button as the zoom ones, but drawing the map's
 *  GPS location dot (blue dot + white ring) as its icon. */
@Composable
private fun RecenterButton(onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val dotColor = Color(MapPalette.Gps)
    Box(
        Modifier.size(52.dp).clip(CircleShape).background(Color.White)
            .border(1.dp, cs.outline, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(22.dp)) {
            val r = size.minDimension / 2f
            drawCircle(dotColor, radius = r)                                  // outer dot
            drawCircle(Color.White, radius = r * 0.62f, style = Stroke(r * 0.28f))  // white ring
            drawCircle(dotColor, radius = r * 0.34f)                          // inner dot
        }
    }
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

/** Where the picker map centres when it opens, as (lat, lon). [focused] is true when editing or
 *  copying an observation, or changing the current locality: then centre on the chosen [focus]
 *  locality so you adjust around it, not your current position. A new observation isn't focused, so
 *  it centres on the current GPS [fix] to show where you are now, falling back to the draft [dLoc],
 *  the [nearest] locality, then the country default. Copying must focus too: the copy carries the
 *  original's location but isn't "editing" it, so without [focused] it wrongly re-centred on GPS. */
internal fun pickerCenter(
    focused: Boolean,
    focus: Locality?,
    fix: GpsFix?,
    dLoc: Locality?,
    nearest: Locality?,
): Pair<Double, Double> {
    val f = if (focused) focus else null
    val lat = f?.lat ?: fix?.lat ?: dLoc?.lat ?: nearest?.lat ?: Country.mapCenterLat
    val lon = f?.lon ?: fix?.lon ?: dLoc?.lon ?: nearest?.lon ?: Country.mapCenterLon
    return lat to lon
}

/** A locality is decluttered - hidden when zoomed far out and its on-screen footprint is tiny.
 *  Shared by the draw cull and tap resolution, so you can only tap what's actually drawn (#126:
 *  otherwise a hidden point could steal a tap meant for a visible polygon). */
internal fun declutteredAtZoom(zoom: Double, spanPx: Float): Boolean =
    zoom < DECLUTTER_ZOOM && spanPx < DECLUTTER_MIN_SPAN_PX

/** Footprint (px half-extent) a locality needs before its name becomes a label *candidate*. This
 *  is only the candidacy gate, not the final show/hide: the greedy collision pass (#121) decides
 *  which candidates actually draw, so this is set low and geometry does the rest. Point localities
 *  have no real footprint, so they're treated as [POINT_LABEL_EXTENT_M] across. */
private const val LABEL_MIN_SPAN_PX = 14f
private const val POINT_LABEL_EXTENT_M = 60.0

/** Padding (px) added around each label's text box before testing overlap — the main
 *  "breathing room" knob for how tightly labels may sit next to each other (#121). */
private const val LABEL_PAD = 8f

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

/** How close (dp) a tap must land to a footprint-less point's dot to count as hitting it, and the
 *  reach of the near-miss fallback. In dp (scaled by display density at tap time) so the touch
 *  target is the same physical size on any screen; ~Material's minimum touch target as a radius. */
internal const val TAP_SLOP_DP = 24f

/** A locality whose on-screen footprint (bounding box) exceeds this fraction of the viewport along
 *  *either* axis is too big to tap: zoomed in tight it overflows the screen with no visible edge
 *  or centre to aim at, so a tap near a small locality at the rim of a huge one (Hønstadvatnet
 *  on the edge of Jonsvatnet) must never grab the giant. Zoom out until it fits to select it.
 *  Set above 1.0 so a polygon that *nearly* fits stays tappable: an irregular shape (a diagonal
 *  lake like "sjøen mellom Sistranda og Inntian") only half-fills its bounding box, so an empty
 *  bbox corner can poke off-screen while the shape itself is ~all visible. 1.2 tolerates that;
 *  raise it to be more forgiving, lower it toward 1.0 to require the bbox to fit outright.
 *  Symmetric to [declutteredAtZoom], which gates out footprints that are too small. */
internal const val MAX_TAP_FIT_FRACTION = 4.0f

/** Whether [loc]'s footprint is too big to tap at the current zoom. Compares the footprint's
 *  width and height to the viewport's width and height *separately*, so a tall-but-narrow polygon
 *  (or vice versa) that fits within the screen stays tappable instead of being judged against the
 *  short side only. A circle is symmetric (diameter on both axes); a point has no footprint. */
internal fun tooBigToTap(loc: Locality, ppm: Double, viewWpx: Int, viewHpx: Int): Boolean {
    val b = loc.polyBounds
    val wPx: Double
    val hPx: Double
    if (b == null) {
        val d = loc.radius * ppm * 2.0   // circle diameter; a point (radius 0) is never too big
        wPx = d; hPx = d
    } else {
        hPx = (b[1] - b[0]) * 111_320.0 * ppm
        wPx = (b[3] - b[2]) * 111_320.0 * cos(Math.toRadians((b[0] + b[1]) / 2)) * ppm
    }
    return wPx > viewWpx * MAX_TAP_FIT_FRACTION || hPx > viewHpx * MAX_TAP_FIT_FRACTION
}

/** Dev tool: when on (and on a dev build), the map draws each locality's tap hitbox on top —
 *  magenta ring = a point's finger-reach, blue outline = a footprint's exact tap shape, red dashed
 *  = a footprint excluded by [tooBigToTap]. A compile-time `const`, so when off it strips out like a
 *  C `#ifdef` (and [BuildConfig.DEV] keeps it out of release even if left on). Flip to true and
 *  rebuild the dev app (`just install`) to eyeball hitboxes while tuning [TAP_SLOP_DP] /
 *  [MAX_TAP_FIT_FRACTION]. */
private const val SHOW_HITBOXES = false

/** A locality a tap landed on, with its on-screen footprint span and the tap->centre distance (px). */
internal class TapCandidate(val loc: Locality, val spanPx: Float, val centreDistPx: Float)

/** Resolve a tap to a locality: the SMALLEST footprint among the hits wins, so a point or small
 *  polygon nested inside a big container (a wide circle like Uttian, or a polygon) stays selectable
 *  instead of always losing to the container (#126). Nearest centre only breaks ties between hits
 *  of equal size (e.g. two overlapping point dots). */
internal fun resolveTap(candidates: List<TapCandidate>): Locality? =
    candidates.minWithOrNull(compareBy({ it.spanPx }, { it.centreDistPx }))?.loc

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
    private val superFill = fillPaint(MapPalette.SuperFill)
    private val superStroke = strokePaint(MapPalette.SuperStroke, 3f)
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
            val fp = if (!it.public) privFill else if (it.isSuper) superFill else fill
            val sp = if (!it.public) privStroke else if (it.isSuper) superStroke else stroke
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
    private val superFill = fillPaint(MapPalette.SuperFill)
    private val superFillPale = fillPaint(MapPalette.SuperFillPale)
    private val superFillSolid = fillPaint(MapPalette.SuperFillSolid)
    private val superStroke = strokePaint(MapPalette.SuperStroke, 2f)
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

    // Hitbox overlay paints (drawn only when [SHOW_HITBOXES] is on): magenta ring = a point's
    // finger-reach hitbox; blue outline = a footprint (polygon/circle) whose exact shape is its tap
    // area; red dashed outline = a footprint the zoom-in size gate (tooBigToTap) has excluded.
    private val dbgSlop = strokePaint(0xFFFF00FF, 6f)
    private val dbgFootprint = strokePaint(0xFF0066FF, 4f)
    private val dbgBlocked = strokePaint(0xFFFF0000, 8f)
        .apply { pathEffect = DashPathEffect(floatArrayOf(24f, 16f), 0f) }

    // Dark, legible text over a halo tinted by type, so the outline tells you which kind of
    // locality the name belongs to: green for public, yellow for the user's own - matching the disks.
    private val labelFill = Paint().apply { color = MapPalette.LabelText.toInt(); textSize = 38f; textAlign = Paint.Align.CENTER; isAntiAlias = true }
    private val labelHalo = Paint().apply { color = MapPalette.LabelHaloGreen.toInt(); textSize = 38f; textAlign = Paint.Align.CENTER; isAntiAlias = true; style = Paint.Style.STROKE; strokeWidth = 5f }
    private val labelHaloSuper = Paint().apply { color = MapPalette.LabelHaloSuper.toInt(); textSize = 38f; textAlign = Paint.Align.CENTER; isAntiAlias = true; style = Paint.Style.STROKE; strokeWidth = 5f }
    private val labelHaloPriv = Paint().apply { color = MapPalette.LabelHaloYellow.toInt(); textSize = 38f; textAlign = Paint.Align.CENTER; isAntiAlias = true; style = Paint.Style.STROKE; strokeWidth = 5f }
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

    /** Footprint span used both for label candidacy (vs [LABEL_MIN_SPAN_PX]) and as the priority
     *  score in the collision pass: bigger footprint = higher priority. Point localities have no
     *  real size, so they get a nominal extent ([POINT_LABEL_EXTENT_M]). */
    private fun labelSpanPx(loc: Locality, ppm: Double): Float =
        if (loc.polygon.isEmpty() && loc.radius <= 0.0) (POINT_LABEL_EXTENT_M * ppm).toFloat()
        else screenSpanPx(loc, ppm)

    private val lineH = 32f

    /** A label queued for the greedy collision pass: its wrapped lines, the first baseline to draw
     *  from, the screen-space box used for overlap tests, and a priority (higher wins a contested spot). */
    private class LabelCandidate(
        val lines: List<String>, val cx: Float, val firstBaseline: Float,
        val box: RectF, val priority: Float, val public: Boolean, val isSuper: Boolean,
    )

    /** Build a [LabelCandidate] for [name] anchored at (cx,cy). A real footprint (polygon, or a
     *  circle drawn bigger than the dot) gets the name *centred on* the anchor so it reads as
     *  belonging to the shape (#135); a bare dot gets it just below [markerR] so the dot stays
     *  visible. Wrapped text is measured into a padded box for the overlap test. */
    private fun makeLabel(name: String, cx: Float, cy: Float, markerR: Float, centred: Boolean, public: Boolean, isSuper: Boolean, priority: Float): LabelCandidate {
        val lines = wrapLabel(name, 210f)
        var maxW = 0f
        for (l in lines) maxW = max(maxW, labelFill.measureText(l))
        val halfW = maxW / 2f + LABEL_PAD
        // First baseline: centred vertically on the anchor, or one line below the marker.
        val first = if (centred) cy - (lines.size - 1) * lineH / 2f + lineH * 0.32f
        else cy + markerR.coerceAtMost(40f) + lineH
        val top = first - lineH * 0.8f
        val bottom = first + (lines.size - 1) * lineH + lineH * 0.2f
        val box = RectF(cx - halfW, top - LABEL_PAD, cx + halfW, bottom + LABEL_PAD)
        return LabelCandidate(lines, cx, first, box, priority, public, isSuper)
    }

    /** Build the label for [loc], anchored at its [Locality.labelAnchor] (a polygon's interior
     *  pole, else its point). Centre the name on a real footprint - a polygon or a circle drawn
     *  bigger than the bare dot - so it sits on the shape; a dot keeps the name just below it. */
    private fun labelFor(loc: Locality, proj: Projection, ppm: Double, markerR: Float, priority: Float): LabelCandidate {
        val a = loc.labelAnchor
        proj.toPixels(GeoPoint(a[0], a[1]), p)
        val centred = loc.polygon.isNotEmpty() || (loc.radius > 0.0 && loc.radius * ppm >= POINT_DOT_PX)
        return makeLabel(loc.lokalitet, p.x.toFloat(), p.y.toFloat(), markerR, centred, loc.public, loc.isSuper, priority)
    }

    /** Draw a queued label, wrapped, from its first baseline down - dark text over a halo tinted
     *  by type (green public, yellow private). */
    private fun drawLabel(c: Canvas, cand: LabelCandidate) {
        val halo = when {
            !cand.public -> labelHaloPriv
            cand.isSuper -> labelHaloSuper
            else -> labelHalo
        }
        var ty = cand.firstBaseline
        for (line in cand.lines) {
            c.drawText(line, cand.cx, ty, halo)
            c.drawText(line, cand.cx, ty, labelFill)
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
        val slop = TAP_SLOP_DP * map.context.resources.displayMetrics.density
        // Zoomed far out, thousands of point/small localities collapse to a clutter of dots.
        // Hide the small ones until the view is zoomed in; big footprints (areas, wide
        // circles) still read as shapes and keep drawing. The active pick is culled-exempt.
        // Labels aren't drawn inline: we collect candidates here, then place them in one greedy
        // collision pass after all shapes so overlapping names get gated, not stacked (#121).
        val labels = ArrayList<LabelCandidate>()
        for (loc in localities) {
            if (loc === picked) continue              // drawn highlighted on top, after the loop
            // Cull by the locality's geographic bounds, not just its centre: a big polygon
            // (or wide circle) can fill the view while its centre point sits off-screen.
            if (bb != null && !boundsVisible(loc, bb)) continue
            val span = screenSpanPx(loc, ppm)
            if (declutteredAtZoom(map.zoomLevelDouble, span)) continue   // declutter the far-out view
            proj.toPixels(GeoPoint(loc.lat, loc.lon), p)
            val rPx = radiusPx(loc, ppm)
            val px = p.x.toFloat(); val py = p.y.toFloat()
            // Fade large localities (big circles/polygons) so they don't dominate the map.
            // New spots have public=false, so they draw yellow like the user's own.
            val big = span > LARGE_FOOTPRINT_PX
            val fp = when {
                !loc.public -> if (big) privFillPale else privFill
                loc.isSuper -> when {
                    big -> superFillPale
                    loc.polygon.isEmpty() && loc.radius <= 0.0 -> superFillSolid   // tiny dot: paint it solid
                    else -> superFill
                }
                else -> if (big) fillPale else fill
            }
            val sp = when {
                !loc.public -> privStroke
                loc.isSuper -> superStroke
                else -> stroke
            }
            drawShape(c, proj, loc, px, py, rPx, fp, sp)
            if (BuildConfig.DEV && SHOW_HITBOXES) {  // dev hitbox overlay (off by default)
                val fpPaint = if (tooBigToTap(loc, ppm, map.width, map.height)) dbgBlocked else dbgFootprint
                when {
                    loc.polygon.isEmpty() && loc.radius <= 0.0 -> c.drawCircle(px, py, slop, dbgSlop)
                    loc.polygon.isNotEmpty() -> { tracePolygon(path, loc.polygon, proj, p); c.drawPath(path, fpPaint) }
                    else -> c.drawCircle(px, py, rPx, fpPaint)
                }
            }
            // Candidate priority is footprint size, so big areas win a contested spot over tiny ones.
            val labelSpan = labelSpanPx(loc, ppm)
            if (labelSpan >= LABEL_MIN_SPAN_PX) labels += labelFor(loc, proj, ppm, rPx, labelSpan)
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
            // The pick always gets its name (top priority), so selecting a locality confirms which it is.
            labels += labelFor(pl, proj, ppm, radiusPx(pl, ppm), Float.MAX_VALUE)
        }
        // Greedy placement: highest priority first; draw a label only if its box clears every
        // already-placed one. Tens of candidates on a phone, so naïve O(n²) overlap testing is fine.
        labels.sortByDescending { it.priority }
        val placed = ArrayList<RectF>()
        for (cand in labels) {
            if (placed.any { RectF.intersects(it, cand.box) }) continue
            placed += cand.box
            drawLabel(c, cand)
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
        // Collect every locality the tap hits: a real footprint (polygon/circle) that contains it,
        // or a footprint-less point whose dot is within finger reach. The smallest footprint among
        // them wins (resolveTap), so a small polygon or a point nested inside a big container stays
        // selectable instead of always losing to it (#126). Tapping inside only one footprint (e.g.
        // a polygon's edge, or the part of A not overlapped by B) makes it the sole hit, so it wins
        // regardless of where its centre sits (#63). Decluttered localities are skipped: you can
        // only tap what's drawn, or a hidden point would steal a tap meant for a visible polygon.
        val zoom = map.zoomLevelDouble
        val slop = TAP_SLOP_DP * map.context.resources.displayMetrics.density
        val hits = ArrayList<TapCandidate>()
        for (loc in localities) {
            val span = screenSpanPx(loc, ppm)
            if (loc !== picked && (declutteredAtZoom(zoom, span) || tooBigToTap(loc, ppm, map.width, map.height))) continue
            proj.toPixels(GeoPoint(loc.lat, loc.lon), p)
            val d = hypot((p.x - e.x), (p.y - e.y))
            val hit = if (loc.polygon.isNotEmpty() || loc.radius > 0.0)
                localityContains(loc, tap.latitude, tap.longitude)   // real footprint: exact containment
            else d <= slop                                          // point: no footprint, so finger reach
            if (hit) hits.add(TapCandidate(loc, span, d))
        }
        var best = resolveTap(hits)
        if (best == null) {
            // Nothing hit: fall back to the nearest centre within a comfortable touch radius, so a
            // near-miss on a small dot or circle still selects instead of doing nothing. Skip
            // polygons: their exact traced shape is the hitbox (tested above), so a centre-distance
            // slop would let a tap register surprisingly far outside the drawn polygon.
            var bestD = Float.MAX_VALUE
            for (loc in localities) {
                if (loc.polygon.isNotEmpty()) continue
                val span = screenSpanPx(loc, ppm)
                if (loc !== picked && (declutteredAtZoom(zoom, span) || tooBigToTap(loc, ppm, map.width, map.height))) continue
                proj.toPixels(GeoPoint(loc.lat, loc.lon), p)
                val d = hypot((p.x - e.x), (p.y - e.y))
                if (d < bestD && d <= maxOf(radiusPx(loc, ppm), slop)) { bestD = d; best = loc }
            }
        }
        best?.let { onTap(it); return true }
        return false
    }
}
