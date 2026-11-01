// iOS version of the locality picker (the Android one is androidMain/MapPicker.kt). The screen
// (header, zoom/re-centre buttons, new-spot panel) and the tap logic are the Android code, nearly
// line for line; what changed is the map: MapLibre draws the OSM tiles and the localities
// (MapLayers.kt) and handles pan/zoom, and a Compose Canvas on top draws the new-spot crosshair.
//
// Units: the Android overlay is tuned in physical pixels at osmdroid zoom levels on a ~2.6x phone
// (osmdroid tiles aren't DPI-scaled). [Proj] reproduces that: "android px" are dp × ANDROID_DENSITY,
// and the osmdroid-equivalent zoom is derived from MapLibre's, so every constant below (dot size,
// declutter span, label size) keeps its Android meaning and the map shows the same density.
package io.github.mortenfyhn.feltbok

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.CameraUpdate
import org.maplibre.compose.interaction.ClickResult
import org.maplibre.compose.interaction.MapInteractions
import org.maplibre.compose.map.CameraConstraints
import org.maplibre.compose.map.DefaultMapRuntime
import org.maplibre.compose.map.MapState
import org.maplibre.compose.map.MapUiOptions
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.resource.MapResourceProvider
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.Position
import platform.Foundation.NSBundle
import platform.Foundation.NSData
import platform.Foundation.dataWithContentsOfFile
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.tan

/** The Android phone density the overlay's pixel constants were tuned on (xxhdpi-ish). */
private const val ANDROID_DENSITY = 2.625

/** osmdroid zoom = MapLibre zoom + this: MapLibre zooms on 512-dp tiles, osmdroid on 256 physical px. */
private val OSM_ZOOM_OFFSET = 1.0 + log2(ANDROID_DENSITY)

/** The app's own tiles (osmdroid's MAPNIK). 128-dp tiles draw them near pixel-for-pixel on an iPhone,
 *  the way osmdroid shows them unscaled, instead of blurry at 256 dp. */
private val OsmStyle = BaseStyle.Json(
    """
    {"version":8,"glyphs":"app://glyphs/{fontstack}/{range}.pbf",
     "sources":{"osm":{"type":"raster","tileSize":128,"maxzoom":19,
      "tiles":["https://tile.openstreetmap.org/{z}/{x}/{y}.png"]}},
     "layers":[{"id":"bg","type":"background","paint":{"background-color":"#f2efe9"}},
               {"id":"osm","type":"raster","source":"osm"}]}
    """.trimIndent(),
)

/** Serves the label font from the app bundle (glyphs-<range>.pbf: Noto Sans, OFL, from OpenFreeMap's
 *  font server), so map labels also work offline. Ranges cover Latin, Latin Extended and
 *  punctuation - every character in the Norwegian locality names. Installed before the first map. */
@OptIn(ExperimentalForeignApi::class)
fun configureMapRuntime() {
    DefaultMapRuntime.configure {
        resourceProvider = MapResourceProvider(scheme = "app") { request ->
            val range = request.url.substringAfterLast('/').removeSuffix(".pbf")
            val path = NSBundle.mainBundle.pathForResource("glyphs-$range", "pbf")
                ?: error("no bundled glyphs for $range")
            val data = NSData.dataWithContentsOfFile(path) ?: error("can't read $path")
            data.bytes?.readBytes(data.length.toInt()) ?: ByteArray(0)
        }
    }
}

/** Web Mercator projection for the current MapLibre camera, in device pixels, plus the Android-unit
 *  conversions (see the file comment). Built fresh from the camera each frame. */
private class Proj(
    val centerLat: Double,
    val centerLon: Double,
    mapLibreZoom: Double,
    val widthPx: Float,
    val heightPx: Float,
    density: Float,
) {
    private val worldPx = 512.0 * 2.0.pow(mapLibreZoom) * density
    private val cx = mx(centerLon)
    private val cy = my(centerLat)

    /** Device pixels per android pixel. */
    val s = (density / ANDROID_DENSITY).toFloat()
    val zoom = mapLibreZoom + OSM_ZOOM_OFFSET

    /** Android pixels per metre at the centre - MapPicker.kt's pxPerMeter, same formula. */
    val ppm = 1.0 / (156543.03392 * cos(((centerLat) * PI / 180)) / 2.0.pow(zoom))
    val widthA = (widthPx / s).toInt()
    val heightA = (heightPx / s).toInt()

    private fun mx(lon: Double) = (lon + 180.0) / 360.0 * worldPx
    private fun my(lat: Double): Double {
        val r = ((lat) * PI / 180)
        return (1.0 - ln(tan(r) + 1.0 / cos(r)) / PI) / 2.0 * worldPx
    }

    fun toPixels(lat: Double, lon: Double) =
        Offset((mx(lon) - cx + widthPx / 2).toFloat(), (my(lat) - cy + heightPx / 2).toFloat())

    fun fromPixels(x: Float, y: Float): Pair<Double, Double> {
        val wx = x - widthPx / 2 + cx
        val wy = y - heightPx / 2 + cy
        val lon = wx / worldPx * 360.0 - 180.0
        val n = PI * (1.0 - 2.0 * wy / worldPx)
        val lat = atan(0.5 * (exp(n) - exp(-n))) * 180.0 / PI
        return lat to lon
    }

    /** [latS, lonW, latN, lonE] of the view. */
    fun bounds(): DoubleArray {
        val (n, w) = fromPixels(0f, 0f)
        val (s2, e) = fromPixels(widthPx, heightPx)
        return doubleArrayOf(s2, w, n, e)
    }
}

private fun projOf(state: MapState, wPx: Float, hPx: Float, density: Float): Proj? {
    if (wPx <= 0f) return null
    val cam = state.viewport?.cameraPosition ?: state.cameraPosition
    return Proj(cam.center.latitude, cam.center.longitude, cam.zoom, wPx, hPx, density)
}

/**
 * The locality picker, as a map. Localities are drawn as green disks at their real
 * radius (like the Artsobservasjoner site) over OpenStreetMap tiles. Tap a disk to pick it.
 */
@Composable
actual fun LocalityScreen(vm: MainViewModel) {
    val cs = MaterialTheme.colorScheme
    val density = LocalDensity.current.density
    var tapped by remember { mutableStateOf<Locality?>(null) }
    var newMode by remember { mutableStateOf(false) }      // placing a brand-new spot
    var newRadius by remember { mutableStateOf(100) }
    var newName by remember { mutableStateOf("") }
    var sheetH by remember { mutableStateOf(0) }           // bottom-panel height (px), to centre the crosshair
    var mapW by remember { mutableStateOf(0f) }
    var mapH by remember { mutableStateOf(0f) }

    val start = remember {
        val (lat, lon) = pickerCenter(focus = vm.pickerFocus, fix = vm.fix, nearest = vm.nearest())
        CameraPosition(center = Position(lon, lat), zoom = (if (vm.mapZoom >= 1.0) vm.mapZoom else 16.0) - OSM_ZOOM_OFFSET)
    }
    var layerData by remember { mutableStateOf(LayerData.Empty) }
    val state = rememberMapState(baseStyle = OsmStyle, initialCameraPosition = start) {
        LocalityLayers(layerData)
    }
    val overlay = remember { LocalityOverlay() }
    // Plain-List snapshot of the localities, refreshed only when the set changes (the draw loop
    // and the layer build iterate it, and snapshot-list reads there are slow) - as on Android.
    LaunchedEffect(vm.localities.size) { overlay.localities = vm.localities.toList() }
    // Flash the tapped locality highlighted, then return to the entry screen.
    LaunchedEffect(tapped) { tapped?.let { delay(300); vm.pickLocality(it) } }

    fun proj() = projOf(state, mapW, mapH, density)

    // Whether the map is already (about) centred on the GPS fix - drives the re-centre button.
    val centered by remember {
        derivedStateOf {
            val f = vm.fix
            val p = proj()
            f != null && p != null && run {
                val pt = p.toPixels(f.lat, f.lon)
                hypot(pt.x - p.widthPx / 2, pt.y - p.heightPx / 2) < 24f * density
            }
        }
    }

    // Once the map has settled for a second, look up public localities in view (#173).
    val scope = rememberCoroutineScope()
    val settle = remember { arrayOfNulls<Job>(1) }
    LaunchedEffect(state) {
        snapshotFlow { state.viewport?.cameraPosition }.collect {
            settle[0]?.cancel()
            settle[0] = scope.launch {
                delay(1000)
                val b = proj()?.bounds() ?: return@launch
                if (fitsSiteQuery(b[0], b[1], b[2], b[3])) vm.fetchLiveSites(b[0], b[1], b[2], b[3])
            }
        }
    }
    // Rebuild the GeoJSON when the view leaves the padded area it was built for, the
    // zoom moves enough to change decluttering, or the pick/fix/locality set changes.
    LaunchedEffect(state) {
        var built: DoubleArray? = null
        var builtZoom = 0.0
        var builtKey: Any? = null
        snapshotFlow { proj() to Triple(tapped ?: vm.pickerFocus, vm.fix, vm.localities.size) }
            .conflate()
            .collect { (p, key) ->
                if (p == null) return@collect
                val v = p.bounds()
                val b = built
                val inside = b != null && v[0] >= b[0] && v[1] >= b[1] && v[2] <= b[2] && v[3] <= b[3]
                if (inside && abs(p.zoom - builtZoom) < 0.15 && key == builtKey) return@collect
                val padLat = (v[2] - v[0]) * 0.75
                val padLon = (v[3] - v[1]) * 0.75
                val bb = doubleArrayOf(v[0] - padLat, v[1] - padLon, v[2] + padLat, v[3] + padLon)
                val locs = overlay.localities
                layerData = withContext(Dispatchers.Default) {
                    buildLayerData(locs, key.first, key.second, bb, p.zoom, p.ppm)
                }
                built = bb; builtZoom = p.zoom; builtKey = key
            }
    }
    DisposableEffect(Unit) {
        onDispose { vm.mapZoom = state.cameraPosition.zoom + OSM_ZOOM_OFFSET }   // keep the zoom for next time
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
            if (!newMode) TextButton(onClick = { proj()?.let { newRadius = fitRadius(it) }; newMode = true }) {
                Text(Strings.Picker.newButton, color = Color.White)
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().onSizeChanged { mapW = it.width.toFloat(); mapH = it.height.toFloat() }) {
            MaplibreMap(
                modifier = Modifier.fillMaxSize(),
                state = state,
                cameraConstraints = CameraConstraints {
                    // osmdroid's 4..23 clamp: 4 still shows all of Norway, 23 places a 1 m spot.
                    minZoom = 4.0 - OSM_ZOOM_OFFSET
                    maxZoom = 23.0 - OSM_ZOOM_OFFSET
                },
                interactions = MapInteractions {
                    camera {
                        rotate { enabled = false }
                        pitch { enabled = false }
                    }
                    callbacks {
                        click {
                            onEvent { e ->
                                val p = proj()
                                if (p == null || overlay.tapsBlocked) return@onEvent ClickResult.Pass
                                val hit = overlay.hitTest(p, e.screenOffset.x.value * density, e.screenOffset.y.value * density, density)
                                if (hit != null) { tapped = hit; ClickResult.Consume } else ClickResult.Pass
                            }
                        }
                    }
                },
                // Taps select instantly instead of waiting out a double-tap; pinch still zooms (as on Android).
                uiOptions = MapUiOptions { bindings { doubleTap { enabled = false } } },
                overlay = {},
            )
            overlay.picked = tapped ?: vm.pickerFocus
            if (newMode) {
                Canvas(Modifier.fillMaxSize()) {
                    val p = proj() ?: return@Canvas
                    with(overlay) { drawNewSpot(p, newRadius.toDouble(), sheetH.toFloat()) }
                }
            }
            // osmdroid's CopyrightOverlay: the OSM tile policy requires the credit.
            Text("© OpenStreetMap-bidragsytere", fontSize = 10.sp, color = Color(0xFF444444),
                modifier = Modifier.align(Alignment.BottomStart).background(Color(0x99FFFFFF)).padding(horizontal = 4.dp, vertical = 1.dp))
            // One-handed zoom + re-centre: thumb-reachable buttons (the new-spot panel covers them).
            if (!newMode) Column(
                Modifier.align(Alignment.BottomEnd).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (vm.fix != null && !centered) RecenterButton {
                    vm.fix?.let { f -> scope.launch { state.animateCamera(CameraUpdate(center = Position(f.lon, f.lat))) } }
                }
                ZoomButton("+") { scope.launch { state.animateCamera(CameraUpdate(zoom = state.cameraPosition.zoom + 1)) } }
                ZoomButton("−") { scope.launch { state.animateCamera(CameraUpdate(zoom = state.cameraPosition.zoom - 1)) } }
            }
            // New-spot panel OVERLAYS the map (so the map doesn't resize/jump); the overlay
            // draws the crosshair above it, at the centre of the visible map.
            if (newMode) Row(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(Color.White).padding(14.dp)
                    .onSizeChanged { sheetH = it.height },
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newName, onValueChange = { newName = it }, singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        label = { Text(Strings.Picker.nameLabel) },
                        placeholder = { Text(Strings.Picker.namePlaceholder) }, modifier = Modifier.fillMaxWidth(),
                    )
                    Text(Country.adjustHint, color = cs.onSurfaceVariant, fontSize = 12.sp)
                    Button(
                        onClick = {
                            val p = proj() ?: return@Button
                            val (lat, lon) = p.fromPixels(p.widthPx / 2, (p.heightPx - sheetH) / 2)
                            vm.createNewLocality(lat, lon, newRadius, newName)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(Strings.Picker.save) }
                }
                RadiusWheel(newRadius) { newRadius = it }
            }
        }
    }
}

// Radii Artsobservasjoner allows for a circle locality. No 0/"punkt": the paste-import
// rejects a 0 m Nøyaktighet ("må ha ... et positivt heltall"), so 1 m is the effective point.
private val RADII = listOf(1, 5, 10, 25, 50, 75, 100, 125, 150, 200, 250, 300, 400, 500, 750, 1000, 1500, 2000, 2500, 3000, 5000)

/** Largest preset radius whose circle comfortably fits the current map view, so a new spot's
 *  circle is actually visible at the current zoom instead of overflowing off-screen (#67). */
private fun fitRadius(p: Proj): Int {
    val maxR = minOf(p.widthA, p.heightA) * 0.25 / p.ppm   // radius ~ a quarter of the shorter side
    return RADII.lastOrNull { it <= maxR } ?: RADII.first()
}

/** Stand-in for Android's NumberPicker wheel: three rows, the middle one selected. The list's own
 *  first visible item is the selected one (contentPadding puts it in the middle slot), so a fling
 *  that snaps to an item boundary always lands a value in the box. */
@Composable
private fun RadiusWheel(value: Int, onChange: (Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val rowH = 40.dp
    val rowPx = with(LocalDensity.current) { rowH.toPx() }
    val list = rememberLazyListState(RADII.indexOf(value).coerceAtLeast(0))
    // The row nearest the middle slot, counting a half-scrolled row as the next one.
    val centred by remember {
        derivedStateOf {
            val i = list.firstVisibleItemIndex + if (list.firstVisibleItemScrollOffset > rowPx / 2) 1 else 0
            i.coerceIn(0, RADII.lastIndex)
        }
    }
    LaunchedEffect(centred) { onChange(RADII[centred]) }
    Box(Modifier.width(96.dp).height(rowH * 3)) {
        Box(Modifier.align(Alignment.Center).fillMaxWidth().height(rowH).border(1.dp, cs.outline))
        LazyColumn(
            state = list,
            flingBehavior = rememberSnapFlingBehavior(list),
            contentPadding = PaddingValues(vertical = rowH),
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(RADII) { i, r ->
                Box(Modifier.fillMaxWidth().height(rowH), contentAlignment = Alignment.Center) {
                    Text(Strings.Picker.meters(r), textAlign = TextAlign.Center,
                        fontWeight = if (i == centred) FontWeight.Bold else FontWeight.Normal)
                }
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
            drawCircle(dotColor, radius = r)
            drawCircle(Color.White, radius = r * 0.62f, style = Stroke(r * 0.28f))
            drawCircle(dotColor, radius = r * 0.34f)
        }
    }
}

/** The main-screen minimap is hidden on Android (SHOW_MINIMAP = false), so it isn't ported. */
// No minimap on iOS yet.
@Composable
actual fun LocalityPreview(vm: MainViewModel, modifier: Modifier) {}

private fun c(argb: Long) = Color(argb.toInt())

/** What MapLibre doesn't do for the picker: resolving a tap to a locality (Android's rules, in
 *  MapLogic.kt) and drawing the new-spot crosshair. */
private class LocalityOverlay {
    var localities: List<Locality> = emptyList()
    var picked: Locality? = null
    var tapsBlocked = false

    private fun radiusPx(loc: Locality, ppm: Double): Float {
        if (loc.radius <= 0.0) return POINT_DOT_PX
        return (loc.radius * ppm).toFloat().coerceAtLeast(POINT_DOT_PX)
    }

    /** The new spot's radius circle ([radiusM], 0 = none) and crosshair, centred in the map area
     *  above the [offsetPx]-high bottom panel. */
    fun DrawScope.drawNewSpot(p: Proj, radiusM: Double, offsetPx: Float) {
        val cx = p.widthPx / 2f
        val cy = (p.heightPx - offsetPx) / 2f
        val ctr = Offset(cx, cy)
        if (radiusM > 0.0) {
            val r = (radiusM * p.ppm).toFloat().coerceAtLeast(10f) * p.s
            drawCircle(c(MapPalette.YellowFill), r, ctr, style = Fill)
            drawCircle(c(MapPalette.YellowStroke), r, ctr,
                style = Stroke(6f * p.s, pathEffect = PathEffect.dashPathEffect(floatArrayOf(20f * p.s, 14f * p.s))))
        }
        val cross = c(MapPalette.YellowStroke)
        drawLine(cross, Offset(cx - 22f * p.s, cy), Offset(cx + 22f * p.s, cy), 4f * p.s)
        drawLine(cross, Offset(cx, cy - 22f * p.s), Offset(cx, cy + 22f * p.s), 4f * p.s)
    }

    /** MapPicker.kt's onSingleTapUp, unchanged apart from units: [x]/[y] are device pixels. */
    fun hitTest(p: Proj, x: Float, y: Float, density: Float): Locality? {
        val ppm = p.ppm
        val (tapLat, tapLon) = p.fromPixels(x, y)
        val zoom = p.zoom
        val slop = TAP_SLOP_DP * density
        val hits = ArrayList<TapCandidate>()
        for (loc in localities) {
            val span = screenSpanPx(loc, ppm)
            if (loc !== picked && (declutteredAtZoom(zoom, span) || tooBigToTap(loc, ppm, p.widthA, p.heightA))) continue
            val o = p.toPixels(loc.lat, loc.lon)
            val d = hypot(o.x - x, o.y - y)
            val hit = if (loc.polygon.isNotEmpty() || loc.radius > 0.0) localityContains(loc, tapLat, tapLon)
            else d <= slop
            if (hit) hits.add(TapCandidate(loc, span, d))
        }
        var best = resolveTap(hits)
        if (best == null) {
            var bestD = Float.MAX_VALUE
            for (loc in localities) {
                if (loc.polygon.isNotEmpty()) continue
                val span = screenSpanPx(loc, ppm)
                if (loc !== picked && (declutteredAtZoom(zoom, span) || tooBigToTap(loc, ppm, p.widthA, p.heightA))) continue
                val o = p.toPixels(loc.lat, loc.lon)
                val d = hypot(o.x - x, o.y - y)
                if (d < bestD && d <= maxOf(radiusPx(loc, ppm) * p.s, slop)) { bestD = d; best = loc }
            }
        }
        return best
    }
}
