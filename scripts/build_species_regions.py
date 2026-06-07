#!/usr/bin/env python3
"""Harvest per-species report counts on a coarse geographic grid, for locality-aware search ranking.

Instead of fylke names (a mess: localities.csv uses 2024 names with kommune noise, while GBIF mixes
2017/2020/2024 province names with overlapping counts), bucket reports into raw lat/lon cells. The app
works offline, so we precompute the grid and bundle it; at search time it maps the GPS fix to a cell
and ranks "what's reported here". Cells are 1 deg latitude x 2 deg longitude (lon degrees are short
at Norwegian latitudes, so 2 deg keeps cells roughly square-ish).

    python scripts/build_species_regions.py   # -> app/src/main/assets/species_regions.csv
"""

import csv
import sys
import time
from concurrent.futures import ThreadPoolExecutor

import requests

DATASET = "b124e1e0-4755-430f-9eab-894f25a9b59c"
AVES = 212
BASE = "https://api.gbif.org/v1/occurrence/search"
SESSION = requests.Session()

LAT_RANGE = range(57, 72)  # mainland + a margin
LON_STEP = 2
LON_RANGE = range(2, 32, LON_STEP)
MIN_CELL_TOTAL = 200  # skip near-empty cells (ocean / outside Norway)
MIN_COUNT = 5  # drop per-species noise to keep the file small


def get(params):
    for attempt in range(1, 5):
        try:
            r = SESSION.get(BASE, params=params, timeout=60)
            r.raise_for_status()
            return r.json()
        except requests.exceptions.RequestException:
            time.sleep(2 * attempt)
    return None


def species_facet(**extra):
    p = {
        "datasetKey": DATASET,
        "taxonKey": AVES,
        "limit": 0,
        "facet": "speciesKey",
        "facetLimit": 1000,
    }
    p.update(extra)
    d = get(p) or {"count": 0, "facets": [{"counts": []}]}
    return d["count"], {c["name"]: c["count"] for c in d["facets"][0]["counts"]}


def resolve(key):
    sp = SESSION.get(f"https://api.gbif.org/v1/species/{key}", timeout=60).json()
    return key, (sp.get("canonicalName") or sp.get("scientificName"))


def main() -> int:
    _, all_keys = species_facet()
    print(f"{len(all_keys)} species; resolving names…", file=sys.stderr)
    with ThreadPoolExecutor(max_workers=16) as ex:
        key_latin = dict(ex.map(resolve, list(all_keys)))

    ship = {
        r[1]
        for r in list(csv.reader(open("app/src/main/assets/species.csv")))[1:]
        if len(r) > 1
    }

    out_path = "app/src/main/assets/species_regions.csv"
    cells = rows = 0
    with open(out_path, "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(
            ["latS", "lonW", "latin", "count"]
        )  # cell SW corner (int lat, even lon)
        for lat in LAT_RANGE:
            for lon in LON_RANGE:
                total, counts = species_facet(
                    decimalLatitude=f"{lat},{lat + 1}",
                    decimalLongitude=f"{lon},{lon + LON_STEP}",
                )
                if total < MIN_CELL_TOTAL:
                    continue
                cells += 1
                for key, cnt in counts.items():
                    latin = key_latin.get(key)
                    if latin in ship and cnt >= MIN_COUNT:
                        w.writerow([lat, lon, latin, cnt])
                        rows += 1
            print(f"\r  lat {lat}", end="", file=sys.stderr)
    print(f"\nWrote {rows} rows across {cells} cells to {out_path}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
