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

Output: localities.csv (id,lokalitet,hovedlokalitet,kommune,fylke,lat,lon,count),
most-used first. The app emits the bare `lokalitet` name + the exact `lat,lon`
(the registry's canonical point, which the import uses to snap to the official
locality and disambiguate duplicate names); `hovedlokalitet` is kept for the
new-site import path and for showing context in the picker.
"""
import argparse
import csv
import io
import os
import sys
import time
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
    """Stream the archive to `dest`, resuming a dropped download via HTTP Range.

    A few GB over one GET drops often, so we write to `<dest>.part`, resume from
    wherever it left off (Range), and retry on connection errors. Only renamed to
    `dest` once complete, so a cached `dest` is always whole."""
    if os.path.exists(dest):
        print(f"Using cached archive {dest} ({os.path.getsize(dest) >> 20} MB)", file=sys.stderr)
        return
    part = dest + ".part"
    print(f"Downloading {url}\n  -> {dest} (a few GB, one time; resumable)", file=sys.stderr)
    for attempt in range(1, 9):
        have = os.path.getsize(part) if os.path.exists(part) else 0
        headers = {"Range": f"bytes={have}-"} if have else {}
        try:
            with requests.get(url, stream=True, timeout=120, headers=headers) as r:
                if r.status_code == 416:           # already have it all
                    break
                mode = "ab"
                if have and r.status_code != 206:  # server ignored Range -> restart
                    have, mode = 0, "wb"
                r.raise_for_status()
                clen = r.headers.get("Content-Length")
                total = (have + int(clen)) if clen else None
                got = have
                with open(part, mode) as f:
                    for chunk in r.iter_content(chunk_size=1 << 20):
                        if not chunk:
                            continue
                        f.write(chunk)
                        got += len(chunk)
                        if (got >> 20) % 16 == 0:
                            tot = f" / {total >> 20} MB" if total else ""
                            print(f"\r  {got >> 20} MB{tot}   ", end="", file=sys.stderr)
            print(file=sys.stderr)
            break
        except (requests.exceptions.ChunkedEncodingError,
                requests.exceptions.ConnectionError,
                requests.exceptions.ReadTimeout) as e:
            wait = min(5 * attempt, 30)
            print(f"\n  interrupted ({type(e).__name__}); resuming in {wait}s "
                  f"(attempt {attempt})", file=sys.stderr)
            time.sleep(wait)
    else:
        raise SystemExit("Download kept dropping — try again later.")
    os.replace(part, dest)


def split_name(full: str):
    """Split the qualified registry name into (lokalitet, hovedlokalitet).

    The composite is "Lok[, Hovedlok], Kommune, Fylke-abbr" — drop the trailing
    kommune + fylke to leave the place part. The bare lokalitet (the most
    specific sublocality) is what the import matches on."""
    parts = [p.strip() for p in full.split(",")]
    place = parts[:-2] if len(parts) >= 3 else parts[:1]
    lok = place[0] if place else full.strip()
    hoved = place[1] if len(place) > 1 else ""
    return lok, hoved


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

    kept = sorted((s for s in sites.values() if s[-1] >= args.min_count),
                  key=lambda s: -s[-1])
    with open(args.output, "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["id", "lokalitet", "hovedlokalitet", "kommune", "fylke",
                    "lat", "lon", "count"])
        for lid, name, lat, lon, kommune, fylke, count in kept:
            lok, hoved = split_name(name)
            w.writerow([lid, lok, hoved, kommune, fylke, lat, lon, count])
    print(f"Wrote {len(rows)} localities (>= {args.min_count} records) to "
          f"{args.output}, from {len(sites)} sites seen.", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
