package io.github.mortenfyhn.feltbok

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// ---- "my localities" sync (from the mobile API's /core/Sites/ByUser) ----

/** Map the mobile API's accumulated `{data:[...]}` rows to the user's own private localities.
 *  Coordinates are already WGS84; only `isPrivate` sites go to the mine-file (public ones are
 *  already in the bundled set). */
fun parseMySites(json: String): List<Locality> {
    val arr = parseJson(json).jsonObject.optArray("data") ?: return emptyList()
    val out = ArrayList<Locality>(arr.size)
    for (e in arr) {
        val o = e.jsonObject
        if (!o.optBoolean("isPrivate")) continue
        val lat = o.optDouble("latitude", Double.NaN); val lon = o.optDouble("longitude", Double.NaN)
        if (lat.isNaN() || lon.isNaN()) continue
        val poly = if (o.optBoolean("isPolygon")) lonLatRingToVertices(o["polygonCoordinates"])
        else emptyList()
        out.add(Locality(
            id = o.optLong("id").toString(),
            lokalitet = o.optString("name"),
            hovedlokalitet = "",
            kommune = o.optString("municipalityName"),
            lat = lat, lon = lon,
            observers = 0, radius = o.optDouble("accuracy", 0.0),
            polygon = poly, public = false, mine = true,
        ))
    }
    return out
}

/** The user's own (editable) localities from artportalen.se's GetEditableSitesGeoJson — a GeoJSON
 *  FeatureCollection of points + polygons in Web Mercator. The Swedish counterpart of [parseMySites]:
 *  reproject to WGS84 and mark each as the user's own (private). */
fun parseEditableSitesGeoJson(json: String): List<Locality> {
    val root = parseJson(json).jsonObject
    val out = ArrayList<Locality>()
    fun locOf(p: JsonObject, lat: Double, lon: Double, poly: List<DoubleArray>) = Locality(
        id = p.optLong("siteId").toString(),
        lokalitet = p.optString("siteName"),
        hovedlokalitet = "",
        kommune = if (p.optString("siteAreaDescription") == "Kommun") p.optString("siteAreaName") else "",
        lat = lat, lon = lon,
        observers = 0, radius = p.optDouble("accuracy", 0.0),
        polygon = poly, public = false, mine = true,
    )
    fun JsonObject.coords() = getValue("geometry").jsonObject.getValue("coordinates").jsonArray
    root.optObject("points")?.optArray("features")?.forEach { e ->
        val f = e.jsonObject
        val c = f.coords()
        out.add(locOf(f.getValue("properties").jsonObject, mercToLat(c[1].jsonPrimitive.double), mercToLon(c[0].jsonPrimitive.double), emptyList()))
    }
    root.optObject("polygons")?.optArray("features")?.forEach { e ->
        val f = e.jsonObject
        val verts = f.coords()[0].jsonArray.map { p ->
            val xy = p.jsonArray
            doubleArrayOf(mercToLat(xy[1].jsonPrimitive.double), mercToLon(xy[0].jsonPrimitive.double))
        }
        if (verts.isEmpty()) return@forEach
        out.add(locOf(f.getValue("properties").jsonObject, verts.sumOf { it[0] } / verts.size, verts.sumOf { it[1] } / verts.size, verts))
    }
    return out
}
