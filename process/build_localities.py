#!/usr/bin/env python3
"""Build a locality gazetteer from open Artskart bird observations.

Run once (or occasionally) to populate localities_gazetteer.csv, which
process.py uses to label rows with established locality names so the import
links to existing localities instead of creating new points.

It queries the open Artskart API for bird records (Institution "Birdlife Norge",
i.e. the Artsobservasjoner bird data) inside a bounding box, and aggregates the
distinct localities. Artskart's `Locality` is a composite — the real locality
name is the part before the first comma (the rest is admin context):

    "Utnesvatnet, Lensvik, Orkland, Tø"  ->  "Utnesvatnet"

Example:
    python process/build_localities.py --bbox 10.0,63.3,10.7,63.5
"""
import argparse
import csv
import os
import statistics
import sys

import requests

API = "https://artskart.artsdatabanken.no/publicapi/api/observations/list"
# In Artskart, the Artsobservasjoner bird data is attributed to BirdLife Norge.
BIRD_INSTITUTION = "Birdlife Norge"


def wkt_box(minlon, minlat, maxlon, maxlat) -> str:
    return (f"POLYGON(({minlon} {minlat},{maxlon} {minlat},{maxlon} {maxlat},"
            f"{minlon} {maxlat},{minlon} {minlat}))")


def fetch(bbox, from_date, institution, max_pages, page_size=1000):
    params = {
        "pageSize": page_size, "crs": "EPSG:4326", "filter.crs": "EPSG:4326",
        "filter.wktPolygon": wkt_box(*bbox), "filter.fromDate": from_date,
    }
    page = 0
    while True:
        params["pageIndex"] = page
        r = requests.get(API, params=params, timeout=90,
                         headers={"User-Agent": "appobs-localities/1.0"})
        r.raise_for_status()
        d = r.json()
        for o in d.get("Observations", []):
            if institution and o.get("Institution") != institution:
                continue
            yield o
        total = d.get("TotalPages", 0)
        page += 1
        print(f"  page {page}/{total}", file=sys.stderr)
        if page >= total or page >= max_pages:
            if page >= max_pages < total:
                print(f"  (stopped at --max-pages {max_pages} of {total})", file=sys.stderr)
            break


def load_csv_counts(path):
    """Read a previously written gazetteer/species CSV: name -> (lat, lon, count)."""
    seed = {}
    if not os.path.exists(path):
        return seed
    with open(path, newline="", encoding="utf-8") as f:
        for r in csv.reader(f):
            if not r or r[0].strip().lower() in ("name", "navn"):
                continue
            try:
                if len(r) >= 4:      # gazetteer: name,lat,lon,count
                    seed[r[0].strip()] = (float(r[1]), float(r[2]), int(r[3]))
                else:                # species: name,count
                    seed[r[0].strip()] = int(r[1])
            except (IndexError, ValueError):
                pass
    return seed


def write_gazetteer(path, agg, min_count, seed=None) -> int:
    rows = {name: (round(statistics.median(la), 6), round(statistics.median(lo), 6), len(la))
            for name, (la, lo) in agg.items() if len(la) >= min_count}
    for name, val in (seed or {}).items():   # keep prior regions' localities
        rows.setdefault(name, val)
    out = sorted(([name, *v] for name, v in rows.items()), key=lambda r: -r[3])
    with open(path, "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["name", "lat", "lon", "count"])
        w.writerows(out)
    return len(out)


def write_species(path, species) -> int:
    rows = sorted(species.items(), key=lambda r: -r[1])  # most-observed first
    with open(path, "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["name", "count"])
        w.writerows(rows)
    return len(rows)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--bbox", default="10.0,63.3,10.7,63.5",
                    help="minlon,minlat,maxlon,maxlat (default: Trondheim area)")
    ap.add_argument("--from-date", default="2018-01-01",
                    help="earliest observation date (default: %(default)s)")
    ap.add_argument("--institution", default=BIRD_INSTITUTION,
                    help="source institution to keep; '' for all sources")
    ap.add_argument("--min-count", type=int, default=2,
                    help="drop localities seen fewer times (default: %(default)s)")
    ap.add_argument("--max-pages", type=int, default=400,
                    help="safety cap on pages of 1000 (default: %(default)s)")
    ap.add_argument("-o", "--output", default="localities_gazetteer.csv")
    ap.add_argument("--species-output", default="bird_species.csv",
                    help="also write the distinct bird names seen (default: %(default)s)")
    ap.add_argument("--append", action="store_true",
                    help="merge into existing output files instead of replacing "
                         "them (use to add a new region to the gazetteer)")
    args = ap.parse_args()

    bbox = tuple(float(x) for x in args.bbox.split(","))
    agg: dict[str, tuple[list, list]] = {}
    # In append mode, seed from prior regions so they're preserved.
    gaz_seed = load_csv_counts(args.output) if args.append else None
    species: dict[str, int] = load_csv_counts(args.species_output) if args.append else {}
    if args.append:
        print(f"Appending to {len(gaz_seed)} existing localities, "
              f"{len(species)} species.", file=sys.stderr)
    scanned = 0
    # Deep pagination gets slow on big result sets, so we checkpoint: the output
    # files are rewritten periodically and stay usable if you stop the run early.
    CHECKPOINT = 20_000
    try:
        for o in fetch(bbox, args.from_date, args.institution or None, args.max_pages):
            sp = (o.get("Name") or "").strip()
            if sp:
                species[sp] = species.get(sp, 0) + 1
            name = (o.get("Locality") or "").split(",")[0].strip()
            if not name:
                continue
            try:
                lat, lon = float(o["North"]), float(o["East"])
            except (KeyError, TypeError, ValueError):
                continue
            lats, lons = agg.setdefault(name, ([], []))
            lats.append(lat)
            lons.append(lon)
            scanned += 1
            if scanned % CHECKPOINT == 0:
                n = write_gazetteer(args.output, agg, args.min_count, gaz_seed)
                write_species(args.species_output, species)
                print(f"  checkpoint: {scanned} obs -> {n} localities, "
                      f"{len(species)} species", file=sys.stderr)
    except KeyboardInterrupt:
        print("\nInterrupted — writing partial output.", file=sys.stderr)

    n = write_gazetteer(args.output, agg, args.min_count, gaz_seed)
    s = write_species(args.species_output, species)
    print(f"Scanned {scanned} bird obs -> {n} localities "
          f"(>= {args.min_count} obs) to {args.output}, {s} species to {args.species_output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
