#!/usr/bin/env python3
"""Build the Swedish bird checklist (svensk,latin,status,count) for the Sweden flavor's
species search — the Artportalen counterpart of build_species.py.

Names come from Dyntaxa (SLU Artdatabanken's taxonomy, GBIF dataset de8934f4-…, CC0):
every Aves species with its scientific name + Swedish vernacular (language 'swe'). Ranking
comes from how often each species is reported in Sweden — GBIF occurrence counts faceted by
species over the Artportalen dataset (38b4c89f-…) — so common birds rank first in search, like
the Norwegian list. Join is by the GBIF backbone key (Dyntaxa's `nubKey` == the facet's key).

The `status` column (Swedish Red List) is intentionally left blank for now — not essential for
the Sweden MVP (see issue #127). The Red List 2025 (GBIF 87e639cc-…) can fill it later.

    python scripts/build_species_se.py    # -> species.csv  (place in app/src/sweden/assets/)
"""

import csv
import sys
import time

import requests

GBIF = "https://api.gbif.org/v1"
DYNTAXA = "de8934f4-a136-481c-a87a-b0b202b80a31"  # Dyntaxa checklist on GBIF (CC0)
DYNTAXA_AVES = 159935840  # class Aves *within Dyntaxa* (not the backbone key 212)
ARTPORTALEN = "38b4c89f-584c-41bb-bd8f-cd1def33e92f"  # Artportalen occurrences, for ranking
AVES = 212  # backbone Aves key, for the occurrence facet


def aves_counts():
    """{backbone speciesKey -> Swedish occurrence count}, from one faceted occurrence query."""
    r = requests.get(
        f"{GBIF}/occurrence/search",
        params={
            "datasetKey": ARTPORTALEN, "taxonKey": AVES, "limit": 0,
            "facet": "speciesKey", "facetLimit": 5000,
        },
        timeout=120,
    )
    r.raise_for_status()
    facets = r.json().get("facets", [])
    counts = facets[0]["counts"] if facets else []
    return {int(c["name"]): c["count"] for c in counts}


def dyntaxa_birds():
    """Every Dyntaxa Aves species: (scientificName, swedishName, nubKey). Paginated."""
    out, offset = [], 0
    while True:
        r = requests.get(
            f"{GBIF}/species/search",
            params={
                "datasetKey": DYNTAXA, "highertaxonKey": DYNTAXA_AVES,
                "rank": "SPECIES", "status": "ACCEPTED", "limit": 1000, "offset": offset,
            },
            timeout=120,
        )
        r.raise_for_status()
        d = r.json()
        for rec in d.get("results", []):
            latin = rec.get("species") or rec.get("scientificName", "")
            swe = next(
                (v["vernacularName"] for v in rec.get("vernacularNames", [])
                 if v.get("language") == "swe"),
                "",
            )
            out.append((latin, swe, rec.get("nubKey")))
        if d.get("endOfRecords", True):
            break
        offset += 1000
        time.sleep(0.3)
    return out


def main():
    counts = aves_counts()
    print(f"facet: {len(counts)} species with Swedish occurrences", file=sys.stderr)
    birds = dyntaxa_birds()
    print(f"dyntaxa: {len(birds)} Aves species", file=sys.stderr)

    rows = []
    for latin, swe, nub in birds:
        if not swe or not latin:
            continue  # need both a Swedish name (for search) and a Latin name (for export)
        rows.append((swe, latin, "", counts.get(nub, 0)))
    # Most-reported first (common species reachable without scrolling); ties by name.
    rows.sort(key=lambda r: (-r[3], r[0]))

    w = csv.writer(sys.stdout)
    w.writerow(["svensk", "latin", "status", "count"])
    w.writerows(rows)
    print(f"wrote {len(rows)} species", file=sys.stderr)


if __name__ == "__main__":
    raise SystemExit(main())
