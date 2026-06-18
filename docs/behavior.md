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
