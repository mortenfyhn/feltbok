#!/usr/bin/env python3
"""Build a Swedish locality ("fyndplats") list for the Sweden flavor, region by region.

Smart-and-small: instead of harvesting every observation, GBIF's occurrence facet over the
Artportalen dataset returns the distinct localities in a bounding box ranked by report count
(facet=locality) — i.e. the real hotspots straight away. For each, one tiny occurrence lookup
fetches its stable site id, coordinates and accuracy radius. No bulk dump.

Output matches the bundled localities.csv schema, so the app reads it with no code change.
Ranked by report count (most-reported first) and capped per region with --top.

    python scripts/build_sites_se.py --region gotland --top 30 > localities.csv
    # then place in app/src/sweden/assets/localities.csv
"""

import argparse
import csv
import sys
import time

import requests

GBIF = "https://api.gbif.org/v1/occurrence/search"
ARTPORTALEN = "38b4c89f-584c-41bb-bd8f-cd1def33e92f"
AVES = 212

# (minLat, maxLat, minLon, maxLon) per region the maintainer is visiting (issue #127).
REGIONS = {
    "gotland": (56.85, 58.05, 18.00, 19.45),
    "stockholm": (58.70, 60.25, 17.00, 19.30),
    "jamtland": (61.70, 64.60, 12.10, 16.50),
}


def bbox_params(bbox):
    minlat, maxlat, minlon, maxlon = bbox
    return {
        "datasetKey": ARTPORTALEN,
        "taxonKey": AVES,
        "decimalLatitude": f"{minlat},{maxlat}",
        "decimalLongitude": f"{minlon},{maxlon}",
    }


def top_localities(bbox, n):
    """[(localityName, reportCount)] — the n most-reported distinct localities in the bbox."""
    p = bbox_params(bbox) | {"limit": 0, "facet": "locality", "facetLimit": n}
    r = requests.get(GBIF, params=p, timeout=120)
    r.raise_for_status()
    facets = r.json().get("facets", [])
    return [(c["name"], c["count"]) for c in facets[0]["counts"]] if facets else []


def locality_site(bbox, name):
    """Resolve a locality name to its Artportalen site: (siteId, lat, lon, radius, kommune, fylke).
    Matches the occurrence whose locality is exactly `name` (a q-search returns near-misses too)."""
    p = bbox_params(bbox) | {"q": name, "limit": 20}
    r = requests.get(GBIF, params=p, timeout=120)
    r.raise_for_status()
    for rec in r.json().get("results", []):
        if rec.get("locality") != name:
            continue
        lid = (rec.get("locationID") or "").rsplit(":", 1)[-1]
        lat, lon = rec.get("decimalLatitude"), rec.get("decimalLongitude")
        if not lid or lat is None or lon is None:
            continue
        radius = round(rec.get("coordinateUncertaintyInMeters") or 0) or 100
        return (
            lid,
            lat,
            lon,
            radius,
            rec.get("county") or "",
            rec.get("stateProvince") or "",
        )
    return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--region", choices=REGIONS, required=True)
    ap.add_argument(
        "--top", type=int, default=30, help="keep the N most-reported localities"
    )
    ap.add_argument(
        "--no-header", action="store_true", help="omit header (for appending regions)"
    )
    args = ap.parse_args()

    bbox = REGIONS[args.region]
    names = top_localities(bbox, args.top)
    print(f"{args.region}: {len(names)} candidate localities", file=sys.stderr)

    w = csv.writer(sys.stdout)
    if not args.no_header:
        w.writerow(
            "id lokalitet hovedlokalitet kommune fylke lat lon "
            "count observers radius geometry public mine super".split()
        )
    kept = 0
    for name, count in names:
        site = locality_site(bbox, name)
        time.sleep(0.2)
        if site is None:
            print(f"  ? no exact match for {name!r}", file=sys.stderr)
            continue
        lid, lat, lon, radius, kommune, fylke = site
        w.writerow(
            [
                lid,
                name,
                "",
                kommune,
                fylke,
                f"{lat:.6f}",
                f"{lon:.6f}",
                count,
                count,
                radius,
                "",
                "1",
                "0",
                "0",
            ]
        )
        kept += 1
    print(f"{args.region}: wrote {kept} localities", file=sys.stderr)


if __name__ == "__main__":
    raise SystemExit(main())
