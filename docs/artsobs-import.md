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

The app exports the **qualified `Lokalitetsnavn`** and **no coordinates**. Observed
matching rules for the **paste** flow on the old site:

| What you paste | Result |
|---|---|
| **Qualified** name `"Lok, Hovedlok, Kommune, Fylke"` (e.g. `"Meliaunskjæret, Uttian, Frøya, Tø"`) | ✅ Links to the existing **public** (allmenn) locality. This is the match key. *(verified 2026-06-01 across a full day's upload)* |
| **Bare** name (`"Frøya sykehjem"`) | ❌ `LOKALITET: Kunne ikke validere lokalitet., Feltet er obligatorisk` — even for a clearly public locality. The bare name only matches **your own** localities ("Mine lokaliteter"), **not** the public registry. *(verified: a whole batch failed, incl. known-public sites)* |
| Bare name **+ coordinates** | ❌ Mints a new custom locality at those coords — the duplicate mess. |
| A name that is only a **private** locality | ❌ same `Kunne ikke validere lokalitet` — no public locality to match. *(e.g. "Skogholt ved Børaunet")* |

**This corrects an earlier wrong conclusion** (that the *bare* name links and the
*qualified* name fails). The earlier "bare name works" cases — "Lundeveien 9" etc. —
were the tester's **own** localities, which the bare name *does* match. For public
localities you must send the **full qualified name**, and the earlier qualified-name
failure ("Ørndalen, Frøya, Tø") was a **malformed** qualification that dropped the
hovedlokalitet — the real name is "Ørndalen, **Sistranda**, Frøya, Tø". Send the exact
registered string.

**Conclusion:** export the qualified name (`fullname` in `localities.csv`), no
coordinates. The qualified name must be exact — sublocality, hovedlokalitet (when the
locality has one), kommune, and the **fylke abbreviation** ("Tø" for Trøndelag), e.g.
`"Neset (Frøya), Frøya, Tø"` (no hovedlokalitet) vs `"Gåsvika, Uttian, Frøya, Tø"`.
`build_localities.py` preserves this exact string from the GBIF `locality` field as the
`fullname` column; the app emits it verbatim. Coordinates stay in the table only for
GPS-nearest picking, never exported.

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
