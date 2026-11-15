package io.github.mortenfyhn.feltbok

import kotlinx.serialization.json.JsonObject

// Name suggestions for a new locality from Kartverket's stedsnavn register, the source behind
// Norgeskart (#137).

// Types that make no sense as a hand-placed locality name: bus stops, and administrative areas
// ("Annen administrativ inndeling" is old county names like "Sør-Trøndelag fylke"; "Administrativ
// bydel" is official districts like "Nidarvoll/Leira", whose plain names also exist as "Bydel").
private val DENY_TYPES = setOf(
    "Busstopp", "Bussholdeplass",
    "Annen administrativ inndeling", "Administrativ bydel", "Kommune", "Fylke", "Land", "By",
)

// Street names. Their distance is to the street's line, so in a town many are close by and would fill
// the list (10 of the nearest 12 at Duedalen in Trondheim); only the nearest few are kept.
private const val STREET_TYPE = "Adressenavn"
private const val MAX_STREETS = 4

private const val MAX_SUGGESTIONS = 12

/** Search radius. The API neither sorts by distance nor returns more than one page of 500, so a
 *  wider radius in a town can page the nearest names away. 1000 m keeps city centres under 500. */
const val PLACE_NAME_RADIUS_M = 1000

data class PlaceSuggestion(val name: String, val type: String, val distanceM: Int)

/** One name from the register, before filtering and ranking. */
data class RawPlace(val name: String, val type: String, val status: String, val distanceM: Double)

/** The names in a stedsnavn `/punkt` response. Throws on malformed JSON. */
fun parsePlaceNames(json: String): List<RawPlace> {
    val names = (parseJson(json) as JsonObject).optArray("navn") ?: return emptyList()
    return names.mapNotNull { e ->
        val n = e as? JsonObject ?: return@mapNotNull null
        val sn = n.optArray("stedsnavn")?.firstOrNull() as? JsonObject ?: return@mapNotNull null
        RawPlace(
            name = sn.optString("skrivemåte"),
            type = n.optString("navneobjekttype"),
            status = sn.optString("navnestatus"),
            distanceM = n.optDouble("meterFraPunkt", Double.MAX_VALUE),
        )
    }
}

/** Nearest-first suggestions: drops unusable types and historical names, keeps the nearest of each
 *  repeated name and the nearest [MAX_STREETS] streets, and caps the list. The API's distance is to
 *  the name's shape (a street or river is a line), not to its label point, so a long feature ranks
 *  by its nearest part. Repeats are dropped before streets are counted. */
fun rankPlaceNames(raw: List<RawPlace>): List<PlaceSuggestion> {
    val seen = HashSet<String>()
    var streets = 0
    return raw.asSequence()
        .filter { it.type !in DENY_TYPES && it.status != "historisk" && it.name.isNotBlank() }
        .sortedBy { it.distanceM }
        .filter { seen.add(it.name) }
        .filter { it.type != STREET_TYPE || streets++ < MAX_STREETS }
        .map { PlaceSuggestion(it.name, it.type, it.distanceM.toInt()) }
        .take(MAX_SUGGESTIONS)
        .toList()
}
