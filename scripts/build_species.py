#!/usr/bin/env python3
"""Build the Norwegian bird checklist (norsk,latin,status) for the app's species search.

Lists every bird species ever recorded in Artsobservasjoner (GBIF dataset
b124e1e0-…, taxonKey 212 = Aves) and resolves each to its Norwegian name via
Artsdatabanken's Taxon API — the same name authority Artsobservasjoner uses on
import, so the names match what the portal accepts (the GBIF backbone vernacular
is only a fallback for the few vagrants Artsnavnebasen has no name for). Sorted
most-observed first, so common species rank first in search. The `status` column
carries the Norwegian Red List 2021 category (mainland) for red-listed species, or
the Alien Species List 2023 risk category (SE/HI/PH/LO/NK) for introduced species.

    python scripts/build_species.py      # -> species.csv  (then: just push-data, or rebundle)
"""

import csv
import html as _html
import re
import sys
import time
from urllib.parse import urlsplit

import requests

DATASET = "b124e1e0-4755-430f-9eab-894f25a9b59c"
AVES = 212

# This checklist inherits the GBIF backbone's older genus for a few species; Artsobservasjoner and
# its name authority have since moved them. Map to the current scientific name so both the name
# lookup and the emitted latin match what the portal accepts.
SCI_ALIASES = {
    "Oceanodroma leucorhoa": "Hydrobates leucorhous",  # stormsvale
    "Oceanodroma monorhis": "Hydrobates monorhis",  # japanstormsvale
}

# Norwegian Red List 2021 (Artsdatabanken), mainland (Area=N) bird assessments.
# Only red-listed categories are kept for display; LC/NA/NE map to "".
REDLIST_URL = (
    "https://lister.artsdatabanken.no/rodlisteforarter/2021?SpeciesGroups=Fugler&Area=N"
)
REDLISTED = {"RE", "CR", "EN", "VU", "NT", "DD"}


# Alien Species List 2023 (Artsdatabanken) bird assessments. The ecological-risk
# categories, worst first; NR (not risk-assessed) is dropped, like LC for the red list.
ALIENLIST_URL = (
    "https://lister.artsdatabanken.no/fremmedartslista/2023?SpeciesGroups=Aves"
)
ALIEN_CATS = {"SE", "HI", "PH", "LO", "NK"}


def _scrape_categories(url, categories, label):
    """{scientific name -> category} scraped from an Artsdatabanken list, page by page.
    Both lists render the same row markup: an <i> scientific name + a risk-category circle."""
    # Match each species' own detail anchor (/<list>/<year>/<id>), not just any link ending in
    # digits - else nav/footer links keep `rows` non-empty past the last page and we never break.
    base = urlsplit(url).path
    rowre = re.compile(rf'<a[^>]*href="{re.escape(base)}/\d+".*?</a>', re.S)
    latre = re.compile(r"element_scientific_name[^>]*><i>([^<]+)</i>")
    catre = re.compile(r'class="([A-Z]{2}) risk-category-circle"')
    out, page = {}, 1
    while True:
        r = requests.get(
            f"{url}&Page={page}",
            headers={"User-Agent": f"feltbok-{label}/1.0"},
            timeout=60,
        )
        r.raise_for_status()
        rows = rowre.findall(r.text)
        if not rows:
            break
        for blob in rows:
            lat, cat = latre.search(blob), catre.search(blob)
            if lat and cat and cat.group(1) in categories:
                # The list and the checklist both key on Artsdatabanken's accepted name, so they match.
                out[_html.unescape(lat.group(1)).strip()] = cat.group(1)
        page += 1
        time.sleep(0.6)  # gentle on Artsdatabanken
    return out


def fetch_redlist():
    """{scientific name -> Red List 2021 category} for red-listed mainland birds."""
    return _scrape_categories(REDLIST_URL, REDLISTED, "redlist")


def fetch_alienlist():
    """{scientific name -> Alien Species List 2023 risk category} for introduced birds."""
    return _scrape_categories(ALIENLIST_URL, ALIEN_CATS, "alienlist")


# Deliberate divergence from Artsdatabanken's preferred name: Artsnavnebasen calls Columba livia
# "klippedue" (the species as a whole), but Norway only has the feral form, so Artsobservasjoner
# only accepts "bydue" on import. Everything else resolves correctly through the authoritative
# lookup, so no other manual names are needed.
OVERRIDES = {
    "Columba livia": "bydue",
}


def artsdatabanken_taxon(latin):
    """(accepted scientific name, preferred Norwegian name) from Artsdatabanken's Taxon API - the
    same authority Artsobservasjoner uses on import. Modernizes an outdated genus to the currently
    accepted binomial (so the name matches the portal and joins the Swedish list, which already uses
    the new genera), but keeps our species-level name where Artsdatabanken has since lumped it into
    a subspecies. The Norwegian name is the vernacular flagged `preferred` (Bokmål, else Nynorsk).
    Returns (latin unchanged, None) when the name is unknown or the taxon carries no Norwegian name."""
    hits = get(
        "https://artsdatabanken.no/api/Taxon/ScientificName", {"scientificName": latin}
    )
    if not hits:
        return latin, None
    accepted = hits[0].get("acceptedNameUsage") or {}
    if accepted.get("taxonRank") == "species":
        latin = accepted["scientificName"]
    taxon = get(f"https://artsdatabanken.no/api/Taxon/{hits[0]['taxonID']}") or {}
    names = taxon.get("vernacularNames") or []

    def preferred(lang):
        return next(
            (
                v["vernacularName"]
                for v in names
                if v.get("language") == lang
                and v.get("nomenclaturalStatus") == "preferred"
            ),
            None,
        )

    return latin, preferred("nb-NO") or preferred("nn-NO")


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
    d = get(
        "https://api.gbif.org/v1/occurrence/search",
        params={
            "datasetKey": DATASET,
            "taxonKey": AVES,
            "facet": "speciesKey",
            "facetLimit": 1000,
            "limit": 0,
        },
    )
    return [(c["name"], c["count"]) for c in d["facets"][0]["counts"]]


def resolve(key):
    """(norsk, latin) for a speciesKey, or None to skip."""
    sp = get(f"https://api.gbif.org/v1/species/{key}")
    if not sp or sp.get("rank") != "SPECIES":
        return None
    latin = sp.get("canonicalName") or sp.get("scientificName")
    if not latin:
        return None
    latin = SCI_ALIASES.get(latin, latin)
    # Artsdatabanken (Artsnavnebasen) is authoritative for both the accepted scientific name and the
    # Norwegian name, and matches Artsobservasjoner. Its API is missing a name for a few rare
    # vagrants, so fall back to the GBIF backbone vernacular (a Bokmål/Nynorsk mix) only there.
    latin, norsk = artsdatabanken_taxon(latin)
    if not norsk:
        vn = (
            get(
                f"https://api.gbif.org/v1/species/{key}/vernacularNames", {"limit": 200}
            )
            or {}
        )
        by_lang: dict = {}
        for v in vn.get("results", []):
            by_lang.setdefault(v.get("language"), v.get("vernacularName"))
        norsk = by_lang.get("nor") or by_lang.get("nno") or ""
    if norsk:  # Bokmål species names are lowercase (Artsdatabanken orthography)
        norsk = norsk[:1].lower() + norsk[1:]
    return norsk or "", latin


def main() -> int:
    redlist = fetch_redlist()
    print(f"{len(redlist)} red-listed mainland birds (Rødlista 2021)", file=sys.stderr)
    alienlist = fetch_alienlist()
    print(
        f"{len(alienlist)} risk-assessed alien birds (Fremmedartslista 2023)",
        file=sys.stderr,
    )
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
    for norsk, latin, count in sorted(rows, key=lambda r: -r[2]):  # most-observed first
        if latin in seen:
            continue
        seen.add(latin)
        # override > resolved name > scientific name; red list wins over alien list
        # (native red-listed and introduced alien are disjoint in practice anyway).
        status = redlist.get(latin) or alienlist.get(latin, "")
        # Drop species we couldn't give a Norwegian name (and hybrids, which never get one): the app
        # searches by Norwegian name, so a bare scientific name is unsearchable noise in the results.
        name = OVERRIDES.get(latin) or norsk
        if not name or " x " in latin:
            continue
        # count = Norway-wide observation count; the search uses it (log-scaled) to rank common
        # birds above rarities. The most-observed-first row order already reflects it; keeping the
        # raw number lets the ranker weight by the real (very skewed) magnitudes, not just rank.
        out.append((name, latin, status, count))
    with open("species.csv", "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["norsk", "latin", "status", "count"])
        w.writerows(out)
    print(
        f"Wrote {len(out)} bird species ({gaps} without a Norwegian name) to species.csv",
        file=sys.stderr,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
