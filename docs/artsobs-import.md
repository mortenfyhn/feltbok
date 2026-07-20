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
The decimal separator is a **comma** (`63,670000`), not a period — the import parses numbers
with the account's number format (Norwegian/Swedish), so a period is rejected as *"not a decimal
number"*. The TSV is tab-delimited, so a comma inside the value is safe.
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
3. **The list must store the exact registered name.** (The old GBIF builder's `split_name()`
   mangled this — it kept only the first comma-token, storing `Sørøyan` and dropping
   ", Uttian".) The mobile API settles it: its `name` field *is* the exact registered
   locality name, so `build_sites.py` stores it verbatim — no splitting guesswork.

**Consequence:** export the registered name, append nothing, no coordinates. The user
scopes the import form to the kommune; truly-public names link, the user's own/unknown
ones still need a manual pick. `localities.csv` keeps the qualified `fullname` for display
only; coordinates stay for GPS-nearest picking, never exported (they only mint duplicates).

> Open question: how does **iGoTerra** export to this same old site and land on public
> localities? If it genuinely does, a working format exists that name/coords paste does
> not reach (a locality **id**, or the **v3.0** template). Inspecting a real iGoTerra
> export is the next lead. See `docs/custom-localities-design.md`.

## Why the locality list ships public-only

Paste can't link to public localities (see above), but the list should still be the
**public, established** sites — that's what the user recognises in the picker and what
the kommune-scoped import is most likely to resolve. This used to need heuristics (a
GBIF distinct-observer count and a name-collision/route filter) because the GBIF export
carried no public/private flag. That's gone now: the mobile-API harvest carries the
authoritative `isPrivate` flag per site, so `build_sites.py` simply keeps the public
(allmenn) ones — no proxies, no residual gap of look-alike private localities.

## Field mapping the app emits (paste TSV, old site)

`Artsnavn`, `Antall`, `Alder`, `Kjønn`, `Aktivitet`, `Lokalitetsnavn`, `Nord`, `Øst`,
`Nøyaktighet`, `Fra dato`, `Fra klokkeslett`, `Til dato`, `Til klokkeslett`,
`Kommentar (synlig for alle)`, `Privat kommentar (kun synlig for deg selv)`,
`Usikker artsbestemming`.

`Nord`/`Øst`/`Nøyaktighet` are left blank unless "Ta med koordinater" is on. Dates are
`dd.MM.yyyy`, times `HH:mm`; from/til default to the same instant (one moment of
observation). The time-of-day is optional: an obs saved with "Uten klokkeslett" keeps
its date(s) but emits both `klokkeslett` columns **blank** — the same "unknown" idiom as
a blank `Antall`. (Whether the site imports a date-only row cleanly is what the no-time
sample rows below exist to verify.)

`Nøyaktighet` (radius of a minted custom locality) **must be a positive integer** — a
`0 m` row hard-fails with *"Lokaliteten må ha en nøyaktighet som er et positivt heltall"*
(observed 2026-06 importing a new "punkt" locality). So **1 m is the smallest accepted
radius** and acts as the effective point — the locality picker no longer offers `0`/"punkt".

`Antall` is emitted blank for an unknown number of individuals (the `-1` sentinel,
[#89]). The spreadsheet template's *Instruksjoner* sheet documents `Antall` as "Et
positivt tall … (Kan stå tom …)", so a blank cell is a valid value there, and a live
paste of the sample **validates fine** on the import site — a blank `Antall` is accepted.
Still unconfirmed: whether it registers as *unknown* vs. silently defaulting to `1`, which
needs the **Kontroller funn** review page (blocked by a site-side bug at time of writing) —
see [#90].

## Manual integration test — seed, then export from the app

Rather than maintain a hand-written TSV (which drifts from real output — an invalid `Aktivitet`
string once slipped in that way and failed import), the integration test goes through the app:

1. `just seed` — pushes a clean batch of observations to the debug app (from
   `scripts/dev/make_sample_notes.py`; it imports them on next launch, **overwriting** its notes).
2. Export from the app (the normal Eksporter flow) and paste into "Importer observasjoner".
3. Check the live site accepts it end-to-end — validation **and** that rows reach "Kontroller
   funn", not just the green success message.

The seed deliberately spans every path `exportTsv` takes so one paste exercises the whole format:
a name-only registry locality, a brand-new spot (coordinates + radius, mints a private locality —
each import mints a fresh **duplicate**, so expect to clean those up), a **blank `Antall`** (unknown
count, see #90), the `Usikker artsbestemming` flag, both comment fields, same-day and multi-day time
ranges, the two **blank `klokkeslett`** no-time cases (single day and multi-day, #155), and
**`Medobservatør` columns** with 0/1/12 co-observers. To keep the import errors meaningful (rather
than drowned in avoidable ones), the seed uses inputs the site actually accepts: localities are
**real, globally-unique public localities** (unique across the country, so they resolve to the public
locality even without kommune-scoping the form — no "matchet flere allmenne lokaliteter"),
co-observers are **real registered users** (made-up or ambiguous names like a common "Ola Nordmann"
fail to resolve), and the one "I par" row uses an even count (the site requires partall). So a clean
seed should import with **no errors** — any error is then a real signal.
`python scripts/dev/make_sample_notes.py --why` prints a legend of what each row is for.

The `Medobservatør` columns repeat one header per co-observer; paste-import matches by header name,
so extra columns beyond the template's 10 are expected to work (the template hjelp says "10 felt
(kan være flere)") — but a live paste with **11+** co-observers is worth confirming before relying
on it.

The `Usikker artsbestemming` header is a **misspelling carried by the official template**
(the Norwegian word is *artsbestemmelse*; the Fugl sheet, col 40, has *-bestemming*).
Paste-import matches by header name, so the typo must be reproduced verbatim — a flagged
row under the correctly-spelt header fails validation. The cell is a checkbox: blank, or
`Ja` (the template accepts «X», «ja» or «1»).
