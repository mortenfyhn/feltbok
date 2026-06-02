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

The app exports the **bare `Lokalitetsnavn`** and **no coordinates**. A controlled
6-row matrix paste-tested against the live old site (2026-06-02) settled the rules —
and overturned two earlier theories. Same dummy observation on every row, only the
locality columns varied:

| What you paste | Validates? | Links to public? |
|---|---|---|
| **Bare** name, no coords (`Uttian`) | ✅ | ❌ mints a private duplicate |
| Bare name **+ coords** (centroid, `100 m`) | ✅ | ❌ mints a private duplicate |
| Bare name **+ `Superlokalitet`** column | ✅ | ❌ mints a private duplicate |
| Bare + Superlokalitet **+ coords** | ✅ | ❌ mints a private duplicate |
| **Comma-qualified** `"Lok, Hovedlok, Kommune, Fylke"` | ❌ `Kunne ikke validere lokalitet` | — |

Two firm conclusions:

1. **The comma-qualified single-column name hard-fails validation.** Commas in
   `Lokalitetsnavn` break it outright. (The earlier "qualified name is the match key"
   note was wrong — it was never actually paste-tested in isolation; a whole day's
   *bare-name* upload had failed for a different reason and qualification was assumed to
   be the fix.)
2. **Paste never links an observation to a public locality.** Every form that validates
   instead **mints a new private duplicate** in "Mine lokaliteter" — name alone,
   name+coords, name+superlokalitet, all of them. Only **manual selection on the site**
   links to a public (allmenn) locality.

**Consequence:** there is no paste format that lands an observation on a public
locality, so we stop trying to encode one. The app exports the **bare name** (the only
clean, lowest-friction thing that validates). The user **scopes the import form to the
kommune** so the site disambiguates same-named localities as best it can, then fixes the
residual by hand on the site (re-selecting the public locality). `localities.csv` keeps
both the bare `lokalitet` (exported) and the qualified `fullname` (display only);
coordinates stay for GPS-nearest picking, never exported (they only mint duplicates).

> Open question: how does **iGoTerra** export to this same old site and land on public
> localities? If it genuinely does, a working format exists that name/coords paste does
> not reach (a locality **id**, or the **v3.0** template). Inspecting a real iGoTerra
> export is the next lead. See `docs/custom-localities-design.md`.

## Why the locality list still needs filtering

Paste can't link to public localities (see above), but the list should still be mostly
**public, established** sites — that's what the user recognises in the picker and what
the kommune-scoped import is most likely to resolve. The GBIF export Feltbok builds its
list from (`build_localities.py`) carries **no public/private flag** — that lives only in
Artsobservasjoner's own DB — so we approximate "public" with heuristics (also used for
the map's green/yellow colouring), each covering a failure mode seen in the data:

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
