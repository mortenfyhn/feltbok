#!/usr/bin/env python3
"""Build the official Artsobservasjoner locality table.

Source: the Norwegian Species Observation Service Darwin Core Archive, published
by Artsdatabanken (GBIF dataset b124e1e0-4755-430f-9eab-894f25a9b59c). Every
record carries the registry's *qualified* locality name, its stable site id
(`locationID`), the site's canonical coordinates, and kommune/fylke — so one
row per `locationID` reconstructs the locality registry itself.

We need the qualified name because that is exactly what Artsobservasjoner's
name-based import matches on: "Ørndalen, Sistranda, Frøya, Tø", never bare
"Ørndalen". (Import *with* coordinates instead creates a new locality per point,
which is the duplicate mess we're avoiding — so the app uploads names only.)

Usage:
    python process/build_localities.py                  # download + build national table
    python process/build_localities.py --archive a.zip  # use a pre-downloaded archive
    python process/build_localities.py --county Trøndelag   # one fylke only
    python process/build_localities.py --min-count 3    # drop rarely-used (likely private) sites

Output: localities.csv  (id,name,lat,lon,kommune,fylke,count), most-used first.
"""
import argparse
import csv
import io
import os
import sys
import xml.etree.ElementTree as ET
import zipfile

import requests

# Artsdatabanken's IPT serves the Darwin Core Archive directly (a ~3 GB zip).
ARCHIVE_URL = "https://ipt.artsdatabanken.no/archive.do?r=speciesobservationsservice2"
DEFAULT_ARCHIVE = "artsobs-dwca.zip"

# Darwin Core terms we keep, by their full term URI (meta.xml maps these to
# column indices in the data file).
TERMS = {
    "locationID": "http://rs.tdwg.org/dwc/terms/locationID",
    "locality": "http://rs.tdwg.org/dwc/terms/locality",
    "lat": "http://rs.tdwg.org/dwc/terms/decimalLatitude",
    "lon": "http://rs.tdwg.org/dwc/terms/decimalLongitude",
    "kommune": "http://rs.tdwg.org/dwc/terms/municipality",
    "fylke": "http://rs.tdwg.org/dwc/terms/county",
}


def download(url: str, dest: str) -> None:
    """Stream the archive to `dest`, skipping if it's already there."""
    if os.path.exists(dest):
        print(f"Using cached archive {dest} ({os.path.getsize(dest) >> 20} MB)",
              file=sys.stderr)
        return
    print(f"Downloading {url}\n  -> {dest} (this is a few GB, one time)", file=sys.stderr)
    with requests.get(url, stream=True, timeout=120) as r:
        r.raise_for_status()
        got = 0
        with open(dest, "wb") as f:
            for chunk in r.iter_content(chunk_size=1 << 20):
                f.write(chunk)
                got += len(chunk)
                print(f"\r  {got >> 20} MB", end="", file=sys.stderr)
    print(file=sys.stderr)


def parse_meta(zf: zipfile.ZipFile):
    """Read meta.xml -> (core filename, delimiter, header lines, {field: index})."""
    root = ET.fromstring(zf.read("meta.xml"))
    ns = "{http://rs.tdwg.org/dwc/text/}"
    core = root.find(f"{ns}core")
    delim = (core.get("fieldsTerminatedBy", "\\t")
             .replace("\\t", "\t").replace("\\n", "\n"))
    skip = int(core.get("ignoreHeaderLines", "0"))
    filename = core.find(f"{ns}files/{ns}location").text.strip()
    idx = {}
    for key, uri in TERMS.items():
        field = core.find(f"{ns}field[@term='{uri}']")
        if field is None:
            raise SystemExit(f"meta.xml is missing the {key} term ({uri})")
        idx[key] = int(field.get("index"))
    return filename, delim, skip, idx


def aggregate(zf: zipfile.ZipFile, county: str | None, bbox):
    """Stream the core file, keeping one entry per locationID.

    Coordinates are stable per site, so first-seen wins; we just tally how many
    records reference each site (a public-vs-private proxy for --min-count)."""
    filename, delim, skip, idx = parse_meta(zf)
    need = max(idx.values())
    county = county.lower() if county else None
    sites: dict[str, list] = {}
    with zf.open(filename) as raw:
        stream = io.TextIOWrapper(raw, encoding="utf-8", newline="")
        reader = csv.reader(stream, delimiter=delim)
        for _ in range(skip):
            next(reader, None)
        for n, row in enumerate(reader):
            if n % 2_000_000 == 0 and n:
                print(f"\r  {n // 1_000_000}M rows, {len(sites)} sites",
                      end="", file=sys.stderr)
            if len(row) <= need:
                continue
            lid = row[idx["locationID"]].strip()
            name = row[idx["locality"]].strip()
            if not (lid and name):
                continue
            if lid in sites:
                sites[lid][-1] += 1
                continue
            try:
                lat, lon = float(row[idx["lat"]]), float(row[idx["lon"]])
            except ValueError:
                continue
            fylke = row[idx["fylke"]].strip()
            if county and county not in fylke.lower():
                continue
            if bbox and not (bbox[0] <= lon <= bbox[2] and bbox[1] <= lat <= bbox[3]):
                continue
            sites[lid] = [lid, name, round(lat, 6), round(lon, 6),
                          row[idx["kommune"]].strip(), fylke, 1]
    print(file=sys.stderr)
    return sites


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--archive", default=DEFAULT_ARCHIVE,
                    help="Darwin Core Archive zip; downloaded if absent "
                         "(default: %(default)s)")
    ap.add_argument("--county", help="keep only this fylke (e.g. Trøndelag)")
    ap.add_argument("--bbox", help="minlon,minlat,maxlon,maxlat to clip to a region")
    ap.add_argument("--min-count", type=int, default=2,
                    help="drop sites referenced fewer times — rarely-used sites "
                         "are usually private and won't match on import "
                         "(default: %(default)s)")
    ap.add_argument("-o", "--output", default="localities.csv")
    args = ap.parse_args()

    bbox = tuple(float(x) for x in args.bbox.split(",")) if args.bbox else None
    download(ARCHIVE_URL, args.archive)
    with zipfile.ZipFile(args.archive) as zf:
        sites = aggregate(zf, args.county, bbox)

    rows = sorted((s for s in sites.values() if s[-1] >= args.min_count),
                  key=lambda s: -s[-1])
    with open(args.output, "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["id", "name", "lat", "lon", "kommune", "fylke", "count"])
        w.writerows(rows)
    print(f"Wrote {len(rows)} localities (>= {args.min_count} records) to "
          f"{args.output}, from {len(sites)} sites seen.", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
