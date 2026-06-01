# Artsobservasjoner import — observed behaviour

Feltbok produces text you paste into Artsobservasjoner's **"Importer observasjoner"**
page. There is **no official spec**: the maintainers confirmed by email (2026-06)
that *no written documentation of the import/locality-matching behaviour exists*.
Everything here is reverse-engineered from live testing, so treat it as empirical —
re-verify if the site changes.

## Two sites, two templates

| | Old site | New site |
|---|---|---|
| Spreadsheet template | `artsobs-template-v2.20.xlsx` | `artsobs-template-v3.0.xlsx` |
| Sublocality column 2 | `Superlokalitet` | `Hovedlokalitet` |
| Public comment (col 14) | `Kommentar (synlig for alle)` | `Merknad (synlig for alle)` |
| Private comment (col 15) | `Privat kommentar …` | `Privat merknad …` |

The two `Fugl`-sheet layouts diverge after the comment columns (v2.20 carries extra
`Natursystem`/`Livsmedium` columns the new one drops). The leading columns we use are
identical in order. **Feltbok targets the old site (v2.20) via paste.**

## Coordinate format

Geographic WGS 84 decimal degrees vs UTM is **not** in the file — it's a per-account
setting on the old site (Min side → coordinate format). The app emits decimal degrees,
so the account must be set to *Geografisk (WGS 84)* for coordinates to be read right.
But see below: **we normally export without coordinates**, so this rarely matters.

## Locality matching — the make-or-break

The app exports the **bare `Lokalitetsnavn`** and (by default) **no coordinates**.
Observed matching rules for the **paste** flow on the old site:

| What you paste | Result |
|---|---|
| Bare name, **no** coordinates | ✅ Links to the existing **public** (allmenn) locality of that name. *(verified: "Lundeveien 9", "Litle Hammarvatnet")* |
| Bare name **+ coordinates** | ❌ **Mints a new custom locality** at those coords — the duplicate mess. Paste behaves *unlike* file-upload here (in an `.xlsx` upload test, name+coords linked instead). |
| Qualified name `"Ørndalen, Frøya, Tø"` | ❌ `Lokalitet ble ikke funnet`. Appending kommune/fylke **breaks** the match — it is *not* a disambiguation method. The bare sublocality name is the match key. |
| A name that is only a **private** locality | ❌ `LOKALITET: Kunne ikke validere lokalitet., Feltet er obligatorisk` — there is no public locality to match. *(verified: "Skogholt ved Børaunet")* |
| A bare name shared by **several public** localities | ❌ `flere allmenne lokaliteter` — needs **manual** disambiguation on the site. |

**Conclusion:** export bare name, strip coordinates. Coordinates are kept in
`localities.csv` only to pick the GPS-nearest locality in the app, never exported.
This is the same trick iGoTerra documents ("strip GPS before upload").

## Why the locality list still needs filtering

Matching only works against **public** localities, but the GBIF export Feltbok builds
its list from (`build_localities.py`) carries **no public/private flag** — that lives
only in Artsobservasjoner's own DB. So we approximate "public" with heuristics, each
covering a failure mode seen in the data:

1. **Distinct-observer count** (`--min-observers`, default 2): a private locality has a
   single owner. Drops the long tail of one-person sites.
2. **Name-collision / route filter** (`drop_name_collisions`, `--public-min`,
   `--cluster-km`): a birder laying out a personal route stamps many same-named points
   in one sitting — consecutive site ids, a few records each, often co-observed so they
   clear the observer filter (e.g. seven `Sildskjærbugen` within 2 km). A real public
   locality is **one** canonical site. For any name used at >1 site we keep a point only
   if it looks public on its own (record count ≥ `--public-min`) and is >`--cluster-km`
   from every kept same-name site.

These are proxies. The residual gap — a **uniquely-named** private locality with a
couple of co-observers (e.g. "Skogholt ved Børaunet") — looks identical to a small
public locality in GBIF data and can only be filtered out reliably with the real
allmenn flag from the Artsobservasjoner API (build-time download, requested separately).

## Field mapping the app emits (paste TSV, old site)

`Artsnavn`, `Antall`, `Alder`, `Kjønn`, `Aktivitet`, `Lokalitetsnavn`, `Nord`, `Øst`,
`Nøyaktighet`, `Fra dato`, `Fra klokkeslett`, `Til dato`, `Til klokkeslett`,
`Kommentar (synlig for alle)`, `Privat kommentar (kun synlig for deg selv)`.

`Nord`/`Øst`/`Nøyaktighet` are left blank unless "Ta med koordinater" is on. Dates are
`dd.MM.yyyy`, times `HH:mm`; from/til are the same instant (one moment of observation).
