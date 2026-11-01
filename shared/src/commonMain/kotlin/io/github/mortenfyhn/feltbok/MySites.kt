package io.github.mortenfyhn.feltbok

import kotlinx.serialization.json.jsonObject

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
