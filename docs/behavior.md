# How the app behaves

Plain-language notes on user-visible behaviour that isn't obvious from the UI —
the kind of thing a field user might wonder about. The why and the tuning knobs
live in the code (`Search.kt`, `MainViewModel.kt`); this is the short version.

## Species search

### Before you type (the quick list)

Open the search screen and you get a list before touching the keyboard. It's
built in three layers (`speciesQuickList` in `Ui.kt`):

1. **Your recent picks** — the species you last chose, most-recent first (kept to
   the last 8, and it survives app restarts). A fresh install seeds this with the
   most common species so the list isn't empty.
2. **Your regulars** — anything else you've picked before, ordered by how often
   you've picked it.
3. **Likely here and now** — the rest, ordered by what's reported in the current
   month near your location (same season/location signal the typed search uses;
   see below), to fill the screen.

Each bird comes from exactly one of these layers — your picks stay pinned on top,
and the season/location ranking only orders the fill, so it never buries a bird
you choose often.

### Once you start typing

Search is **scoring, not filtering**: every species gets a score for what you've
typed and the best ~40 are shown, so you always get *something* even on a near-miss.
Each result's score combines a few signals (`TieredScorer` in `Search.kt`):

- **Match quality, in tiers.** Best first: exact name → starts-with → ends-with
  (suffix) → initials (e.g. `pf` → Pilfink) → contains → scattered letters →
  typo. A better tier always wins; the rest only reorder *within* a tier.
- **Forgiving matching.** Diacritics are folded, so `ardfugl` finds Ærfugl and
  `rorsanger` finds Rørsanger; spaces/hyphens are ignored; and once your query is
  4+ letters a small typo (one wrong/swapped letter) still matches. Typing the
  real æ/ø/å gives a small precision bonus but is never required.
- **What's likely here and now.** Among comparable matches, species reported in
  the current month and near your current GPS location rank higher — spring
  migrants in May, southern birds in the south. Birds that are off-season or
  far away rank lower but stay findable. With no GPS fix it falls back to season
  + all-time commonness.
- **Your regulars get a nudge.** Species you pick often get a soft boost, so your
  usual birds tend to surface — but season and location can still outrank them.

The dataset is tiny (~600 species), so this all runs in well under a millisecond
per keystroke.
