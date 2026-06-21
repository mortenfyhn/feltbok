package io.github.mortenfyhn.feltbok

import java.util.Locale

/**
 * Sweden (Artportalen) build configuration — the Swedish counterpart of the Norway Country.
 * Values verified against the official Artportalen import template (docs/artportalen-template-sv-v4.17.xls)
 * and a live paste-import test (2026-06): Swedish headers, yyyy-MM-dd dates, coordinates required on
 * every row, and the Fågel age/sex/activity vocabularies. UI text stays Bokmål; only the *data* and
 * the *export format* are Swedish.
 */
object Country {
    // Locale for *display* date/time (weekday + month names in the UI); export dates are numeric.
    val displayLocale: Locale = Locale("sv", "SE")

    // ---- export (paste) format ----
    // Like Norway: a picked registry locality exports name-only (the import links it to the public
    // site), and a minted map-spot exports name + coordinates + radius (creates a new private
    // locality). For a minted spot's coords to read right, the account's coordinate format must be
    // WGS84 (geografisk) — see issue #149. Column order is positional with exportCols; the import
    // matches by header name, so Nord/Ost order doesn't matter as long as each sits above its value.
    val exportDateFmt = "yyyy-MM-dd"
    val exportTimeFmt = "HH:mm"
    val uncertainYes = "Ja"
    val exportCols = listOf(
        "Artnamn", "Antal", "Ålder-Stadium", "Kön", "Aktivitet", "Lokalnamn", "Nord", "Ost",
        "Noggrannhet", "Startdatum", "Starttid", "Slutdatum", "Sluttid",
        "Publik kommentar", "Privat kommentar", "Osäker artbestämning",
    )

    // ---- bird option vocabularies (Fåglar), exactly as the import expects them ----
    val ages = listOf(
        "ägg", "pulli", "adult",
        "1K", "1K+", "2K", "2K+", "2K-", "3K", "3K+", "3K-", "4K", "4K+", "4K-",
        "5K", "5K+", "5K-", "6K", "6K+", "6K-", "7K", "7K+", "7K-",
    )
    val sexes = listOf("Hane", "Hona", "Honfärgad", "I par")

    // Everyday non-breeding activities first (mirrors the Norwegian ordering).
    private val commonActivities = listOf(
        "Rastande", "Stationär", "Förbiflygande", "Födosökande", "Sträckande",
        "Spel/sång", "Lockläte, övriga läten", "Revir, ej häckning", "Permanent revir",
    )

    // The full Fåglar activity list, in the template order.
    private val allActivities = listOf(
        "Bo, ägg/ungar", "Bo, hörda ungar", "Misslyckad häckning", "Ruvande", "Äggskal",
        "Föda åt ungar", "Bär exkrementsäck", "Besöker bebott bo", "Pulli/nyligen flygga ungar",
        "Nyligen använt bo", "Avledningsbeteende", "Bobygge", "Ruvfläckar", "Upprörd, varnande",
        "Bobesök?", "Parning/parningsceremonier", "Permanent revir", "Par i lämplig häckbiotop",
        "Spel/sång", "Obs i häcktid, lämplig biotop", "Rastande", "Stationär", "Förbiflygande",
        "Födosökande", "Lockläte, övriga läten", "Övernattning", "Revir, ej häckning", "Ringmärktes",
        "Individmärkt", "Sträckförsök", "Sträckande", "Sträckande N", "Sträckande NO", "Sträckande O",
        "Sträckande SO", "Sträckande S", "Sträckande SV", "Sträckande V", "Sträckande NV",
        "Död, krockat med kraftledning", "Död, krockat med vindkraftverk", "Död, krockat med fönster",
        "Död, krockat med fyr", "Trafikdödad", "Död, krockat med flygplan", "Död, krockat med staket",
        "Dödad av elektricitet", "Drunknad i fiskenät", "Dödad av predator", "Död av sjukdom/svält",
        "Funnen död", "Färska spår", "Äldre spår", "Färsk spillning", "Äldre spillning", "Gammalt bo",
    )
    val activities = commonActivities + allActivities.filterNot { it in commonActivities }

    val accuracy = "100 m"

    // ---- geography / online services ----
    // Fallback map centre when there's no GPS fix or nearby locality (central-south Sweden).
    val mapCenterLat = 59.5
    val mapCenterLon = 15.0
    val importUrl = "https://www.artportalen.se/ImportSighting"

    // Private-locality sync is out of scope for the Sweden MVP (its mobile API is unverified), so
    // the footer entry point is hidden. sitesHost is kept only to satisfy the shared SyncScreen.
    val syncEnabled = false
    val sitesHost = "https://mobil.artportalen.se"
    val adjustHint = "Du kan justere lokaliteten seinere på artportalen.se"
}
