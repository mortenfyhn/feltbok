# Processing voice notes → Artsobservasjoner import sheet

The Android app records one voice note per observation, encoding time + GPS +
accuracy in each filename, e.g.:

```
2026-05-30T08-33-12_lat59.912340_lon10.756780_acc8.m4a
```

This script turns a folder of those into rows in the Artsobservasjoner import
template (`docs/artsobs-template-v3.0.xlsx`), ready to paste into
Artsobservasjoner → *Rapportere* → *Importer observasjoner*. The v2.20 template
also works (`-t docs/artsobs-template-v2.20.xlsx`); column renames are handled.

## Pipeline

1. **Transcribe** locally with `faster-whisper` — by default the dialect-tuned
   **NB-Whisper** model (see Setup), falling back to `large-v3` if not built —
   seeded with a bird-name prompt.
2. **Parse** each transcript with Claude → species (corrected against Norwegian
   bird names, optionally validated against `bird_species.csv`), count,
   activity, notes, and an uncertainty flag.
3. **Resolve the locality** (a required field). If the GPS is within
   `--locality-radius` (200 m) of a known locality, that exact name is used so
   the import **links to your established locality**; otherwise a Kartverket
   place name is used, which creates a new point the row flags for review.
4. **Write** a row per clip into a copy of the template's `Fugl` sheet, then
   move handled recordings into `recordings/processed/` so re-runs only see new
   ones (`--keep` to disable). Silent/accidental clips go to `recordings/skipped/`.

Nothing is dropped: a clip Claude is unsure about — or one missing GPS, or a
species off the checklist — still gets a row, marked `Usikker artsbestemming = X`
with the raw transcript in the private comment, so you can fix it on the
website's *Kontrollér og publisér* step.

## Setup

```sh
python3 -m venv .venv && . .venv/bin/activate
pip install -r process/requirements.txt
export ANTHROPIC_API_KEY=sk-ant-...
```

Build the **NB-Whisper** Norwegian model once (~3 GB download + convert):

```sh
just nb-whisper          # -> models/nb-whisper-large-ct2
```

Without it, transcription falls back to stock `large-v3` (downloaded on first
run). It runs on CPU; expect roughly real-time-ish per clip on the Framework.

## Run

```sh
just process             # or: .venv/bin/python process/process.py recordings/
# -> observasjoner.xlsx
```

Parsing defaults to Claude **Sonnet**. Options: `-o out.xlsx`, `-t template.xlsx`,
`--claude-model claude-opus-4-8` (better on rare species, higher cost),
`--whisper-model large-v3` (skip NB-Whisper), `--localities file.csv`,
`--locality-radius 150`, `--species file.csv`, `--keep` (don't archive
processed clips).

Each observation is written at its **locality's** coordinate (from the gazetteer
or, for new spots, the nearest Kartverket place point) rather than the raw GPS
fix, so every note at the same place groups under one locality instead of
minting a new tiny locality per observation.

When you paste into the import form, set its **coordinate-system selector to
"WGS 84 geographic"** (lat/long decimal degrees) — it defaults to UTM 32N, which
would misread the coordinates. No profile change needed; the selector wins.

## Localities

The import links a row to an existing locality when the **name matches one near
the coordinates** — and coordinates disambiguate same-named localities
nationwide. So a recording near a known locality gets that exact name → links to
the established locality; anywhere else falls back to a Kartverket name (a new
point, flagged for review). `process.py` reads two CSVs (`name,lat,lon`):

**1. The harvested gazetteer (`localities_gazetteer.csv`) — build it once:**

```sh
python process/build_localities.py --bbox 10.0,63.3,10.7,63.5   # minlon,minlat,maxlon,maxlat
```

This pulls bird records (Artsobservasjoner / BirdLife Norge) from the open
Artskart API inside the box and aggregates the distinct localities with their
names and coordinates — no manual list-building. It also writes the distinct
bird names seen to `bird_species.csv`, used as the parser's checklist. Widen the
`--bbox` to cover wherever you bird; `--from-date` / `--max-pages` control depth.

**2. Your own list (`my_localities.csv`) — for private spots the gazetteer
won't have**, e.g. nest nicknames:

```
name,lat,lon
"reir-ringdue-furu-1",63.42704,10.41914
```

Names with commas/quotes work if wrapped in double quotes. Both files are
optional and merged; your list is a good place for anything the harvest misses.

## Example sheet (no recording / no API needed)

```sh
python process/make_example.py   # -> eksempel_observasjoner.xlsx
```

Three fake observations, to test the upload flow end to end.

## Notes

- `Nord`/`Øst` are decimal degrees (WGS84/ETRS89) — the template takes them
  directly, no UTM conversion.
- GPS accuracy is rounded **up** to the nearest allowed `Nøyaktighet` radius, so
  the sheet never claims more precision than the fix had.
- openpyxl drops the in-cell dropdown *validation* when it saves the copy; the
  values are plain text and import fine — the website validates on import.
- If local Whisper underperforms on bird names, swapping in a cloud STT is a
  one-function change in `make_transcriber`.
