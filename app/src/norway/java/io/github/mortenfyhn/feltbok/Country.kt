package io.github.mortenfyhn.feltbok

import java.util.Locale

/**
 * Per-country (build flavor) configuration: everything that differs between the Norwegian
 * (Artsobservasjoner) build and the Swedish (Artportalen) one. This file is the Norway
 * build's values; the Sweden flavor supplies its own copy so the rest of the app stays
 * country-agnostic and just reads `Country.*`.
 *
 * Note vocabularies (ages/activities/sexes) are the *exact* values the import expects, so a
 * picked value is exported verbatim — no per-country translation at export time.
 */
object Country {
    // Locale for *display* date/time (weekday + month names in the UI); export dates are numeric.
    val displayLocale: Locale = Locale("nb", "NO")

    // ---- species-name languages (#155) ----
    // The export always writes the Norwegian name (what Artsobservasjoner accepts on import),
    // regardless of which language the user chooses to *see*. The defaults reproduce the old
    // hard-wired behaviour (Norwegian, with Latin underneath); the user can change them in Settings.
    val exportLang = Lang.NORSK
    val defaultPrimary = Lang.NORSK
    val defaultSecondary = Lang.LATIN

    // ---- export (paste) format ----
    val exportDateFmt = "dd.MM.yyyy"
    val exportTimeFmt = "HH:mm"
    val uncertainYes = "Ja"
    val exportCols = listOf(
        "Artsnavn", "Antall", "Alder", "Kjønn", "Aktivitet", "Lokalitetsnavn", "Nord", "Øst",
        "Nøyaktighet", "Fra dato", "Fra klokkeslett", "Til dato", "Til klokkeslett",
        "Kommentar (synlig for alle)", "Privat kommentar (kun synlig for deg selv)",
        // The registered template header is misspelt "artsbestemming" (not "-bestemmelse").
        // Paste-import matches columns by header name, so the misspelling must be reproduced
        // verbatim or a flagged row fails validation (col 40 of the v2.20 Fugl template; checkbox
        // cells accept «X»/«ja»/«1», so "Ja" is fine).
        "Usikker artsbestemming",
    )

    // Header for the repeated co-observer columns (#128). The v2.20/v3.0 Fugl template ships 10
    // "Medobservatør" columns (R–AA); paste-import matches by header name, so exportTsv appends as
    // many identical columns as the batch needs. The template hjelp says "10 felt (kan være flere)",
    // so >10 is expected to work - worth a live paste-test before relying on 11+.
    val coObserverCol = "Medobservatør"

    // ---- bird option vocabularies (Fugl), exactly as the import expects them ----
    val ages = listOf(
        "Egg", "Pulli", "Adult",
        "1K", "1K+", "2K", "2K+", "2K-", "3K", "3K+", "3K-", "4K", "4K+", "4K-",
        "5K", "5K+", "5K-", "6K", "6K+", "6K-", "7K", "7K+", "7K-",
    )
    val sexes = listOf("Hann", "Hunn", "Hunnfarget", "I par")

    // The everyday non-breeding activities, surfaced first so the long list below
    // doesn't have to be scrolled for the common case.
    private val commonActivities = listOf(
        "Rastende", "Stasjonær", "Overflygende", "Næringssøkende", "Trekkende",
        "Sang/spill, ikke hekking", "Lokkelyd, øvrige lyder", "Ved fôring",
        "Revir, ikke hekking", "Permanent revir",
    )

    // The full Fugl activity list, in the template/website order.
    private val allActivities = listOf(
        "Reir med egg eller unger", "Reir, unger hørt", "Rugende", "Mat til unger",
        "Bar ekskrementpose", "Reir i bruk", "Besøker bebodd reir",
        "Unger utenfor reir, ikke utvokste", "Brukt reir", "Eggeskall",
        "Avledningsmanøver", "Mislykket hekking", "Reirbygging", "Rugeflekker",
        "Engstelig adferd, indikasjon på hekking", "Reirbesøk?",
        "Paring/kurtise på mulig hekkeplass", "Permanent revir",
        "Par i passende hekkebiotop", "Sang/spill i hekketid og passende hekkebiotop",
        "Observasjon i hekketid, passende biotop", "Rastende", "Stasjonær",
        "Overflygende", "Næringssøkende", "Ved fôring", "Sang/spill, ikke hekking",
        "Lokkelyd, øvrige lyder", "Revir, ikke hekking", "Ringmerket",
        "Individmerket (kontroll)", "Trekkforsøk", "Trekkende", "Trekkende mot N",
        "Trekkende mot NØ", "Trekkende mot Ø", "Trekkende mot SØ", "Trekkende mot S",
        "Trekkende mot SV", "Trekkende mot V", "Trekkende mot NV", "Syk",
        "Død - kollisjon med kraftledning", "Død - kollisjon med vindturbin",
        "Død - kollisjon med vindu", "Død - kollisjon med fyr", "Død - kollisjon med fly",
        "Død - kollisjon med gjerde", "Drept av elektrokusjon (strømslag)",
        "Drept av olje", "Trafikkdrept", "Garndød", "Skadet av fiskeredskap",
        "Drept av predator", "Død av sykdom/sult", "Skutt/avlivet",
        "Død - ukjent dødsårsak", "Ferske spor", "Eldre spor", "Fersk møkk", "Eldre møkk",
    )
    val activities = commonActivities + allActivities.filterNot { it in commonActivities }

    /** Nøyaktighet written for every row - the locality's own coordinate is exact
     *  enough that the import snaps to the registered locality. */
    val accuracy = "100 m"

    // ---- geography / online services ----
    // Locality map style. Norway has real polygons and lower density, so filled disks read fine.
    // (Sweden's near-all-circle, heavily-overlapping localities use the hollow style instead.)
    val hollowLocalities = false

    // Fallback map centre when there's no GPS fix or nearby locality (central Norway).
    val mapCenterLat = 63.7
    val mapCenterLon = 8.7
    val importUrl = "https://www.artsobservasjoner.no/ImportSighting"

    val adjustHint = "Du kan justere lokaliteten seinere på artsobservasjoner.no"

    // ---- private-locality sync: a WebView logs in, then a same-origin fetch pulls the user's own
    // sites (the session cookie rides along). syncEnabled gates the footer entry point. ----
    val syncEnabled = true
    val sitesHost = "https://mobil.artsobservasjoner.no"
    val syncProbeUrl = "$sitesHost/my-sites"
    val syncLoginUrl = "$sitesHost/bff/login?returnUrl=/my-page"
    val loginPathMarker = "/bff/" // while the URL still contains this we're mid-login; don't fetch yet
    val mySitesParse: (String) -> List<Locality> = ::parseMySites
    val mySitesFetchJs = """
        (async () => {
          try {
            let all = [], page = 1, total = 1;
            do {
              const r = await fetch('/core/Sites/ByUser?pageSize=100&pageNumber=' + page,
                                    { headers: { 'X-CSRF': '1' }, credentials: 'same-origin' });
              if (r.status === 401 || r.status === 403) { FeltbokSync.deliver('{"error":"auth"}'); return; }
              if (!r.ok) { FeltbokSync.deliver('{"error":"http"}'); return; }
              const d = await r.json();
              (d.data || []).forEach(function(x) { all.push(x); });
              total = d.totalPages || 1; page++;
            } while (page <= total);
            FeltbokSync.deliver(JSON.stringify({ data: all }));
          } catch (e) { FeltbokSync.deliver('{"error":"js"}'); }
        })();
    """.trimIndent()
}
