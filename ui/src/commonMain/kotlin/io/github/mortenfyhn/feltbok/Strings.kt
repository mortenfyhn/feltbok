package io.github.mortenfyhn.feltbok

/**
 * All user-facing chrome strings, in one place so the wording stays consistent and easy to edit.
 * Bokmål. Static text is a `const val`; text with values interpolated is a small `fun` so the
 * phrasing (and any pluralisation) lives here too.
 *
 * Deliberately NOT here: export/wire values that must match Artsobservasjoner exactly (the activity
 * options and TSV headers in Model.kt), and live diagnostics like the build/version `tech` string.
 * If i18n ever happens, this is the object to turn into per-locale implementations.
 */
object Strings {
    const val cancel = "Avbryt"
    const val back = "Tilbake"                        // header back action on screens that discard nothing
    const val findingPosition = "Finner posisjon…"   // shared by the status strip and the detail screen

    // What Android's nb-NO locale shows, minus the trailing dots ("jun.", "søn.").
    val dateNames = DateNames(
        months = listOf("jan", "feb", "mar", "apr", "mai", "jun", "jul", "aug", "sep", "okt", "nov", "des"),
        weekdays = listOf("Man", "Tir", "Ons", "Tor", "Fre", "Lør", "Søn"),
        today = Notes.today,
        yesterday = Notes.yesterday,
    )

    object Notes {
        const val empty = "Trykk + for ny observasjon"
        const val today = "I dag"
        const val yesterday = "I går"
        const val feedback = "Gi tilbakemelding"
        const val export = "Eksporter"
        fun speciesCount(n: Int) = if (n == 1) "1 art" else "$n arter"
        fun speciesTotal(n: Int) = "($n totalt)"
        const val youAreHere = "du er her"
        fun distanceAway(d: String) = "$d unna"
        const val searchingGps = "søker GPS"
        const val gps = "GPS"
        fun gpsAccuracy(m: Int) = "(GPS ±$m m)"

        // Selection mode: long-press a note to mark it, then tap more, then delete the marked ones.
        fun selected(n: Int) = "$n markert"
        const val deleteSelected = "Slett"

        // Confirm before a multi-note delete (#156): dropping several at once is surprising, so ask
        // (a single one just deletes - the undo snackbar catches a misfire).
        fun confirmDeleteTitle(n: Int) = "Slett $n observasjoner?"
        const val edit = "Endre"
        fun editTitle(n: Int) = "Endre $n ${if (n == 1) "observasjon" else "observasjoner"}"

        // Undo snackbar shown after a delete (issue #122) or a batch edit (#120).
        fun deleted(n: Int) = "Sletta $n ${if (n == 1) "observasjon" else "observasjoner"}"
        fun edited(n: Int) = "Endra $n ${if (n == 1) "observasjon" else "observasjoner"}"
        const val undo = "Angre"
    }

    // The trash bin. Deleting never destroys a note: it moves to the bin and can be restored (#153).
    // Users see "Slett" and "Papirkurv" (#192), but the code still says "archive" (`archived`,
    // `openArchive`, archive.jsonl) from when the bin was called Arkiv. The footer link shares the title.
    object Archive {
        const val title = "Papirkurv"
        const val empty = "Papirkurven er tom"
        const val restore = "Gjenopprett"
    }

    // Credits/attribution shown by tapping the footer version (#139). CC BY 4.0 for the
    // Artsdatabanken data *requires* attribution; OSM is credited on the map but repeated here too.
    object About {
        const val title = "Om Feltbok"
        const val madeBy = "Laga av Morten F. Amundsen"
        const val dataHeader = "Data og kilder"
        const val names = "Artsnavn fra IOC World Bird List"
        const val artsdatabanken = "Rødliste fra Artsdatabanken (CC BY 4.0)"
        const val alienRisk = "Fremmedarter fra Artsdatabanken (CC BY 4.0)"
        const val artsobs = "Lokaliteter fra Artsobservasjoner"
        const val osm = "Kart © OpenStreetMap"
        const val close = "Ålreit"
        const val settings = "Innstillinger"
    }

    object Settings {
        const val title = "Innstillinger"
        const val primaryHeader = "Hovedspråk for artsnavn"
        const val secondaryHeader = "Andrespråk for artsnavn"
        const val searchSecondary = "Søk i begge språk"
        fun langLabel(lang: Lang) = when (lang) {
            Lang.NORSK -> "Norsk"
            Lang.SVENSK -> "Svensk"
            Lang.LATIN -> "Vitenskapelig"
        }
    }

    object Feedback {
        const val title = "Tilbakemelding"
        const val body = "Ta kontakt om du finner feil, lurer på noe eller har tilbakemelding:"
        const val githubIssue = "Bruk GitHub"
        const val email = "Send e-post"
        const val mailSubject = "Feltbok-tilbakemelding"
        const val mailHint = "Legg gjerne ved et skjermbilde."
    }

    object Search {
        const val placeholder = "Søk art…"
        fun add(name: String) = "Velg «$name»"
    }

    object Detail {
        const val titleNew = "Ny observasjon"
        const val titleEdit = "Endre observasjon"
        const val species = "Art"
        const val locality = "Lokalitet"
        const val count = "Antall"
        const val age = "Alder"
        const val activity = "Aktivitet"
        const val sex = "Kjønn"
        const val commentPublic = "Åpen kommentar"
        const val commentPrivate = "Privat kommentar"
        const val commentDone = "Ferdig"
        const val uncertain = "Usikker artsbestemming"
        const val coObservers = "Medobs."   // short, so the names beside it get the room (#176)
        const val time = "Tid"
        const val hideUntil = "Skjul t.o.m."   // wording from the website's own field
        const val hideUntilNone = "Ikke skjul"   // the date picker's neutral button
        const val delete = "Slett"
        const val copy = "Kopier"
        const val save = "Lagre"

        // Copy commits the draft, then makes a fresh copy; confirm the silent save. Wording depends
        // on whether the commit updated an existing obs or added a new one.
        fun savedChangesToast(species: String) = "Endringer lagra ($species)"
        fun savedNewToast(species: String) = "Observasjon lagra ($species)"

        // Undo snackbar after leaving the editor without saving (#122); wording depends on whether
        // it was a new observation or edits to an existing one.
        fun discarded(wasEdit: Boolean) = if (wasEdit) "Endringer forkasta" else "Observasjon forkasta"
    }

    // The co-observer picker (#128): search/add names, alphabetical, tap to toggle onto the obs,
    // long-press to mark names for deletion (#191).
    object CoObs {
        const val title = "Medobservatører"
        fun titleCount(n: Int) = "$title ($n)"   // with anyone switched on: how many, for a big party
        const val placeholder = "Søk eller skriv navn…"
        const val done = "Ferdig"
        const val allOff = "Slå av alle"   // the picker's footer: switches everyone off

        // The list-screen party header (#176) when nobody is set. "valgt" (#191): you may just not
        // have picked anyone yet, which "Ingen medobservatører" read as being alone.
        const val none = "Ingen medobservatører valgt"
        const val remove = "Slett"   // delete the marked names from the autocomplete list entirely
        fun forgot(n: Int) = "Sletta $n ${if (n == 1) "medobservatør" else "medobservatører"}"
        fun add(name: String) = "Legg til «$name»"
        const val searching = "Søker…"   // while the Artsobservasjoner user search runs
    }

    object Time {
        const val title = "Tid"
        const val from = "Start"
        const val to = "Slutt"
        const val now = "Nå"
        const val done = "OK"
        const val noTime = "Ukjent klokkeslett"
    }

    object Export {
        const val title = "Eksporter"
        const val step1 = "Kopier observasjoner"
        const val copy = "Kopier"
        const val copied = "Kopiert ✓"
        const val step2 = "Åpne Artsobservasjoner"
        const val open = "Åpne"
        const val step3 = "Lim inn og importer"

        // Ends in <pasteEmphasis, semibold>, the label of the site's button.
        const val pasteBody = "Lim inn observasjonene og trykk "
        const val pasteEmphasis = "Importer"

        const val step4 = "Kontroller funn"
        const val step4Body = "Sjekk at observasjonene vises i Artsobservasjoner"
        const val step5 = "Slett i appen"
        const val step5Body = "Når alt er på plass, kan du slette observasjonene i appen"
        fun clearAll(n: Int) = "Slett alle observasjoner ($n)"
        const val clearTitle = "Slett alle observasjoner?"

        // Shown instead when the export covered only marked notes (#120): names the count so it's
        // clear the rest of the list is untouched.
        fun clearSelected(n: Int) = "Slett $n ${if (n == 1) "observasjon" else "observasjoner"}"
        fun clearSelectedTitle(n: Int) = "Slett de $n eksporterte observasjonene?"
        const val clearBody = "Sørg for alt er på plass i Artsobservasjoner!"
        const val clearConfirm = "Slett"
    }

    object Sync {
        const val login = "Logg inn"                         // the login screen's title
        const val update = "Oppdater lokaliteter"            // the picker-map footer, logged in
        const val loginForPrivate = "Logg inn for å vise private lokaliteter"  // the picker-map footer, logged out
        const val error = "Klarte ikke å hente lokaliteter"

        /** "Oppdater lokaliteter": [newPublic] new public ones in view, and what changed in your own. */
        fun updated(newPublic: Int, d: SyncDiff): String {
            val parts = listOfNotNull(
                newPublic.takeIf { it > 0 }?.let(::publicCount),
                when {
                    d.firstSync -> privateCount(d.total)
                    d.changed > 0 -> if (d.changed == 1) "1 endra privat lokalitet" else "${d.changed} endra private lokaliteter"
                    else -> null
                },
            )
            return if (parts.isEmpty()) "Allerede oppdatert" else "Henta " + parts.joinToString(" og ")
        }

        /** After the map set off a fetch of your own localities: first time, or something changed. */
        fun done(d: SyncDiff) = when {
            d.firstSync -> "Henta ${privateCount(d.total)}"
            d.changed > 0 -> "Henta ${privateCount(d.total)} (${d.changed} endra)"
            else -> "Private lokaliteter er allerede oppdatert"
        }

        private fun publicCount(n: Int) = if (n == 1) "1 allmenn lokalitet" else "$n allmenne lokaliteter"
        private fun privateCount(n: Int) = if (n == 1) "1 privat lokalitet" else "$n private lokaliteter"
    }

    object Picker {
        fun liveFetched(n: Int) = "Henta " + if (n == 1) "1 allmenn lokalitet" else "$n allmenne lokaliteter"
        const val titleNew = "Ny lokalitet"
        const val titlePick = "Velg lokalitet"
        const val newButton = "＋ Ny lokalitet"
        const val radiusLabel = "Radius"
        fun meters(m: Int) = "$m m"
        const val nameLabel = "Lokalitetsnavn (valgfritt)"
        const val namePlaceholder = "Ny lokalitet"
        const val save = "Lagre"
    }
}
