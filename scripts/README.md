# Locality tooling

The Feltbok app matches each observation to an official Artsobservasjoner
locality. These scripts build the public locality table the app ships.

## Setup

```sh
python3 -m venv .venv && . .venv/bin/activate
pip install -r scripts/requirements.txt
```

## The pipeline: `harvest_sites_mobil.py` → `build_sites.py`

```sh
just harvest          # harvest the official site list from the mobile API (no auth)
just build-localities # write app/src/main/assets/localities.csv from the harvest
```

`harvest_sites_mobil.py` queries Artsobservasjoner's mobile API
(`GET /core/Sites/ByBoundingBox`) over a tiled bounding box of Norway and saves a
flat JSON array of every site — already WGS84, with a per-row county and the
authoritative `isPrivate` flag. No login needed; the harvest is resumable. Extra
`--bbox` passes accumulate into the same file (e.g. to add Svalbard).

`build_sites.py` keeps the **public (allmenn)** sites and writes
`localities.csv` (`id,lokalitet,hovedlokalitet,kommune,fylke,lat,lon,count,observers,radius,geometry,public,mine`).
`hovedlokalitet` is resolved from each site's `parentSiteId`; manual public/private
corrections in `locality_overrides.csv` (keyed by siteId) win over the flag.

Push onto the device with `just push-data` — it overrides the bundled asset, no
rebuild.

**Why the bare name, no coordinates:** verified live (see
`docs/artsobs-import.md`), the paste import matches the **exact registered
`Lokalitetsnavn`** verbatim and links it to the public locality; appending
kommune/fylke hard-fails, and **any coordinate mints a private duplicate**. So the
app emits the bare registered name and no coordinates. Coordinates stay in the
table only for GPS-nearest picking.

The Artsobservasjoner import templates live in `docs/` (`artsobs-template-v2.20.xlsx`,
`artsobs-template-v3.0.xlsx`).
