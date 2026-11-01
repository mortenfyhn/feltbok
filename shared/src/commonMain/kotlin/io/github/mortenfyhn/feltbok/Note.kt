package io.github.mortenfyhn.feltbok

const val UNKNOWN_COUNT = -1 // Note.count sentinel: unknown number of individuals

data class Note(
    val id: Long, // creation time in ms - the stable key (never changes)
    val time: Long = id, // observation start (date+time) - editable; defaults to id
    val endTime: Long? = null, // observation end; null = a single time point (Til = Fra on export)
    val timeUnknown: Boolean = false, // date is known but the time-of-day isn't (obs added after the fact -> blank klokkeslett)
    val species: String,
    val latin: String,
    val count: Int, // UNKNOWN_COUNT (-1) = unknown number of individuals (-> blank Antall)
    val age: String,
    val activity: String,
    val sex: String,
    val publicComment: String,
    val privateComment: String,
    val locName: String, // short locality name, for display
    val locFull: String, // qualified Lokalitetsnavn, for the import
    val lat: Double,
    val lon: Double,
    val newLoc: Boolean = false, // a brand-new spot: export with coordinates so the import mints it
    val locRadius: Int = 0, // chosen radius in metres for a new spot (-> Nøyaktighet)
    val uncertain: Boolean = false, // uncertain species determination (-> "Usikker artsbestemming")
    val coObservers: List<String> = emptyList(), // who you observed with (-> Medobservatør columns)
    // Kommune the obs belongs to, stamped at save for the per-kommune export grouping. A new spot
    // resolves it from the nearest registry locality at creation; blank on notes saved before this
    // existed (grouping then falls back to the locFull / nearest-locality lookup).
    val kommune: String = "",
    // Hide the observation from everyone else until after this date (-> "Skjul funn til dato").
    // For vulnerable birds you don't want a crowd turning up to search for. null = not hidden.
    val hideUntil: Long? = null,
)
