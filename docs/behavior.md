# How the app behaves

Plain-language notes on user-visible behaviour that isn't obvious from the UI —
the kind of thing a field user might wonder about. The why and the tuning knobs
live in the code (`Search.kt`, `MainViewModel.kt`, `UseScore.kt`); this is the
short version.

## Large text

The app follows the system font size, map labels included. When a screen's title doesn't fit
between the buttons in its top bar, it moves to its own row above them.

## Species search

### Before you type (the quick list)

Open the search screen and you get a list before touching the keyboard: simply your
**most-recently-picked species, newest first** (up to ~20; `blankQuickList` in
`MainViewModel.kt`, ordered by each species' last pick from `uses.json`). The idea
is that if the bird you want isn't right at the top you'd start typing anyway, so a
short recents list earns its keep more than a ranked season/context blend did.

When you have **too few recents to fill the list** — a fresh install, or early in an
outing — the rest is padded with whatever the typed search would surface for an
**empty query** (the same season + use-score blend described below, just with zero
letters typed), skipping birds already in your recents. So the list is never sparse,
and as you log birds your own recents push the padding out.

### Once you start typing

Search is **scoring, not filtering**: every species that matches at all gets a
score for what you've typed, and the whole list is shown best-first (no cap) — so
you always get *something* even on a near-miss, with the weak matches trailing off
the bottom rather than being cut. A species drops out only when the query doesn't
match its name *at all* — not even as scattered letters in order or a close typo;
type gibberish and you get an empty list. Each result's score combines a few
signals (`rankSpecies` in `Search.kt`):

- **Match quality, in tiers.** Best first: exact name → starts-with → ends-with
  (suffix) → initials (e.g. `pf` → Pilfink) → contains → scattered letters →
  typo. A better tier always wins; the rest only reorder *within* a tier.
- **Forgiving matching.** Spaces/hyphens are ignored, and once your query is
  4+ letters a small typo (one wrong/swapped letter) still matches — so `ardfugl`
  still finds Ærfugl and `rorsanger` Rørsanger. å/æ/ø aren't folded: they're a
  keystroke away, so typing the real letter matches precisely (`må` surfaces the
  måker, not every `ma…` bird) while an ASCII stand-in just rides the typo net
  like any other misspelling.
- **What's likely here and now.** Among comparable matches, species reported in
  the current month and near your current GPS location rank higher — spring
  migrants in May, southern birds in the south. Birds that are off-season or
  far away rank lower but stay findable. With no GPS fix it falls back to
  season + all-time commonness.
- **Your regulars get a nudge.** Species you pick often get a soft boost, so your
  usual birds tend to surface — but season and location can still outrank them.

The dataset is tiny (~600 species), so this all runs in well under a millisecond
per keystroke.

### Species not in the list

Only checklist species can be logged: an upload needs the species' taxon id on Artsobservasjoner.
For a bird that isn't in the list, pick the nearest "ub." entry and describe it in the comment.
Notes saved with a typed-in species by an older version can't be uploaded until a listed species
is picked for them.

### Ubestemt (ub.) entries

Artsobservasjoner also lets a sighting be logged as "ub. X" ("ubestemt") when you can narrow it
to a genus (`ub. svane`) or a small group of similar species (`ub. makrell-/rødnebbterne`) but not
the exact species. These are fetched from Artsobservasjoner itself
(`scripts/build_ubestemt.py`) and searchable like any other checklist entry — but they carry no
observation-count data, so they never get the "what's likely here and now" boost real species get;
a matching real species generally ranks above a same-tier ub. entry, though a better match tier
(e.g. typing "ub. …" exactly) still wins outright, same as for two real species.

### Names and languages

Every species carries three names — Norwegian, Swedish, and the scientific (Latin)
name — from the **IOC World Bird List**, one consistent source across both country
builds.

Under **Innstillinger** (open the About dialog from the footer version, then
"Innstillinger") you pick a **primary** name language and a **secondary** one shown
beneath it — each any of the three (Latin doubles as the "no second common name"
choice). Out of the box it shows Norwegian with Latin underneath. A secondary equal to the primary just
isn't drawn; a species a source lacks the chosen name for falls back to Latin, so a
primary name is never blank.

Names are cased by convention: **Swedish** names show with an initial capital ("Svartvit
flugsnappare", as on Artportalen), **Norwegian** stays all-lower-case, and the **Latin**
name keeps its capitalised genus and is always shown *italic* (whether primary or
secondary). This is display-only — the stored data and the export name stay lower-case.

**Search** matches the **primary** name only by default — so typing `t` won't surface a
bird via its Swedish name. A **"søk i begge språk"** toggle
(same screen) also searches the secondary language, as a fallback for when you can't
recall the primary-language name (e.g. a Swedish birder searching Norwegian). The third,
unshown language is never searched.

The **export is unaffected** by this choice: it sends the species' Artsobservasjoner taxon id,
whatever name the app shows.

## The notes list

Observations are grouped into per-day sections, newest day first, each under a date
header ("I dag", "I går", else an abbreviated date like "Søn 8. jun"). Within a day,
newest first. Each row carries species + count on the left, its locality, and the
time on the right (the day lives in the section header, so the row shows bare HH:mm;
an observation left without a time shows `--:--` there instead).
A row with co-observers also shows a small pill with N (how many joined) and a people
icon beside the species — no pill means it was just you.

Saving a new observation scrolls the list up to its day section, so you see the note
you just added even when it starts a fresh day above where the list was scrolled.
Saving an *edit* that changed something offers an undo ("Endra 1 observasjon"), on the
same longer timeout as a batch edit — the old values are gone otherwise.
**Lagre** is greyed out on an edit or batch edit until a field changes. The Slett/Lagre bar is
hidden while the keyboard is open, so it doesn't sit on top of the keys.

While you scroll, a thin indicator along the right edge shows where you are in the
list (it fades away at rest) — with a long history the day headers alone give no
sense of position. The papirkurv list has it too.

### Marking (masse-handlinger)

Long-press a row to start marking. A leading circle then appears on every row (and
on each day header), filling with a check when marked — tap a row or its circle to
toggle it, and a day header's circle marks or clears that whole day at once. You can
also **long-press and drag** across rows to sweep a range in one gesture (drag back
to un-sweep); dragging to the top or bottom edge auto-scrolls the list so the range
can run past what's on screen. The
status strip turns into a selection bar in place — a ✕ to leave, the count (cut to just the
number, or left out, when large text leaves no room), and the
bulk actions **Endre**, **Slett** and **Eksporter** — so entering marking never shifts
the list. `Slett` moves the marked notes to the papirkurv (undoable, and restorable
from the papirkurv later — see Papirkurv below); deleting more than one asks to
confirm first, since several vanishing at once is surprising (a single mark deletes
straight away). `Eksporter` opens the export
screen scoped to just them (see below). You leave marking via the ✕, system
Back, finishing an action, or by clearing the last mark — the bar disappears with it.
The footer (Papirkurv, Tilbakemelding, version) is hidden while marking, so a tap
at the bottom after a long drag-select can't open the papirkurv by mistake.
While *not* marking, a plain tap on a row opens it for editing.
Swiping a row to the left deletes it straight away (to the papirkurv, with the usual
undo), since that's the gesture many people try first. Swiping right does nothing, and
swiping is off while marking. Undoing a delete flashes the notes that came back, as
restoring from the papirkurv does.

Marking is easy to miss, so the app explains it once: when you make the same change
(the same field set to the same value) to a second note in a row, a dialog describes
how to mark several notes and use **Endre**. After you dismiss it, it never shows again.

### Endre (batch edit)

`Endre` opens the ordinary observation editor over the whole selection. Fields the
marked notes agree on are pre-filled; fields they differ on show a pale preview of the
current mix ("Skjære, Gråmåke, …") and stay blank. Saving applies only the fields you
actually changed to every marked note (one undoable step), drops the marks and returns
you to the list. The undo snackbar stays up a bit longer than a delete one, since the
old values can't be recovered any other way once it's gone. Comments aren't batch-editable (they're
rarely the same across notes), so they're hidden here; everything else — species,
locality, count, age, sex, activity, time, co-observers — is. Batch-editing
co-observers covers the classic slip of logging a run of notes before remembering to
set the party; it replaces each note's list (it doesn't append). Since the party follows
the most recent observation (see Medobservatører below), a batch that
covers it also fixes the default for the next new observation. Marking a *single* note
and tapping
`Endre` is just the normal single-note editor (nothing to batch).

## Tidspunkt (start/slutt)

The editor's time row opens a small editor with a **Start** and a **Slutt** row, each with a
separately-tappable date and time-of-day (so you can change just the day, or just the clock).
Slutt shows the same as Start until you edit it; then it becomes a real range that may span
midnight. The end can never fall before the start, and an end in the same minute as the start
is dropped, so Slutt mirrors Start again. That is how you go back to a single point in time.
Each row has its own **Nå** button, pinned to the right, that sets just that end to the current
moment. **Uten klokkeslett** keeps the date but leaves the time unspecified, for observations
logged after the fact, when you don't remember the time; it exports with the date filled in and
both klokkeslett columns blank.

## Medobservatører (co-observers)

Each observation can record who you observed with. The editor has a **Medobs** row
showing first names; tapping it opens a picker. It works like the species search: one list
with the names you've used before that match what you type (alphabetical by surname, the
last word of the name; each has a
switch showing whether it's on this observation, and tapping the row flips it), then
matching **Artsobservasjoner users**, then **Legg til «…»** at the bottom to add the typed
name as free text. The keyboard's Done key only hides the keyboard, and leaving the picker
never adds what's in the field.

A **Ingen medobservatører** button at the bottom switches everyone off in one tap (greyed out while
nobody is on, and hidden while the keyboard is open).

The user search runs when you've typed 3 or more letters and you're online;
**Søker…** shows while it runs. Your own names filter from the same 3 letters, so both
lists update together. Users show like on Artsobservasjoner: name and place in grey
italics. Their brukeralias shows in brackets only when another user on screen has the same
name and place, since aliases can look odd and people may not know they're public.
Tapping one adds them and saves them to your
list, with place and alias, so they show up offline next time. **Legg til «…»** is the
fallback for when you're offline, the name isn't found, or the person isn't a user: it's
hidden while the search runs and when a saved name or a user matches exactly, so with
namesakes you pick the right one by place and alias. An observation stores a picked user
as their brukeralias, so namesakes never mix and what it exports is fixed when you pick.

A name you type joins the list right away, so switching it off doesn't make it disappear.
The names are remembered locally; only the search text is sent to Artsobservasjoner. To
remove names from the list (a mistype or a one-off), mark them as you mark observations:
long-press a name (and drag to sweep a range), and the switches give way to leading select
circles; tapping marks more names, and the header shows the count with **Slett**. Slett
removes the marked names at once and offers an undo; ✕ or system Back leaves without
deleting. Past observations keep whatever they were saved with, so this only prunes what
you'll be offered next time.

The current field party ("følget mitt") follows **your most recent observation's
co-observers**: every new observation is pre-filled with them, so once you note down who
you're out with, everything that follows inherits them — you only touch the row when the
party changes. Fixing the newest observation's co-observers (alone or in a batch) fixes
the default for the next one, and editing or backdating *older* observations can't hijack
today's party.

The party is also shown on its own line under the green strip on the list screen: the
count, the list rows' people icon and first names ("2 👥 Kari, Ola"), or **Ingen
medobservatører valgt** when nobody is set — so you can see at a glance who the next
observation will credit, and notice a party you forgot to set. Tapping that line opens the
same picker and sets the party directly, without going through an observation.
Switch everyone off and save an observation, and the party is cleared going forward too.
The party never resets on its own — it
tracks exactly what you last saved, since wrongly crediting someone who wasn't there (in a
public database) is worse than the small chore of clearing it.

On export, a user picked from the search is linked to the sighting as an observer, by their user
id (looked up by brukeralias when the app doesn't have it yet, so namesakes never mix). A
free-text name can't be linked, so it goes into the public comment as "Medobservatør: …".

## Comments

The editor's **Åpen kommentar** and **Privat kommentar** rows each open a full-screen writing page.
Each holds at most 254 characters, the site's limit: typing stops there, and a longer paste is cut.
A counter ("230/254") shows from 200 characters. Notes written before the limit can be longer; the
export lists them for fixing.

## Skjul t.o.m. (hide until)

Each observation can be hidden from other Artsobservasjoner users until a date you pick. It's
for vulnerable birds whose site you don't want a crowd turning up at — a breeding record you'd
rather release once the season is over.

The editor's last row, **Skjul t.o.m.**, is blank by default. Tapping it opens the date picker,
seeded a year ahead (hiding usually runs past the next breeding season). The picker's own
**Ikke skjul** button clears the date, so an observation can be un-hidden without leaving the
row; Back or a tap outside closes it unchanged. The row is batch-editable, so marking a run of
observations and setting one date hides the whole trip.

On export it becomes the sighting's hide-until date.

## Locality picker (map)

The status strip and the detail screen's locality field show a small dot before the locality's
name: green for a public locality, yellow for a private one (your own or a brand-new one).

Tapping the locality field (or the status strip) opens a map of the registered localities over
OpenStreetMap tiles. Each locality is a filled green disk at its real radius
(polygons trace their real outline; superlocalities are a deeper green), and the selected one is
highlighted; far-out views hide the smallest ones so the map doesn't clutter.

The map opens centred on the locality the observation already has, however far that is from your
GPS fix — you opened the picker to adjust around that place. Only when there's no locality yet does
it centre on your own position, and then it follows you: the map moves with each new GPS fix until you
pan it. A pinch or the +/− buttons zoom around your position and keep following. While the map isn't
following, a GPS button (the blue location dot) turns it back on.

The picker also looks up public localities created after the bundled list was harvested. When the map
has been still for a second, and the view is under 40 km across in Web Mercator (about 18 km on the
ground at 63°N), the app asks Artsobservasjoner's `ByBoundingBox` endpoint for the public localities
in view, plus a margin of half a view on each side, so a short pan shows localities that are already
loaded. Any it doesn't already have appear on the map, with a toast ("Henta 3 allmenne lokaliteter").
Nothing shows when there is nothing new or the request fails (offline). Private localities in the
response are dropped; your own come from a separate request (see "Logging in" below).

Logged out, the endpoint also returns everyone's private localities and cuts a long response off
without saying so. In dense areas the app therefore splits a cut-off request into quarters until each
answer is complete. One map settle makes at most 8 requests, view first, then margin. If cells in view are left
when they're used up, and the view is under 10 km across, it carries on in further rounds while the
map stays still and the picker is open. So rural areas load whole from far out, and a city centre
completes at about zoom 15-16. Responses are gzipped; a complete zoom-15 view of central Trondheim is
about 0.75 MB, nearly all of it other people's private localities. Logged in, the same view is a few
tens of KB and is never cut off, so none of this splitting happens.

To avoid repeat requests, the map is split into fixed 2 × 2 km cells (Web Mercator), and a cell is
checked at most once per 24 hours while the app is running; restarting the app checks again, for
example for a locality you just created on the website. Fetched localities are saved to
`live-localities-<versionCode>.csv` in internal storage and load with the bundled list at startup;
one already in the bundle is skipped. An app update starts a new file and deletes the old one, since
the new bundle is re-harvested: that drops localities since deleted, renamed or made private, and
still-new ones are fetched again when viewed. Lookups wait until the bundled list has loaded, so a
picker opened right after launch doesn't count every locality in view as new.

### New locality name

"＋ Ny lokalitet" places a spot at the crosshair, above a panel with the name and the radius. The
zoom buttons hide while the panel is open; the re-centre button stays above it and puts the crosshair
on your GPS position. The name field starts blank, with "Ny lokalitet" as a hint, and Lagre sits
at the bottom of the panel, level with the foot of the radius wheel. Under the field, "Se
navneforslag (n)" offers names from Kartverket's stedsnavn register (the source behind
Norgeskart): the app asks for the names within 1000 m of the crosshair each time the map settles,
with a small spinner while it waits. The link hides as soon as you pan, since the names are for the
old spot. The link opens a list with each name's type and distance; tapping one fills the field.
Nothing is filled in without that tap, and a spot saved with an empty field is named "Ny lokalitet".
✕ clears the field. Offline, the link doesn't show.

The ranking drops bus stops, administrative areas and historical names, sorts the rest by distance
from the crosshair, and keeps the nearest 12. The distance is to the name's shape in the register,
not to where Norgeskart puts the label: a street or river is a line, so you're close to it when you
stand by any part of it. In a town that puts many streets within 100 m, so at most 4 street names
are kept, leaving room for the other names.

### Logging in

The footer under the map says **"Logg inn for å vise private lokaliteter"** while you're logged out.
Tapping it opens the Artsobservasjoner login page in the app ("Logg inn"). A thin progress bar runs along
the top of the page while a page loads, including the wait after you tap the site's own "Logg inn". When the login lands back on
the site, the screen closes by itself and you're back in the picker with your observation as it was;
"‹ Tilbake" also returns to the picker. If Artsobservasjoner's login server still remembers you, the
page goes straight through without asking for a password. The session is kept in the login page's
cookie store and lasts 30 days from the login.

Logged in, the footer says **"Oppdater lokaliteter"**, with **"Logg ut"** to the right. "Logg ut"
shows the toast "Logga ut" and clears the login page's cookies, the login server's too, so the next login asks for the password.
Your private localities stay on the phone. The app checks this each time the picker opens
(`/bff/user`). The app sends the session cookie with its own requests, which gives two things:

- **Your private localities.** They come from `ByUser`, all of them, and replace the stored set, so
  renames and deletions on the website come through. They're kept in `my-localities.csv`.
- **Complete public localities in dense areas**, as above.

Tapping "Oppdater lokaliteter" (with a spinner in the footer) fetches your private localities and
fetches the public ones in view again, even if they were checked recently, then shows one toast:
"Henta 2 allmenne lokaliteter og 1 endra privat lokalitet", "Henta 99 private lokaliteter" the first
time, or "Allerede oppdatert". When it fails, the toast says why: "Ingen internettilkobling" when the phone
has no working network, "Artsobservasjoner utilgjengelig, prøv igjen seinere" when the site
didn't answer, the same with "(kode 503)" added when it answered with an error status, or "Klarte ikke å hente lokaliteter" when the answer couldn't be read or saved.
Coming back from the login does the same.
A session can stop working for your own localities while the site still counts you as logged in
(seen a month after a login). The app then logs you out as "Logg ut" does: the toast says
"Innlogging utløpt, logg inn igjen", and the footer offers "Logg inn for å vise private lokaliteter"
again.

You rarely need to tap it. Logged in, the map's response also lists private localities you can see,
without saying whose: your own, and other people's that you have reported on. When it has one the app
doesn't know, for example one you just made on the website, the app fetches your own localities in the
background, with a toast only if something changed ("Henta 99 private lokaliteter (1 endra)"). The
ones that turn out to be other people's are remembered until the app is closed, so they don't set off
another fetch, and are never shown.

The locality suggested by default (before you pick one) is based on your GPS fix's position relative
to each **footprint**, not just the nearest centre. If you're standing inside one or more localities,
the **smallest** containing one is suggested (the most specific — matching how a tap resolves nested
localities). If you're inside none, the one whose **edge** is closest wins. So a large locality you're
inside beats a small one you're outside, yet a small locality nested inside a big one still wins when
you're inside both.

A point locality (no radius or polygon) counts as a 200 m disk here, as in Artskart. When another
point locality is closer than 400 m, both shrink to half the distance between them, so the disks just
touch. So a point locality inside a lake or a reserve is suggested when you're near it. Points at the
same spot get no disk and fall back to plain centre distance. The disks only affect the suggestion:
the map draws point localities as before, and a tap on the map still picks as before.

## Export

**Eksporter** (top right of the status strip) opens the export screen. It sends the observations
straight to «Kontroller funn» on Artsobservasjoner, one by one, through the site's mobile API
(`POST /core/Sightings`), so nothing is copied or pasted. The button says what it sends:
"Eksporter alle (30)", or "Eksporter markerte (3)" when opened from a marking selection, which
scopes the whole screen to the marked notes.

Exporting needs a login. When the screen opens it checks the stored login with the site; without
one, or when the site refuses it, a "Logg inn" button stands in for the export button, and logging
in returns to the screen. Offline the export button stays on, and the export says what's wrong.

Every note is checked before anything is sent: first in the app (a species from the list, a
locality that still exists, known age, sex and activity, an even count for "I par", no date in the
future, an end after the start), then against the site (the taxon id when the bundled list lacks
it, the co-observers' user ids, the comment's length with the co-observers added). Any problem
blocks the whole export, so it never stops halfway over something the app could find. The notes
that need fixing are listed like the main list, each with what's wrong (the fix is left out where
it's obvious); a tap opens the editor and returns to the export screen. The export button stays off until all are fixed.

While it sends, a bar and "12 av 30 eksportert" show how far it got, and the greyed button keeps
the label it was tapped with. The bar stays where an export stopped (at 0 when nothing was sent):
- A lost connection or login stops it, with the reason under the bar, and the button becomes
  "Prøv igjen (18 gjenstår)".
- A note the site rejects is listed with the site's own reason (asked for in Norwegian, without
  its "Invalid arguments: " prefix); the rest are sent. The button
  stays off until it's fixed.

The screen remembers what it sent only while it's open. Leaving it with notes left over marks those
in the list, so Eksporter there sends just them. The next export sends exactly what it's asked to,
including notes sent before: duplicates are the user's call. Each note keeps its sighting id from
the site, but nothing uses it yet.

When all are sent, the bar is full and reads "✓ 30 av 30 eksportert", the export button stays off,
and two actions appear: "Åpne «Kontroller funn»" (the old site's review page, which most users
prefer) and "Slett eksporterte observasjoner fra appen (30)". Deleting follows the list's `Slett`
rule: more than one asks first, a single one goes straight away, both undoable, and the notes land
in the papirkurv.

## Papirkurv

`Slett` never destroys anything: deleted observations move to the papirkurv
(trash bin) instead, and the undo snackbar says "Sletta …". It never empties itself.
The code still calls it the archive (`archived`, `openArchive`, `archive.jsonl`),
its name until #192. A **Papirkurv** link in the
list footer opens it. It shows even when the papirkurv is empty, so it's discoverable
before you first need it. The papirkurv screen is the same day-grouped list, with
brown chrome instead of green so it can't pass for the main list, and
marking behaves exactly like the main list (long-press to start, drag to sweep a
range with edge auto-scroll, circles, day-header toggles, the header turning into
a selection bar) — the one difference is that a plain tap marks the note instead
of opening it, since a deleted note can't be opened; the first tap is enough to
start marking, so getting one note back needs no long-press. The single action is
**Gjenopprett**, which moves the marked notes back into the live list and returns
to it. The list scrolls to the newest restored note, and the restored notes flash once. There is no
true delete in the app — clearing the app's data is the escape hatch if someone
really wants everything gone. The papirkurv lives in its own file (`archive.jsonl`,
append-only on delete), so launch and saving stay
proportional to the *live* notes however big the papirkurv grows.

## About / credits

Tapping the version string in the footer opens a small "Om Feltbok" dialog with the
maker line and the data credits — IOC World Bird List (species names), Artsdatabanken
(red-list status **and** alien-species status), Artsobservasjoner (localities), and
OpenStreetMap (map). The dialog also links to **Innstillinger** (the name-language settings).
Artsdatabanken's data is CC BY 4.0, whose licence *requires* attribution — this is its home. The map also carries an
"© OpenStreetMap-bidragsytere" corner credit, as the OSM tile policy requires.
