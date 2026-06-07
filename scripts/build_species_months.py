#!/usr/bin/env python3
"""Harvest per-species monthly report counts for the season-aware search ranking.

For every bird species in the Artsobservasjoner GBIF dataset, fetch how many records
fall in each calendar month (faceting occurrences by month). This is what lets the app
rank "what's reported *now*" - migrants in spring/autumn, winter visitors in winter -
instead of all-time totals. Output is keyed by scientific name so the app joins it to
species.csv (which already carries latin).

    python scripts/build_species_months.py   # -> app/src/main/assets/species_months.csv
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


def get(params):
    for attempt in range(1, 5):
        try:
            r = SESSION.get(BASE, params=params, timeout=60)
            r.raise_for_status()
            return r.json()
        except requests.exceptions.RequestException:
            time.sleep(2 * attempt)
    return None


def facet(field, **extra):
    p = {
        "datasetKey": DATASET,
        "taxonKey": AVES,
        "limit": 0,
        "facet": field,
        "facetLimit": 1000,
    }
    p.update(extra)
    d = get(p) or {"facets": [{"counts": []}]}
    return {c["name"]: c["count"] for c in d["facets"][0]["counts"]}


def resolve(key):
    """speciesKey -> scientific (canonical) name, matching species.csv's latin column."""
    sp = SESSION.get(f"https://api.gbif.org/v1/species/{key}", timeout=60).json()
    return key, (sp.get("canonicalName") or sp.get("scientificName"))


def main() -> int:
    keys = list(facet("speciesKey"))  # every bird speciesKey in the dataset
    print(f"{len(keys)} species; resolving names…", file=sys.stderr)
    with ThreadPoolExecutor(max_workers=16) as ex:
        key_latin = dict(ex.map(resolve, keys))

    # latin -> [m1..m12] counts (summing keys that map to the same name, e.g. merged taxa)
    months = {}
    for m in range(1, 13):
        for key, cnt in facet("speciesKey", month=m).items():
            latin = key_latin.get(key)
            if not latin:
                continue
            months.setdefault(latin, [0] * 12)[m - 1] += cnt
        print(f"\r  month {m}/12", end="", file=sys.stderr)
    print(file=sys.stderr)

    # Emit only species we actually ship (join by latin to the bundled checklist).
    ship = [
        r[1]
        for r in list(csv.reader(open("app/src/main/assets/species.csv")))[1:]
        if len(r) > 1
    ]
    out_path = "app/src/main/assets/species_months.csv"
    rows = 0
    with open(out_path, "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["latin"] + [f"m{i}" for i in range(1, 13)])
        for latin in ship:
            mc = months.get(latin)
            if mc:
                w.writerow([latin] + mc)
                rows += 1
    print(
        f"Wrote {rows}/{len(ship)} species' monthly counts to {out_path}",
        file=sys.stderr,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
