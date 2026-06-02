#!/usr/bin/env python3
"""Build the Norwegian bird checklist (norsk,latin) for the app's species search.

Lists every bird species ever recorded in Artsobservasjoner (GBIF dataset
b124e1e0-…, taxonKey 212 = Aves) and resolves each to its Norwegian + scientific
name via the GBIF backbone vernacular names (language 'nor', then 'nno'). Sorted
most-observed first, so common species rank first in search.

    python process/build_species.py      # -> species.csv  (then: just push-data, or rebundle)
"""
import csv
import sys
import time

import requests

DATASET = "b124e1e0-4755-430f-9eab-894f25a9b59c"
AVES = 212
NORTAXA = "a6c6cead-b5ce-4a4e-8cf5-1542ba708dec"  # Artsdatabanken's Norwegian name base

# Manual Bokmål names for species the lookups miss - mostly where this dataset
# files a bird under an old genus (Sylvia) that Artsnavnebasen only lists under
# the current one (Curruca), so the name-match fails. Only confident regulars;
# genuine rare vagrants are left as their scientific name.
OVERRIDES = {
    "Sylvia communis": "Tornsanger",
    "Sylvia curruca": "Møller",
    "Sylvia nana": "Dvergsanger",
    "Sylvia crassirostris": "Sultansanger",
    "Corvus corone": "Svartkråke",
    "Lanius isabellinus": "Isabellavarsler",
    "Acanthis hornemanni": "Polarsisik",
}


def nortaxa_name(latin):
    """Norwegian name from Artsnavnebasen - Artsobservasjoner uses the same base.
    Prefer Bokmål (nob, what Artsobs reports) over Nynorsk (nno)."""
    d = get("https://api.gbif.org/v1/species/search",
            {"datasetKey": NORTAXA, "q": latin, "qField": "SCIENTIFIC", "limit": 30}) or {}
    nob = nor = nno = None
    for r in d.get("results", []):
        if r.get("canonicalName") != latin:
            continue
        for v in r.get("vernacularNames", []):
            lang, nm = v.get("language"), v["vernacularName"]
            if lang == "nob" and not nob:
                nob = nm
            elif lang == "nor" and not nor:
                nor = nm
            elif lang == "nno" and not nno:
                nno = nm
    return nob or nor or nno


def get(url, params=None):
    for attempt in range(1, 5):
        try:
            r = requests.get(url, params=params, timeout=60)
            r.raise_for_status()
            return r.json()
        except requests.exceptions.RequestException:
            time.sleep(2 * attempt)
    return None


def species_keys():
    """(speciesKey, record-count) for every bird species in the dataset."""
    d = get("https://api.gbif.org/v1/occurrence/search",
            params={"datasetKey": DATASET, "taxonKey": AVES,
                    "facet": "speciesKey", "facetLimit": 1000, "limit": 0})
    return [(c["name"], c["count"]) for c in d["facets"][0]["counts"]]


def resolve(key):
    """(norsk, latin) for a speciesKey, or None to skip."""
    sp = get(f"https://api.gbif.org/v1/species/{key}")
    if not sp or sp.get("rank") != "SPECIES":
        return None
    latin = sp.get("canonicalName") or sp.get("scientificName")
    if not latin:
        return None
    # Artsnavnebasen (Bokmål) first - it matches Artsobservasjoner. The GBIF
    # backbone vernacular is an inconsistent Bokmål/Nynorsk mix, so use it only
    # for species Artsnavnebasen doesn't cover.
    norsk = nortaxa_name(latin)
    if not norsk:
        vn = get(f"https://api.gbif.org/v1/species/{key}/vernacularNames", {"limit": 200}) or {}
        by_lang: dict = {}
        for v in vn.get("results", []):
            by_lang.setdefault(v.get("language"), v.get("vernacularName"))
        norsk = by_lang.get("nor") or by_lang.get("nno") or ""
    if norsk:                                  # Artsobs capitalises the first letter
        norsk = norsk[:1].upper() + norsk[1:]
    return norsk or "", latin


def main() -> int:
    keys = species_keys()
    print(f"{len(keys)} bird species; resolving names…", file=sys.stderr)
    rows = []
    gaps = 0
    for i, (key, count) in enumerate(keys):
        res = resolve(key)
        if not res:
            continue
        norsk, latin = res
        if not norsk:
            gaps += 1
        rows.append((norsk, latin, count))
        if i % 50 == 0:
            print(f"\r  {i}/{len(keys)}", end="", file=sys.stderr)
    print(file=sys.stderr)

    seen = set()
    out = []
    for norsk, latin, _ in sorted(rows, key=lambda r: -r[2]):  # most-observed first
        if latin in seen:
            continue
        seen.add(latin)
        # override > resolved name > scientific name
        out.append((OVERRIDES.get(latin) or norsk or latin, latin))
    with open("species.csv", "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["norsk", "latin"])
        w.writerows(out)
    print(f"Wrote {len(out)} bird species ({gaps} without a Norwegian name) to species.csv",
          file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
