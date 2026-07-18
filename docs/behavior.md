# How the app behaves

Plain-language notes on user-visible behaviour that isn't obvious from the UI —
the kind of thing a field user might wonder about. The why and the tuning knobs
live in the code (`Search.kt`, `MainViewModel.kt`, `UseScore.kt`); this is the
short version.

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

### Names and languages

Every species carries three names — Norwegian, Swedish, and the scientific (Latin)
name — from the **IOC World Bird List**, one consistent source across both country
builds.

Under **Innstillinger** (open the About dialog from the footer version, then
"Innstillinger") you pick a **primary** name language and a **secondary** one shown
beneath it — each any of the three (Latin doubles as the "no second common name"
choice). Out of the box the Norway build shows Norwegian with Latin underneath, the
Sweden build Swedish with Norwegian underneath. A secondary equal to the primary just
isn't drawn; a species a source lacks the chosen name for falls back to Latin, so a
primary name is never blank.

**Search** matches the **primary** name only by default — so typing `t` in the Norway
build won't surface a bird via its Swedish name. A **"søk i begge språk"** toggle
(same screen) also searches the secondary language, as a fallback for when you can't
recall the primary-language name (e.g. a Swedish birder searching Norwegian). The third,
unshown language is never searched.

The **export is unaffected** by this choice: the pasted `Artsnavn`/`Artnamn` is always
the name the destination portal accepts (Norwegian for Artsobservasjoner, Swedish for
Artportalen) — so, e.g., a feral rock dove exports and is looked up as `bydue` even
though IOC's species name is `klippedue`.

## The notes list

Observations are grouped into per-day sections, newest day first, each under a date
header ("I dag", "I går", else an abbreviated date like "Søn 8. jun"). Within a day,
newest first. Each row carries species + count on the left, its locality, and the
time on the right (the day lives in the section header, so the row shows bare HH:mm).
A row with co-observers also shows a small **`+N`** pill (N = how many joined) beside
the species — no pill means it was just you.

Saving a new observation scrolls the list up to its day section, so you see the note
you just added even when it starts a fresh day above where the list was scrolled.

### Marking (masse-handlinger)

Long-press a row to start marking. A leading circle then appears on every row (and
on each day header), filling with a check when marked — tap a row or its circle to
toggle it, and a day header's circle marks or clears that whole day at once. You can
also **long-press and drag** across rows to sweep a range in one gesture (drag back
to un-sweep); dragging to the top or bottom edge auto-scrolls the list so the range
can run past what's on screen. The
status strip turns into a selection bar in place — a ✕ to leave, the count, and the
bulk actions **Endre**, **Slett** and **Eksporter** — so entering marking never shifts
the list. `Slett` removes the marked notes (undoable); deleting more than one asks to
confirm first, since several vanishing at once is surprising (a single mark deletes
straight away). `Eksporter` opens the export
walkthrough scoped to just them (see below). You leave marking only via the ✕, system
Back, or finishing an action — deselecting the last note keeps you in marking mode
(so you can re-pick), rather than dropping out. While *not* marking, a plain tap on a
row opens it for editing.

### Endre (batch edit)

`Endre` opens the ordinary observation editor over the whole selection. Fields the
marked notes agree on are pre-filled; fields they differ on show a pale preview of the
current mix ("Skjære, Gråmåke, …") and stay blank. Saving applies only the fields you
actually changed to every marked note (one undoable step) and returns you to the list
still marking, so you can make another pass. Comments and co-observers aren't
batch-editable (they're rarely the same across notes, and the party is a per-run
default rather than a bulk field), so they're hidden here; everything else — species,
locality, count, age, sex, activity, time — is. Marking a *single* note and tapping
`Endre` is just the normal single-note editor (nothing to batch).

## Medobservatører (co-observers)

Each observation can record who you observed with. The editor has a **Medobservatører**
row; tapping it opens a picker that filters the names you've used before (most-used
first) — tap a name to add or remove it, or type a new one and tap **Legg til «…»** to
add it as free text. The names you use are remembered locally to feed that
autocomplete; nothing leaves the phone. Each name has a **Slett** action to remove it
from the list (for a mistype or a one-off), behind a short confirm so it's never mistaken
for un-ticking the name from this observation — past observations keep whatever name they
were saved with, so this only prunes what you'll be offered next time.

The current field party is **sticky** ("følget mitt"): whatever co-observers you set on
a new observation carry over to the next new one, so once you note down who you're out
with, every following observation inherits them — you only touch the row when the party
changes. Editing an *existing* observation's co-observers doesn't disturb the sticky
party. **Nå er jeg alene** (in the picker) clears the set; save an observation with it
empty and the party is cleared going forward too. The party never auto-resets on its own — it
stays exactly as you last set it, since wrongly crediting someone who wasn't there (in a
public database) is worse than the small chore of clearing it.

On export, co-observers become the template's **`Medobservatør`** columns (one per name,
matched by header). They must be registered Artsobservasjoner users to link cleanly on
import; otherwise the row validates but the name may need fixing by hand afterwards.

## Export

One **Eksporter** button (top right of the status strip) opens a step-by-step
walkthrough: copy *all* your observations as a single block, paste it into
Artsobservasjoner's "Importer observasjoner", then clear them from the app once
they're safely in. (Exporting from a marking selection scopes the whole walkthrough
to just the marked notes — the copied block, the kommune hint, and the final clear
step all cover only those, so it never touches the rest of the list.) The clear step
follows the same rule as the list's `Slett`: clearing more than one note asks to
confirm first, a single one clears straight away — both undoable.

Everything pastes in one go, regardless of kommune. A bare locality name links to
the public locality as long as the name is unambiguous; scoping the import form to a
kommune only buys you disambiguation of a name that exists in *more than one*
kommune. So the flow leans on all-at-once: when every observation happens to be in
one kommune, the walkthrough suggests prioritising that kommune's localities on the
form (a free safety net); across several kommuner it just pastes everything and lets
unambiguous names resolve themselves. (The deep why — what links vs. what mints a
private duplicate — lives in `docs/artsobs-import.md`.)

## About / credits

Tapping the version string in the footer opens a small "Om Feltbok" dialog with the
maker line and the data credits — IOC World Bird List (species names), Artsdatabanken /
SLU Artdatabanken (red-list status, CC BY 4.0), Artsobservasjoner / Artportalen
(localities), and OpenStreetMap (map). The dialog also links to **Innstillinger** (the
name-language settings). The CC BY licence *requires*
attribution; this is its home. The map also carries an "© OpenStreetMap-bidragsytere"
corner credit, as the OSM tile policy requires.
