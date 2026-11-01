package io.github.mortenfyhn.feltbok

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max

// The locality picker's platform-free rules, shared by the Android map (osmdroid, MapPicker.kt) and
// the iOS one (MapLibre): where it opens, what gets decluttered or labelled, and what a tap selects.
// Pixel values are Android pixels at osmdroid zoom levels; the iOS map converts to them.

// Radius is Artsobservasjoner's authoritative locality radius (accuracy in metres):
// 0 = a point locality -> a small fixed dot; > 0 = a real circle of that radius.
// Polygons draw their own shape and ignore radius.
internal const val POINT_DOT_PX = 15f

/** Below this zoom, localities whose on-screen footprint is smaller than
 *  [DECLUTTER_MIN_SPAN_PX] (px half-extent) are hidden, so a far-out view of many
 *  harvested kommuner isn't a wall of dots. Tune these two to taste. */
internal const val DECLUTTER_ZOOM = 14.0
internal const val DECLUTTER_MIN_SPAN_PX = 24f

/** Where the picker map centres when it opens, as (lat, lon). The [focus] locality - the draft's
 *  own, or the current one when changing that - always wins: you came here to adjust around the
 *  place the observation claims, so dropping you on your own position hides it (#180). Only with no
 *  focus at all does it fall back to the GPS [fix], the [nearest] locality, then the country
 *  default. This used to be gated on a "focused" flag listing the screens that count, which had to
 *  be widened for every new one (copying, the current-locality picker, batch edit) and still missed
 *  the plain new observation that inherited a far-away current locality. */
internal fun pickerCenter(
    focus: Locality?,
    fix: GpsFix?,
    nearest: Locality?,
): Pair<Double, Double> {
    val lat = focus?.lat ?: fix?.lat ?: nearest?.lat ?: Country.mapCenterLat
    val lon = focus?.lon ?: fix?.lon ?: nearest?.lon ?: Country.mapCenterLon
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
internal const val LABEL_MIN_SPAN_PX = 14f
internal const val POINT_LABEL_EXTENT_M = 60.0

/** Nominal extent for a *superlocality* point (#178). A super dot stands for a whole area, so
 *  treating it as a 60 m point made it lose its name to any ordinary polygon beside it - and the
 *  neighbour's label then read as the dot's name. Score it like the big locality it represents. */
internal const val SUPER_POINT_LABEL_EXTENT_M = 500.0

/** Padding (px) added around each label's text box before testing overlap — the main
 *  "breathing room" knob for how tightly labels may sit next to each other (#121). */
internal const val LABEL_PAD = 8f

/** Footprint (px half-extent) above which a locality is treated as "large": faded when
 *  unselected so it doesn't dominate, and drawn translucent when selected so the localities
 *  inside it stay visible. */
internal const val LARGE_FOOTPRINT_PX = 140f

/** Roughly how big the locality's real footprint is on screen (px half-extent): the polygon's
 *  extent, the circle's true radius, or 0 for a point locality. Drives fading big localities,
 *  culling tiny ones when zoomed far out, and the selected-pick fill choice. */
internal fun screenSpanPx(loc: Locality, ppm: Double): Float {
    val b = loc.polyBounds ?: return (loc.radius * ppm).toFloat()
    val mLat = (b[1] - b[0]) * 111_320.0
    val mLon = (b[3] - b[2]) * 111_320.0 * cos((b[0] + b[1]) / 2 * PI / 180)
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
        wPx = (b[3] - b[2]) * 111_320.0 * cos((b[0] + b[1]) / 2 * PI / 180) * ppm
    }
    return wPx > viewWpx * MAX_TAP_FIT_FRACTION || hPx > viewHpx * MAX_TAP_FIT_FRACTION
}

/** A locality a tap landed on, with its on-screen footprint span and the tap->centre distance (px). */
internal class TapCandidate(val loc: Locality, val spanPx: Float, val centreDistPx: Float)

/** Resolve a tap to a locality: the SMALLEST footprint among the hits wins, so a point or small
 *  polygon nested inside a big container (a wide circle like Uttian, or a polygon) stays selectable
 *  instead of always losing to the container (#126). Nearest centre only breaks ties between hits
 *  of equal size (e.g. two overlapping point dots). */
internal fun resolveTap(candidates: List<TapCandidate>): Locality? =
    candidates.minWithOrNull(compareBy({ it.spanPx }, { it.centreDistPx }))?.loc
