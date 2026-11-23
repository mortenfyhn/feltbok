#!/usr/bin/env python3
"""Build taxon_ids.csv (latin,taxonId): Artsobservasjoner's taxon id for every scientific name in
species.csv. The upload sends a sighting by taxon id, so the app must know them. The other taxa's ids
come with them in other_taxa.csv (scripts/build_other_taxa.py).

    GET https://mobil.artsobservasjoner.no/core/TaxonName/Search?search=<name>&includeSubSpecies=true
    Header: X-CSRF: 1

Takes the bird hit whose scientific name, or one of its scientific synonyms, is the latin exactly.
Without one, the hit whose Norwegian name is the checklist's: what the paste import matched on. A
few checklist species are subspecies on Artsobservasjoner (polarsisik is Acanthis flammea
exilipes), so includeSubSpecies matters. Fails if a name has no single hit: then fixing the
checklist comes first. Lists the matches whose Latin or Norwegian name differs from the
checklist's, to check by hand: the checklist should use Artsobservasjoner's names. Run after
rebuilding species.csv.

    python scripts/build_taxon_ids.py      # -> taxon_ids.csv
"""

import csv
import sys
import time

import requests

URL = "https://mobil.artsobservasjoner.no/core/TaxonName/Search"
ASSETS = "app/src/main/assets"
DELAY = 0.3  # gentle on Artsobservasjoner


def search(name):
    r = requests.get(
        URL,
        params={"search": name, "includeSubSpecies": "true"},
        headers={"X-CSRF": "1", "User-Agent": "feltbok-taxon-ids/1.0"},
        timeout=60,
    )
    r.raise_for_status()
    return [h for h in r.json() if h.get("speciesGroupId") == 8]


def taxon_ids(latin, norsk):
    """{id: (Norwegian, scientific name)} of the hits that match, by scientific name first, then
    Norwegian name."""

    def scientific(h):
        names = [h.get("scientificName") or {}] + (
            h.get("scientificNameSynonyms") or []
        )
        return latin.lower() in (n.get("name", "").lower() for n in names)

    def vern(h):
        return (h.get("vernacularName") or {}).get("name", "")

    def names(h):
        return vern(h), (h.get("scientificName") or {}).get("name", "")

    hits = {h["taxonId"]: names(h) for h in search(latin) if scientific(h)}
    if not hits and norsk:
        time.sleep(DELAY)
        hits = {
            h["taxonId"]: names(h)
            for h in search(norsk)
            if vern(h).lower() == norsk.lower()
        }
    return hits


def main():
    with open(f"{ASSETS}/species.csv", newline="", encoding="utf-8") as fh:
        names = [(row["latin"], row["norsk"]) for row in csv.DictReader(fh)]
    out, bad, differ = [], [], []
    for i, (latin, norsk) in enumerate(names, 1):
        hits = taxon_ids(latin, norsk)
        if len(hits) == 1:
            ((tid, (name, sci)),) = hits.items()
            out.append((latin, tid))
            if name.lower() != norsk.lower() or sci != latin:
                differ.append(f"{latin} {norsk} -> {tid} {sci} {name}")
        else:
            bad.append(f"{latin} ({norsk}): {sorted(hits) or 'not found'}")
        if i % 50 == 0:
            print(f"{i}/{len(names)}", file=sys.stderr)
        time.sleep(DELAY)
    if differ:
        print(
            "Name differs, check by hand:\n  " + "\n  ".join(differ),
            file=sys.stderr,
        )
    if bad:
        sys.exit("No single bird taxon for:\n  " + "\n  ".join(bad))
    with open("taxon_ids.csv", "w", newline="", encoding="utf-8") as fh:
        w = csv.writer(fh)
        w.writerow(["latin", "taxonId"])
        w.writerows(out)


if __name__ == "__main__":
    main()
