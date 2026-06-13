# How the app behaves

Plain-language notes on user-visible behaviour that isn't obvious from the UI —
the kind of thing a field user might wonder about. The why and the tuning knobs
live in the code (`Search.kt`, `MainViewModel.kt`, `UseScore.kt`); this is the
short version.

## Species search

### Before you type (the quick list)

Open the search screen and you get a list before touching the keyboard. It has a
small **pinned batch** on top, then every species ranked by a single score
(`blankQuickList` in `MainViewModel.kt`).

**The pinned batch** is your current outing's birds kept one tap away. A species is
pinned only while *both* hold: you picked it **within the last 6 hours**, *and*
it's among your **4 most-recently-picked** such species. So pins fall off on their
own — 6 hours after you last pick a species it drops out (an outing's batch clears
itself by next morning), and picking a 5th species bumps the oldest pin back down
into the ranked list. The pinned rows are ordered by recency, not score, so a
just-picked bird can sit above a higher-scored one. (Tuning: `PIN_WINDOW_MS`,
`PIN_MAX` in `UseScore.kt`.)

**Everything below** is ranked by one blended score, so your regulars and what's
likely here-and-now compete in a single list instead of being stacked in tiers:

- **How much you use it** — each pick bumps a per-species score that **fades with a
  ~2-week half-life**, so a bird you logged a lot recently ranks high, while one you
  logged heavily but long ago has decayed away. This replaces a plain "recent list"
  and "all-time counts" with one recency-aware signal (persisted in `uses.json`).
- **Likely here and now** — what's reported in the current month near your location
  (the same season/location signal the typed search uses; see below).

The two are blended (currently leaning on your own history; see `PERSONAL_W` /
`CONTEXT_W` / `PERSONAL_MIDPOINT` in `UseScore.kt`). In practice the here-and-now
term is fairly flat across common in-season birds, so it mainly pushes off-season
or out-of-region birds *down* rather than reordering the likely ones — and because
your use score already fades over weeks, it carries most of the "what's relevant
now" signal on its own.

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
