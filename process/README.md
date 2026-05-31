# Locality tooling

The Feltbok app matches each observation to an official Artsobservasjoner
locality. These scripts build the locality table the app ships, and verify how
the import matches names.

## Setup

```sh
python3 -m venv .venv && . .venv/bin/activate
pip install -r process/requirements.txt
```

## `build_localities.py` — the official locality table

Reconstructs the Artsobservasjoner locality registry from the **Artsdatabanken /
GBIF** Darwin Core Archive of the Norwegian Species Observation Service. Every
record carries the qualified locality name, its stable site id (`locationID`),
the site's canonical coordinates, and kommune/fylke — so one row per `locationID`
*is* the registry.

```sh
just localities                      # national (downloads the ~3 GB archive once)
just localities --county Trøndelag   # one fylke only (same download, smaller output)
# or directly:
python process/build_localities.py [--archive a.zip] [--bbox minlon,minlat,maxlon,maxlat] [--min-count 2]
```

Output `localities.csv`: `id,lokalitet,hovedlokalitet,kommune,fylke,lat,lon,count`.
`--min-count` drops rarely-referenced sites (usually private, won't match on
import). Push it onto the device with `just push-data` — it overrides the bundled
asset, no rebuild.

**Why this shape:** the import links a row when `Lokalitetsnavn` is the **bare**
name *and* the row carries the locality's **exact registry coordinate** — the
coordinate disambiguates duplicate names and rescues otherwise-unfound ones. The
app emits exactly that (WGS 84 geographic, the old import page's coordinate
setting). Importing *with an approximate* coordinate instead mints a duplicate
locality — which is the mess this avoids.

## `make_locality_test.py` — import-matching probes

Builds small no-/with-coordinate sheets (`lokalitetstest-gammel.xlsx` for the old
v2.20 site, `lokalitetstest-ny.xlsx` for the new v3.0 site) to confirm how the
import resolves locality names. Paste into *Rapportere → Importer observasjoner*
(don't publish) and read the per-row validation. This is how the recipe above was
established; keep it for re-testing if the import behaviour ever changes.

```sh
python process/make_locality_test.py
```

The Artsobservasjoner import templates live in `docs/` (`artsobs-template-v2.20.xlsx`,
`artsobs-template-v3.0.xlsx`).
