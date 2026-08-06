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

NOTE (#155): the *display/export names* no longer come from here. The bundled species.csv now uses
the unified latin,norsk,svensk,status,count schema, with names sourced from the IOC World Bird List
via scripts/build_species_names.py (the GBIF/Dyntaxa vernaculars this script produced carried real
errors, e.g. "gulsångare" for härmsångare). This script survives only as the Swedish status+count
source that feeds that merge; run merge-species-names afterwards to (re)apply the IOC names.
"""

import csv
import os
import sys
import time

import openpyxl
import requests

ALIEN_XLSX = os.path.join(os.path.dirname(__file__), "se_alien_risk_2024.xlsx")

GBIF = "https://api.gbif.org/v1"
DYNTAXA = "de8934f4-a136-481c-a87a-b0b202b80a31"  # Dyntaxa checklist on GBIF (CC0)
DYNTAXA_AVES = 159935840  # class Aves *within Dyntaxa* (not the backbone key 212)
ARTPORTALEN = (
    "38b4c89f-584c-41bb-bd8f-cd1def33e92f"  # Artportalen occurrences, for ranking
)
AVES = 212  # backbone Aves key, for the occurrence facet
REDLIST = "87e639cc-30a9-4007-bd2c-b0cab60326b9"  # The Swedish Red List 2025 (rödlistade arter)

# Dyntaxa stores a handful of Norwegian (nob) names as two names mashed into one string (a data
# error, e.g. "Furusanger Bonellisanger"). Correct those by scientific name. Genuine two-word names
# (e.g. "svarthvit fluesnapper") are left alone — only these specific entries are overridden.
NOB_OVERRIDE = {
    "Ardea cinerea": "gråhegre",
    "Pluvialis fulva": "sibirlo",
    "Pluvialis dominica": "kanadalo",  # verify
    "Phylloscopus bonelli": "bonellisanger",
    "Phylloscopus orientalis": "furusanger",
    "Ptyonoprogne rupestris": "klippesvale",
    "Bucanetes githagineus": "trompeterfink",
    "Cettia cetti": "cettisanger",  # Dyntaxa has "cettis sanger" (two tokens)
    "Limosa haemastica": "hudsonspove",
    "Falco eleonorae": "eleonorafalk",
    "Porzana carolina": "karolinarikse",
    "Coloeus dauuricus": "mongolkaie",  # verify
    "Caprimulgus aegyptius": "egyptisk nattravn",
}

# IUCN status (full word -> the short code StatusBadge renders). LC/NA/NE aren't in the red-list
# dataset (it only carries the red-listed categories), so anything else maps to no badge.
REDLIST_CODE = {
    "REGIONALLY_EXTINCT": "RE",
    "CRITICALLY_ENDANGERED": "CR",
    "ENDANGERED": "EN",
    "VULNERABLE": "VU",
    "NEAR_THREATENED": "NT",
    "DATA_DEFICIENT": "DD",
}


def redlist_statuses():
    """{scientificName -> short IUCN code} from the Swedish Red List 2025. The dataset spans all
    groups and carries only red-listed taxa, so we keep the accepted ones and join by name."""
    out, offset = {}, 0
    while True:
        r = requests.get(
            f"{GBIF}/species/search",
            params={
                "datasetKey": REDLIST,
                "rank": "SPECIES",
                "status": "ACCEPTED",
                "limit": 1000,
                "offset": offset,
            },
            timeout=120,
        )
        r.raise_for_status()
        d = r.json()
        for rec in d.get("results", []):
            code = next(
                (
                    REDLIST_CODE[s]
                    for s in rec.get("threatStatuses", [])
                    if s in REDLIST_CODE
                ),
                None,
            )
            name = rec.get("canonicalName") or rec.get("species")
            if code and name:
                out[name] = code
        if d.get("endOfRecords", True):
            break
        offset += 1000
        time.sleep(0.3)
    return out


def alien_statuses():
    """{scientificName -> GEIAA risk code (SE/HI/PH/LO/NK)} for alien birds, from the committed
    artfakta export (se_alien_risk_2024.xlsx). This grade isn't in any GBIF dataset, so it's a manual
    download of artfakta.se's risk classification (Riskklassning 2024) filtered to birds."""
    ws = openpyxl.load_workbook(ALIEN_XLSX, data_only=True).active
    rows = list(ws.iter_rows(values_only=True))
    hdr = [str(c) for c in rows[0]]
    i_latin = next(i for i, h in enumerate(hdr) if "Vetenskapligt namn" in h)
    i_risk = next(i for i, h in enumerate(hdr) if "GEIAA" in h)
    out = {}
    for r in rows[1:]:
        if r[i_latin] and r[i_risk]:
            out[str(r[i_latin]).strip()] = str(r[i_risk]).strip()
    return out


def aves_counts():
    """{backbone speciesKey -> Swedish occurrence count}, from one faceted occurrence query."""
    r = requests.get(
        f"{GBIF}/occurrence/search",
        params={
            "datasetKey": ARTPORTALEN,
            "taxonKey": AVES,
            "limit": 0,
            "facet": "speciesKey",
            "facetLimit": 5000,
        },
        timeout=120,
    )
    r.raise_for_status()
    facets = r.json().get("facets", [])
    counts = facets[0]["counts"] if facets else []
    return {int(c["name"]): c["count"] for c in counts}


def _vernacular(rec, lang):
    return next(
        (
            v["vernacularName"]
            for v in rec.get("vernacularNames", [])
            if v.get("language") == lang
        ),
        "",
    )


def dyntaxa_birds():
    """Every Dyntaxa Aves species: (scientificName, swedishName, norwegianName, nubKey). Paginated.
    The Norwegian (nob) name is carried as a secondary searchable name in the Swedish build."""
    out, offset = [], 0
    while True:
        r = requests.get(
            f"{GBIF}/species/search",
            params={
                "datasetKey": DYNTAXA,
                "highertaxonKey": DYNTAXA_AVES,
                "rank": "SPECIES",
                "status": "ACCEPTED",
                "limit": 1000,
                "offset": offset,
            },
            timeout=120,
        )
        r.raise_for_status()
        d = r.json()
        for rec in d.get("results", []):
            latin = rec.get("species") or rec.get("scientificName", "")
            out.append(
                (
                    latin,
                    _vernacular(rec, "swe"),
                    _vernacular(rec, "nob"),
                    rec.get("nubKey"),
                )
            )
        if d.get("endOfRecords", True):
            break
        offset += 1000
        time.sleep(0.3)
    return out


def main():
    counts = aves_counts()
    print(f"facet: {len(counts)} species with Swedish occurrences", file=sys.stderr)
    redlist = redlist_statuses()
    print(f"red list: {len(redlist)} red-listed taxa", file=sys.stderr)
    alien = alien_statuses()
    print(f"alien list: {len(alien)} alien bird taxa", file=sys.stderr)
    birds = dyntaxa_birds()
    print(f"dyntaxa: {len(birds)} Aves species", file=sys.stderr)

    rows = []
    for latin, swe, nob, nub in birds:
        if not swe or not latin:
            continue  # need both a Swedish name (for search) and a Latin name (for export)
        # Drop hybrids and subspecies/races so the list is species-level, like the Norwegian one.
        if " x " in swe.lower() or " x " in latin.lower():
            continue
        if len(latin.split()) >= 3 or any(
            m in swe for m in (", rasen", ", underarten", ", formen")
        ):
            continue
        # Red-list code if native and threatened, else the alien GEIAA risk grade if introduced.
        status = redlist.get(latin) or alien.get(latin, "")
        # Correct the few mashed Norwegian names; lowercase the rest (Norwegian names are lowercase).
        nob = NOB_OVERRIDE.get(latin, nob).lower()
        rows.append((swe, latin, status, counts.get(nub, 0), nob))
    # Most-reported first (common species reachable without scrolling); ties by name.
    rows.sort(key=lambda r: (-r[3], r[0]))

    w = csv.writer(sys.stdout)
    # status = Red List 2025 code (RE/CR/EN/VU/NT/DD) or, for alien species, the GEIAA risk
    # grade (SE/HI/PH/LO/NK); blank otherwise. StatusBadge colours both.
    w.writerow(["svensk", "latin", "status", "count", "norsk"])
    w.writerows(rows)
    n_nob = sum(1 for r in rows if r[4])
    n_rl = sum(1 for r in rows if r[2] in REDLIST_CODE.values())
    n_al = sum(1 for r in rows if r[2] and r[2] not in REDLIST_CODE.values())
    print(
        f"wrote {len(rows)} species ({n_nob} Norwegian names, {n_rl} red-listed, {n_al} alien)",
        file=sys.stderr,
    )


if __name__ == "__main__":
    raise SystemExit(main())
