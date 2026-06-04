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

The app exports the **registered `Lokalitetsnavn`** and **no coordinates**. Controlled
matrices paste-tested against the live old site (2026-06-02) settled the rule, after two
earlier wrong theories:

**The match key is the *exact registered* `Lokalitetsnavn`, verbatim — with nothing
appended.** Get that string right and it links to the public (allmenn) locality.

| What you paste | Result |
|---|---|
| Exact registered name (`Uttian`; `Sørøyan, Uttian`) **+ kommune scoped on the form** | ✅ links to the public locality |
| Name **+ kommune + fylke appended** (`Uttian, Frøya, Tø`) | ❌ `Kunne ikke validere lokalitet` — appending the geo qualifier breaks it |
| Name that is only a **private** locality, or one that is **neither public nor yours** (`Uttiveien`) | ❌ `can't find matching locality` |
| Name **+ coordinates** | mints a new private locality at those coords |

Key nuances learned the hard way:

1. **Commas are not the killer.** A comma that is genuinely *part of the registered
   name* is fine — e.g. Sørøyan's registered name literally is **"Sørøyan, Uttian"** (the
   trailing ", Uttian" is a stuck data-entry mistake by whoever created it). Bare
   "Sørøyan" can't match; "Sørøyan, Uttian" links cleanly. What breaks matching is
   *appending kommune + fylke* (`, Frøya, Tø`), which is not a disambiguation method.
2. **Two account-side settings on the import form change everything.** *Kommune scope*
   narrows the search so the exact name resolves to the public locality. *"Prioriter
   private lokaliteter og favoritter"* makes the import prefer your own localities first —
   with it on, names tend to match/create **private** copies instead of the public one.
   (iGoTerra reportedly uploads cleanly with no kommune scope and that box left on — so it
   must encode the locality some other way: own established localities, coordinates, or an
   id. Getting a real iGoTerra export is the open lead; see `custom-localities-design.md`.)
3. **`build_localities.py` must store the exact registered name.** Its `split_name()`
   only kept the first comma-token, so it stored `Sørøyan` and dropped ", Uttian". The
   correct split: the *last* token of the place part is the superlokalitet, everything
   before it is the (possibly comma-containing) name. The messy doubled-token GBIF strings
   (`Strandfjæra, Hitra, Strand, Hitra, Tø`, ~12 of them) can't all be recovered cleanly
   from the flattened string — canonical names from iGoTerra/the API would settle those.

**Consequence:** export the registered name, append nothing, no coordinates. The user
scopes the import form to the kommune; truly-public names link, the user's own/unknown
ones still need a manual pick. `localities.csv` keeps the qualified `fullname` for display
only; coordinates stay for GPS-nearest picking, never exported (they only mint duplicates).

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
`Kommentar (synlig for alle)`, `Privat kommentar (kun synlig for deg selv)`,
`Usikker artsbestemming`.

`Nord`/`Øst`/`Nøyaktighet` are left blank unless "Ta med koordinater" is on. Dates are
`dd.MM.yyyy`, times `HH:mm`; from/til are the same instant (one moment of observation).

The `Usikker artsbestemming` header is a **misspelling carried by the official template**
(the Norwegian word is *artsbestemmelse*; the Fugl sheet, col 40, has *-bestemming*).
Paste-import matches by header name, so the typo must be reproduced verbatim — a flagged
row under the correctly-spelt header fails validation. The cell is a checkbox: blank, or
`Ja` (the template accepts «X», «ja» or «1»).
