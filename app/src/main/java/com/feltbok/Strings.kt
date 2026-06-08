package com.feltbok

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
        const val empty = "Ingen observasjoner enda.\nTrykk + for å legge til."
        const val today = "I dag"
        const val yesterday = "I går"

        // Per-day section header on the notes list; [label] is the day ("I dag", a date, …).
        fun header(label: String, n: Int) = "$label · $n ${if (n == 1) "observasjon" else "observasjoner"}"
        const val feedback = "Gi tilbakemelding"
        const val export = "Eksporter"
        const val youAreHere = "du er her"
        fun distanceAway(d: String) = "$d unna"
        const val searchingGps = "søker GPS"
        const val gps = "GPS"
        fun gpsAccuracy(m: Int) = "(GPS ±$m m)"

        // Selection mode: long-press a note to mark it, then tap more, then delete the marked ones.
        fun selected(n: Int) = "$n markert"
        const val deleteSelected = "Slett"
        fun deleteTitle(n: Int) = "Slett $n ${if (n == 1) "observasjon" else "observasjoner"}?"
        const val deleteConfirm = "Slett"
    }

    object Feedback {
        const val title = "Tilbakemelding"
        const val body = "Ta gjerne kontakt om du har tilbakemelding eller spørsmål:"
        const val githubIssue = "GitHub Issue"
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
        const val asPrevious = "som forrige"
        const val time = "Tid"
        const val delete = "Slett observasjon"
        const val save = "Lagre"
        const val dropdownNone = "Ingen"

        // Confirm before the header's Forkast throws away an in-progress observation (NN/g: guard
        // destructive cancels that lose work).
        const val discardTitleNew = "Forkast observasjon?"
        const val discardTitleEdit = "Forkast endringene?"
        const val discardConfirm = "Forkast"
        const val discardKeep = "Fortsett"
        const val deleteTitle = "Slett observasjon?"
        const val deleteConfirm = "Slett"
    }

    object Time {
        const val title = "Tid"
        const val range = "Tidsrom"
        const val fromDate = "Fra dato"
        const val date = "Dato"
        const val fromTime = "Fra kl."
        const val clock = "Klokkeslett"
        const val toDate = "Til dato"
        const val toTime = "Til kl."
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
        const val pasteBefore = "Lim inn den kopierte teksten på Artsobservasjoner og trykk "
        const val pasteEmphasis = "Importer"
        const val step4 = "Ferdig"
        const val doneBody = "Nå kan du\n· Kontrollere og publisere i Artsobservasjoner\n· Slette observasjonene fra appen"
        const val clear = "Slett alle observasjoner"
        const val clearTitle = "Slett alle observasjoner?"
        fun clearBody(n: Int) = "Sletter alle $n observasjonene. Ikke gjør dette før du har importert alt til Artsobservasjoner!"
        const val clearConfirm = "Slett alle"
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
        const val doneGeneric = "Ferdig ✓"
        fun doneFirst(total: Int) = "Hentet $total lokaliteter ✓"
        fun doneChanged(total: Int, changed: Int) = "Hentet $total lokaliteter ($changed endret) ✓"
        fun doneUnchanged(total: Int) = "Allerede oppdatert ($total lokaliteter) ✓"
    }

    object Picker {
        const val titleNew = "Ny lokalitet"
        const val titlePick = "Velg lokalitet"
        const val newButton = "＋ Ny lokalitet"
        const val privateToggle = "Vis private"
        const val point = "punkt"
        fun meters(m: Int) = "$m m"
        const val nameLabel = "Lokalitetsnavn (valgfritt)"
        const val namePlaceholder = "Ny lokalitet"

        // Reassure that a new spot doesn't have to be perfect: it can be adjusted later (issue #55).
        const val adjustHint = "Du kan justere lokaliteten seinere på artsobservasjoner.no"
        const val save = "Lagre"
    }
}
