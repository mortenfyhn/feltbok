#!/usr/bin/env python3
"""Build other_taxa.csv: every bird taxon Artsobservasjoner accepts that isn't a species (#162, #190).

That is subspecies ("gråkråke", "ærfugl, underarten borealis"), hybrids ("brunnakke x stokkand"),
colour morphs ("havhest, lysfaset"), domestic forms, "ub." entries for a genus or a group of similar
species ("ub. svane", "ub. makrell-/rødnebbterne"), families ("kråkefamilien"), orders
("spurvefugler") and the class ("fugler"). The website shows these when "vis underarter og
hybrider" is ticked; the mobile API does the same with includeSubSpecies:

    GET https://mobil.artsobservasjoner.no/core/TaxonName/Search?search=<name>&includeSubSpecies=true
    Header: X-CSRF: 1

A search returns at most 20 hits and finds a taxon by its own names only, so the script searches
for every checklist species and genus (which finds their subspecies, hybrids, morphs and ub.
entries), every family and order those species belong to (from the IOC list, as Artsobservasjoner
doesn't return them for a species search), "ub. a" to "ub. z" (ub. entries of genera that aren't on
the checklist, such as "ub. fregattfugl") and "fugler". It keeps the bird hits that aren't a
species, and warns if a search hits the cap. A taxon with a species' Norwegian name is left out:
Artsobservasjoner calls both Columba livia and Columba livia domestica "bydue".

Writes latin,norsk,taxonId. Leaves out taxa without a Norwegian name (about 200 genera): a bare
Latin genus is noise in a Norwegian list, and most have a "ub." entry.

    python scripts/build_other_taxa.py [--ioc PATH]      # -> other_taxa.csv
"""

import argparse
import csv
import os
import string
import sys
import time

import openpyxl
import requests

URL = "https://mobil.artsobservasjoner.no/core/TaxonName/Search"
ASSETS = "app/src/main/assets"
DEFAULT_IOC = os.path.expanduser("~/workspace/birdnames/data/Multiling IOC 15.2.xlsx")
SPECIES_CATEGORY = 17
DELAY = 0.4  # gentle on Artsobservasjoner


def search(name):
    r = requests.get(
        URL,
        params={"search": name, "includeSubSpecies": "true"},
        headers={"X-CSRF": "1", "User-Agent": "feltbok-other-taxa/1.0"},
        timeout=60,
    )
    r.raise_for_status()
    hits = r.json()
    if len(hits) >= 20:
        print(f'WARNING: "{name}" hit the 20-result cap', file=sys.stderr)
    return [h for h in hits if h.get("speciesGroupId") == 8]


def clean(s):
    """Artsobservasjoner mixes "x" and "×" in hybrids and has stray non-breaking spaces."""
    return " ".join(s.replace("\xa0", " ").replace("×", "x").split()).replace(" ,", ",")


def ioc_groups(path, genera):
    """The IOC families and orders of [genera]."""
    ws = openpyxl.load_workbook(path, read_only=True)["List"]
    groups = set()
    for row in ws.iter_rows(min_row=2, values_only=True):
        order, family, latin = row[1], row[2], row[3]
        if latin and latin.split()[0] in genera:
            groups |= {family, order.capitalize()}
    return groups


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--ioc", default=DEFAULT_IOC)
    args = p.parse_args()

    with open(f"{ASSETS}/species.csv", newline="", encoding="utf-8") as fh:
        rows = list(csv.DictReader(fh))
    species = [row["latin"] for row in rows]
    species_names = {row["norsk"] for row in rows}
    # A few checklist species are another rank on Artsobservasjoner (polarsisik is a subspecies).
    with open(f"{ASSETS}/taxon_ids.csv", newline="", encoding="utf-8") as fh:
        species_ids = {int(row["taxonId"]) for row in csv.DictReader(fh)}
    genera = {s.split()[0] for s in species}
    ub = [f"ub. {c}" for c in string.ascii_lowercase]
    queries = (
        species
        + sorted(genera)
        + sorted(ioc_groups(args.ioc, genera))
        + ub
        + ["fugler"]
    )

    taxa = {}  # taxonId -> (latin, norsk); dedupes hits found by several queries
    for i, q in enumerate(queries, 1):
        for h in search(q):
            norsk = (h.get("vernacularName") or {}).get("name")
            if (
                norsk
                and h["taxonCategoryId"] != SPECIES_CATEGORY
                and h["taxonId"] not in species_ids
            ):
                taxa[h["taxonId"]] = (clean(h["scientificName"]["name"]), clean(norsk))
        print(f"\r  {i}/{len(queries)}: {len(taxa)} taxa", end="", file=sys.stderr)
        time.sleep(DELAY)
    print(file=sys.stderr)

    # The app keys species by Latin name, so a name may appear only once, and not as a species too.
    known = set(species)
    out = []
    for tid, (latin, norsk) in sorted(taxa.items(), key=lambda t: t[1][1]):
        if latin in known or norsk in species_names:
            print(f"Skipped {latin} ({norsk}, {tid}): name taken", file=sys.stderr)
            continue
        known.add(latin)
        out.append((latin, norsk, tid))
    with open("other_taxa.csv", "w", newline="", encoding="utf-8") as fh:
        w = csv.writer(fh)
        w.writerow(["latin", "norsk", "taxonId"])
        w.writerows(out)
    print(f"Wrote {len(out)} taxa to other_taxa.csv", file=sys.stderr)


if __name__ == "__main__":
    main()
