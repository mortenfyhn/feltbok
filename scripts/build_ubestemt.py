#!/usr/bin/env python3
"""Build the "ubestemt" (ub.) checklist entries for the app's species search (issue #162).

Artsobservasjoner lets you log a sighting as "ub. X" ("ubestemt", unidentified) when you can
narrow a bird down to a genus (e.g. "ub. svane" -> Cygnus) or a small group of similar species
(e.g. "ub. makrell-/rødnebbterne" -> Sterna hirundo/paradisaea) but not to the exact species.
These are real taxon entries in Artsobservasjoner's own database, fetched from the same
unauthenticated mobile-API surface `harvest_sites_mobil.py` uses for localities.

    GET https://mobil.artsobservasjoner.no/core/TaxonName/Search?Search=ub. <letter>
    Header: X-CSRF: 1

The endpoint hard-caps results at 20 and ignores every limit/page parameter tried (MaxHits,
Take, Limit, PageSize, Top) - so full coverage comes from enumerating "ub. a" through "ub. z"
(verified: every letter returns well under 20 hits). Results are filtered to speciesGroup
"Birds" and taxonCategoryId 14 (genus-level) or 28 (species-complex) - both are wanted.

    python scripts/build_ubestemt.py      # -> ubestemt.csv
"""

import string
import sys
import time

import requests

URL = "https://mobil.artsobservasjoner.no/core/TaxonName/Search"
WANTED_CATEGORIES = {14, 28}
DELAY = 0.6  # gentle on Artsobservasjoner, same courtesy as the other harvest scripts


def fetch_letter(letter):
    """Every "ub. <letter>…" taxon hit, filtered to birds in a wanted category."""
    r = requests.get(
        URL,
        params={"Search": f"ub. {letter}"},
        headers={"X-CSRF": "1", "User-Agent": "feltbok-ubestemt/1.0"},
        timeout=60,
    )
    r.raise_for_status()
    hits = r.json()
    if len(hits) >= 20:
        print(
            f'WARNING: "ub. {letter}" hit the 20-result cap - results may be truncated, '
            "coverage can no longer be assumed complete",
            file=sys.stderr,
        )
    return [
        hit
        for hit in hits
        if hit.get("speciesGroup") == "Birds"
        and hit.get("taxonCategoryId") in WANTED_CATEGORIES
    ]


def main() -> int:
    seen = {}  # taxonId -> (norsk, latin), dedupes hits shared across letter queries
    for letter in string.ascii_lowercase:
        for hit in fetch_letter(letter):
            norsk = hit["vernacularName"]["name"]
            latin = hit["scientificName"]["name"]
            seen[hit["taxonId"]] = (norsk, latin)
        print(f"\r  {letter}: {len(seen)} so far", end="", file=sys.stderr)
        time.sleep(DELAY)
    print(file=sys.stderr)

    rows = sorted(seen.values(), key=lambda r: r[0])  # alphabetical by norsk name
    with open("ubestemt.csv", "w", newline="", encoding="utf-8") as f:
        f.write("latin,norsk\n")
        for norsk, latin in rows:
            f.write(
                f'"{latin}","{norsk}"\n'
                if "," in latin or "," in norsk
                else f"{latin},{norsk}\n"
            )
    print(f"Wrote {len(rows)} ub. entries to ubestemt.csv", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
