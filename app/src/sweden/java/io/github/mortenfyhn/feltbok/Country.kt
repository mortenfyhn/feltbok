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
    val displayLocale: Locale = Locale.forLanguageTag("sv-SE")

    // ---- species-name languages (#155) ----
    // The export always writes the Swedish name (what Artportalen accepts on import), regardless of
    // which language the user chooses to *see*. The defaults reproduce the old hard-wired behaviour
    // (Swedish, with Norwegian underneath); the user can change them in Settings.
    val exportLang = Lang.SVENSK
    val defaultPrimary = Lang.SVENSK
    val defaultSecondary = Lang.NORSK

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

    // Header for the repeated co-observer columns (#128). The Artportalen v4.17 Fåglar template ships
    // 10 "Med-observatör" columns (note the hyphen); paste-import matches by header name, so exportTsv
    // appends as many identical columns as the batch needs.
    val coObserverCol = "Med-observatör"

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
    // Locality map style: Artportalen's localities are almost all overlapping circles (5 polygons in
    // ~29k), so filled disks turn dense areas into a solid blob. Draw them hollow (ring + centre dot,
    // fill on select), supers as deep-green triangles — the way artportalen.se draws them (#155).
    val hollowLocalities = true

    // Fallback map centre when there's no GPS fix or nearby locality (central-south Sweden).
    val mapCenterLat = 59.5
    val mapCenterLon = 15.0
    val importUrl = "https://www.artportalen.se/ImportSighting"
    val adjustHint = "Du kan justere lokaliteten seinere på artportalen.se"

    // ---- private-locality sync: same WebView pattern as Norway, but against artportalen.se's
    // GetEditableSitesGeoJson (the user's own editable sites), with the same .ASPXAUTH cookie the
    // locality harvest uses. userId comes from local.properties (BuildConfig), not committed. ----
    val syncEnabled = true
    val sitesHost = "https://artportalen.se"
    val syncProbeUrl = "$sitesHost/Site/Site"
    val syncLoginUrl = "$sitesHost/LogOn?ReturnUrl=%2FSite%2FSite"
    val loginPathMarker = "/LogOn"
    val userId = BuildConfig.SE_USER_ID
    val mySitesParse: (String) -> List<Locality> = ::parseEditableSitesGeoJson

    // One POST returns all the user's editable sites within a national bbox (GeoJSON, Web Mercator).
    val mySitesFetchJs = """
        (async () => {
          try {
            const body = 'zoomLevel=5&bbox=1100000,7300000,2700000,10700000&userId=$userId'
                       + '&projectIds=undefined&showOnlyProjects=false';
            const r = await fetch('/Map/GetEditableSitesGeoJson', {
              method: 'POST',
              headers: { 'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8', 'X-Requested-With': 'XMLHttpRequest' },
              body: body, credentials: 'same-origin'
            });
            if (r.redirected || r.url.indexOf('/LogOn') >= 0) { FeltbokSync.deliver('{"error":"auth"}'); return; }
            if (!r.ok) { FeltbokSync.deliver('{"error":"http"}'); return; }
            const d = await r.json();
            FeltbokSync.deliver(JSON.stringify(d));
          } catch (e) { FeltbokSync.deliver('{"error":"js"}'); }
        })();
    """.trimIndent()
}
