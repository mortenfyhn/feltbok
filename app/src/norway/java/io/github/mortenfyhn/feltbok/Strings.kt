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

        // Undo snackbar shown after a delete (issue #122).
        fun deleted(n: Int) = "Sletta $n ${if (n == 1) "observasjon" else "observasjoner"}"
        const val undo = "Angre"
    }

    // Credits/attribution shown by tapping the footer version (#139). CC BY 4.0 for the
    // Artsdatabanken data *requires* attribution; OSM is credited on the map but repeated here too.
    object About {
        const val title = "Om Feltbok"
        const val madeBy = "Laga av Morten F. Amundsen"
        const val dataHeader = "Data og kilder"
        const val artsdatabanken = "Artsdata fra Artsdatabanken (CC BY 4.0)"
        const val artsobs = "Lokaliteter fra Artsobservasjoner"
        const val osm = "Kart © OpenStreetMap"
        const val close = "Lukk"
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
        const val time = "Tid"
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

    object Time {
        const val title = "Tid"
        const val from = "Fra"
        const val to = "Til"
        const val done = "Ferdig"
    }

    object Export {
        const val title = "Eksporter"
        const val step1 = "Kopier observasjoner"
        const val copy = "Kopiér"
        const val copied = "Kopiert ✓"
        const val step2 = "Åpne Artsobservasjoner"
        const val open = "Kjør"
        const val step3 = "Lim inn og importer"

        // The paste step itself is unconditional, ending in <pasteEmphasis, italic>.
        const val pasteBody = "Lim inn observasjonene og trykk "
        const val pasteEmphasis = "Importer"

        // A suggestion (not an instruction) shown when every note shares one kommune: prioritising
        // its localities on the form resolves a name even if the same one exists elsewhere. The
        // kommune is bolded at render (see ExportScreen); the rest is plain.
        fun tip(kommune: String) = "(Prioriter lokaliteter i $kommune for å unngå kollisjoner)"
        const val step4 = "Kontroller funn"
        const val step4Body = "Sjekk at observasjonene vises i Artsobservasjoner"
        const val step5 = "Slett fra appen"
        const val step5Body = "Når alt er på plass, kan du slette observasjonene fra appen"
        const val clearAll = "Slett alle observasjoner"
        const val clearTitle = "Slett alle observasjoner?"

        // Shown instead when the export covered only marked notes (#120): names the count so it's
        // clear the rest of the list is untouched.
        fun clearSelected(n: Int) = "Slett ($n)"
        fun clearSelectedTitle(n: Int) = "Slett de $n eksporterte observasjonene?"
        const val clearBody = "Sørg for alt er på plass i Artsobservasjoner!"
        const val clearConfirm = "Slett"
    }

    object Sync {
        const val login = "Logg inn på Artsobservasjoner"   // also the LOGIN-stage title
        const val update = "Oppdater lokaliteter"            // also the list-screen footer
        const val fetch = "Hent mine lokaliteter"            // also the list-screen footer
        const val afterLogin = "Etter innlogging henter vi lokalitetene automatisk."
        const val intro = "Feltbok kan hente dine private lokaliteter fra Artsobservasjoner. Trykk på knappen for å logge inn, så går resten av seg selv."
        const val fetching = "Henter lokaliteter…"
        const val error = "Noe gikk galt under henting."
        const val retry = "Prøv igjen"
        const val done = "OK"                                // DONE-stage button back to the list
        const val doneGeneric = "Ferdig ✓"
        fun doneFirst(total: Int) = "Henta $total lokaliteter ✓"
        fun doneChanged(total: Int, changed: Int) = "Henta $total lokaliteter ($changed endra) ✓"
        fun doneUnchanged(total: Int) = "Allerede oppdatert ($total lokaliteter) ✓"
    }

    object Picker {
        const val titleNew = "Ny lokalitet"
        const val titlePick = "Velg lokalitet"
        const val newButton = "＋ Ny lokalitet"
        fun meters(m: Int) = "$m m"
        const val nameLabel = "Lokalitetsnavn (valgfritt)"
        const val namePlaceholder = "Ny lokalitet"
        const val save = "Lagre"
    }
}
