package io.github.mortenfyhn.feltbok

/**
 * All user-facing chrome strings for the Sweden (Artportalen) build — the Swedish counterpart of the
 * Norway flavor's Bokmål Strings. Same object/field shape; only the wording differs. Export/wire
 * values that must match Artportalen exactly live in Country.kt, not here.
 */
object Strings {
    const val cancel = "Avbryt"
    const val back = "Tillbaka"
    const val findingPosition = "Hittar position…"

    object Notes {
        const val empty = "Tryck + för ny observation"
        const val today = "Idag"
        const val yesterday = "Igår"
        const val feedback = "Ge feedback"
        const val export = "Exportera"
        fun speciesCount(n: Int) = if (n == 1) "1 art" else "$n arter"
        fun speciesTotal(n: Int) = "($n totalt)"
        const val youAreHere = "du är här"
        fun distanceAway(d: String) = "$d bort"
        const val searchingGps = "söker GPS"
        const val gps = "GPS"
        fun gpsAccuracy(m: Int) = "(GPS ±$m m)"

        fun selected(n: Int) = "$n " + if (n == 1) "markerad" else "markerade"
        const val deleteSelected = "Ta bort"

        fun deleted(n: Int) = "Tog bort $n " + if (n == 1) "observation" else "observationer"
        const val undo = "Ångra"
    }

    object About {
        const val title = "Om Feltbok"
        const val madeBy = "Skapad av Morten F. Amundsen"
        const val dataHeader = "Data och källor"
        const val artsdatabanken = "Artdata från Artdatabanken (CC BY 4.0)"
        const val artsobs = "Lokaler från Artportalen"
        const val osm = "Karta © OpenStreetMap"
        const val close = "Stäng"
    }

    object Feedback {
        const val title = "Feedback"
        const val body = "Hör av dig om du hittar fel, undrar något eller har feedback:"
        const val githubIssue = "Använd GitHub"
        const val email = "Skicka e-post"
        const val mailSubject = "Feltbok-feedback"
        const val mailHint = "Bifoga gärna en skärmbild."
    }

    object Search {
        const val placeholder = "Sök art…"
    }

    object Detail {
        const val titleNew = "Ny observation"
        const val titleEdit = "Ändra observation"
        const val species = "Art"
        const val locality = "Lokal"
        const val count = "Antal"
        const val age = "Ålder"
        const val activity = "Aktivitet"
        const val sex = "Kön"
        const val commentPublic = "Publik kommentar"
        const val commentPrivate = "Privat kommentar"
        const val time = "Tid"
        const val delete = "Ta bort"
        const val copy = "Kopiera"
        const val save = "Spara"

        fun savedChangesToast(species: String) = "Ändringar sparade ($species)"
        fun savedNewToast(species: String) = "Observation sparad ($species)"

        fun discarded(wasEdit: Boolean) = if (wasEdit) "Ändringar förkastade" else "Observation förkastad"
    }

    object Time {
        const val title = "Tid"
        const val from = "Från"
        const val to = "Till"
        const val done = "Klar"
    }

    object Export {
        const val title = "Exportera"
        const val step1 = "Kopiera observationer"
        const val copy = "Kopiera"
        const val copied = "Kopierat ✓"
        const val step2 = "Öppna Artportalen"
        const val open = "Kör"
        const val step3 = "Klistra in och importera"

        const val pasteBody = "Klistra in observationerna och tryck "
        const val pasteEmphasis = "Importera"

        fun tip(kommune: String) = "(Prioritera lokaler i $kommune för att undvika krockar)"
        const val step4 = "Kontrollera fynd"
        const val step4Body = "Kontrollera att observationerna syns i Artportalen"
        const val step5 = "Ta bort från appen"
        const val step5Body = "När allt är på plats kan du ta bort observationerna från appen"
        const val clearAll = "Ta bort alla observationer"
        const val clearTitle = "Ta bort alla observationer?"
        const val clearBody = "Se till att allt är på plats i Artportalen!"
        const val clearConfirm = "Ta bort"
    }

    object Sync {
        const val login = "Logga in på Artportalen"
        const val update = "Uppdatera lokaler"
        const val fetch = "Hämta mina lokaler"
        const val afterLogin = "Efter inloggning hämtar vi lokalerna automatiskt."
        const val intro = "Feltbok kan hämta dina privata lokaler från Artportalen. Tryck på knappen för att logga in, så sköter resten sig själv."
        const val fetching = "Hämtar lokaler…"
        const val error = "Något gick fel vid hämtningen."
        const val retry = "Försök igen"
        const val done = "OK"
        const val doneGeneric = "Klar ✓"
        fun doneFirst(total: Int) = "Hämtade $total lokaler ✓"
        fun doneChanged(total: Int, changed: Int) = "Hämtade $total lokaler ($changed ändrade) ✓"
        fun doneUnchanged(total: Int) = "Redan uppdaterad ($total lokaler) ✓"
    }

    object Picker {
        const val titleNew = "Ny lokal"
        const val titlePick = "Välj lokal"
        const val newButton = "＋ Ny lokal"
        fun meters(m: Int) = "$m m"
        const val nameLabel = "Lokalnamn (valfritt)"
        const val namePlaceholder = "Ny lokal"
        const val save = "Spara"
    }
}
