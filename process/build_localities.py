#!/usr/bin/env python3
"""Build the official Artsobservasjoner locality table.

Source: the Norwegian Species Observation Service Darwin Core Archive, published
by Artsdatabanken (GBIF dataset b124e1e0-4755-430f-9eab-894f25a9b59c). Every
record carries the registry's *qualified* locality name, its stable site id
(`locationID`), the site's canonical coordinates, and kommune/fylke - so one
row per `locationID` reconstructs the locality registry itself.

We need the qualified name because that is exactly what Artsobservasjoner's
name-based import matches on: "Ørndalen, Sistranda, Frøya, Tø", never bare
"Ørndalen". (Import *with* coordinates instead creates a new locality per point,
which is the duplicate mess we're avoiding - so the app uploads names only.)

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
import math
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
    "observer": "http://rs.tdwg.org/dwc/terms/recordedBy",
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
        raise SystemExit("Download kept dropping - try again later.")
    os.replace(part, dest)


GBIF_SEARCH = "https://api.gbif.org/v1/occurrence/search"
GBIF_DATASET = "b124e1e0-4755-430f-9eab-894f25a9b59c"  # Norwegian Species Observation Service


def wkt_box(minlon, minlat, maxlon, maxlat) -> str:
    return (f"POLYGON(({minlon} {minlat},{maxlon} {minlat},{maxlon} {maxlat},"
            f"{minlon} {maxlat},{minlon} {minlat}))")


def observers(recorded_by):
    """Distinct people from a recordedBy value - GBIF joins co-observers with '|'."""
    if isinstance(recorded_by, list):
        parts = [str(x) for x in recorded_by]
    elif recorded_by:
        parts = str(recorded_by).split("|")
    else:
        parts = []
    return {p.strip() for p in parts if p.strip()} or {"?"}


def _gbif_page(params):
    """Fetch one page, retrying transient GBIF errors (503 backend hiccups,
    dropped connections). Returns the JSON, or None if it should stop."""
    for attempt in range(1, 7):
        try:
            r = requests.get(GBIF_SEARCH, params=params, timeout=90)
            r.raise_for_status()
            return r.json()
        except (requests.exceptions.HTTPError, requests.exceptions.ConnectionError,
                requests.exceptions.ReadTimeout, requests.exceptions.ChunkedEncodingError) as e:
            if attempt == 6:
                print(f"\n  page failed after {attempt} tries, keeping what we have: {e}",
                      file=sys.stderr)
                return None
            time.sleep(min(3 * attempt, 20))


def api_harvest(bbox, taxon_key=212, max_records=99_900):
    """Build the locality table from the GBIF occurrence API instead of the bulk
    archive - many small paged requests, reliable on flaky links. One row per
    locationID, restricted to `taxon_key` (default Aves) so we get bird localities.
    GBIF caps paging at a 100k offset window, so within a regional --bbox this
    captures the established (multi-observer, i.e. public) localities."""
    sites: dict[str, list] = {}
    offset = 0
    while offset < max_records and offset + 300 <= 100_000:
        params = {"datasetKey": GBIF_DATASET, "limit": 300, "offset": offset,
                  "hasCoordinate": "true", "geometry": wkt_box(*bbox)}
        if taxon_key:
            params["taxonKey"] = taxon_key
        d = _gbif_page(params)
        if d is None:
            break  # transient errors exhausted - return the localities we gathered
        for o in d.get("results", []):
            lid = str(o.get("locationID") or "").strip()
            name = (o.get("locality") or "").strip()
            if not (lid and name):
                continue
            who = observers(o.get("recordedBy"))
            if lid in sites:
                sites[lid][6] += 1
                sites[lid][7] |= who
                continue
            try:
                lat, lon = float(o["decimalLatitude"]), float(o["decimalLongitude"])
            except (KeyError, TypeError, ValueError):
                continue
            sites[lid] = [lid, name, round(lat, 6), round(lon, 6),
                          (o.get("municipality") or "").strip(),
                          (o.get("county") or "").strip(), 1, set(who)]
        offset += 300
        print(f"\r  {offset} records, {len(sites)} sites", end="", file=sys.stderr)
        if d.get("endOfRecords") or not d.get("results"):
            break
    print(file=sys.stderr)
    return sites


def haversine(lat1, lon1, lat2, lon2):
    r1, r2 = math.radians(lat1), math.radians(lat2)
    dlat, dlon = math.radians(lat2 - lat1), math.radians(lon2 - lon1)
    a = math.sin(dlat / 2) ** 2 + math.cos(r1) * math.cos(r2) * math.sin(dlon / 2) ** 2
    return 2 * 6371 * math.asin(math.sqrt(a))


def drop_name_collisions(rows, public_min=25, cluster_km=2.0):
    """Remove private 'route' clusters that the observer-count proxy lets through.

    A real public ("allmenn") locality is one canonical site. But a birder laying
    out a personal route stamps many same-named points in one sitting (consecutive
    site ids, a few records each, usually co-observed so they clear --min-observers)
    - e.g. seven "Sildskjærbugen" within 2 km. These can't be imported by bare name
    anyway (no public site to match), so they are pure clutter in the picker.

    For each name used at more than one site, keep a point only if it looks public
    on its own (count >= public_min) AND sits more than cluster_km from every
    already-kept same-name site (a genuinely distinct place, not a route fragment).
    A name used at a single site is always kept. rows: the final [id, lok, hoved,
    kommune, fylke, lat, lon, count] records."""
    groups: dict[str, list] = {}
    for r in rows:
        groups.setdefault(r[1].lower(), []).append(r)
    kept = []
    for grp in groups.values():
        if len(grp) == 1:
            kept.append(grp[0])
            continue
        anchors: list = []
        for r in sorted(grp, key=lambda r: -r[7]):
            if r[7] >= public_min and all(
                    haversine(r[5], r[6], a[5], a[6]) > cluster_km for a in anchors):
                anchors.append(r)
        kept.extend(anchors)
    return kept


def split_name(full: str):
    """Split the qualified registry name into (lokalitet, hovedlokalitet).

    The composite is "Lok[, Hovedlok], Kommune, Fylke-abbr" - drop the trailing
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
            who = observers(row[idx["observer"]])
            if lid in sites:
                sites[lid][6] += 1
                sites[lid][7] |= who
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
                          row[idx["kommune"]].strip(), fylke, 1, set(who)]
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
    ap.add_argument("--api", action="store_true",
                    help="harvest from the GBIF occurrence API (needs --bbox) instead "
                         "of the bulk archive - reliable when the big download won't finish")
    ap.add_argument("--min-count", type=int, default=2,
                    help="drop sites with fewer records (default: %(default)s)")
    ap.add_argument("--min-observers", type=int, default=2,
                    help="keep only localities used by at least this many distinct "
                         "observers - a public-vs-private proxy, since private "
                         "localities have a single owner (default: %(default)s)")
    ap.add_argument("--taxon-key", type=int, default=212,
                    help="GBIF taxonKey for --api (default 212 = Aves/birds; 0 = all taxa)")
    ap.add_argument("--public-min", type=int, default=25,
                    help="when a name is used at several sites, the record count a site "
                         "needs to be kept as a real public locality (default: %(default)s)")
    ap.add_argument("--cluster-km", type=float, default=2.0,
                    help="same-name sites within this distance are treated as one place "
                         "(a private route fragment), keeping only the top one (default: %(default)s)")
    ap.add_argument("-o", "--output", default="localities.csv")
    args = ap.parse_args()

    bbox = tuple(float(x) for x in args.bbox.split(",")) if args.bbox else None
    if args.api:
        if not bbox:
            raise SystemExit("--api needs --bbox minlon,minlat,maxlon,maxlat")
        sites = api_harvest(bbox, args.taxon_key or None)
    else:
        download(ARCHIVE_URL, args.archive)
        with zipfile.ZipFile(args.archive) as zf:
            sites = aggregate(zf, args.county, bbox)

    kept = sorted(
        (s for s in sites.values()
         if s[6] >= args.min_count and len(s[7]) >= args.min_observers),
        key=lambda s: -s[6])
    # Collapse near-duplicate registrations of the same place - the same bare name
    # within ~100 m - to the most-used one (kept is count-desc, so first wins), so
    # the picker lists each place once. Distinct places sharing a name stay separate.
    seen: dict = {}
    for lid, name, lat, lon, kommune, fylke, count, obs in kept:
        lok, hoved = split_name(name)
        key = (lok.lower(), round(lat, 3), round(lon, 3))
        if key in seen:
            seen[key][7] += count
            seen[key][8] |= obs
        else:
            seen[key] = [lid, lok, hoved, kommune, fylke, lat, lon, count, set(obs)]
    merged = drop_name_collisions(list(seen.values()), args.public_min, args.cluster_km)
    rows = [r[:8] + [len(r[8])] for r in sorted(merged, key=lambda r: -r[7])]
    with open(args.output, "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["id", "lokalitet", "hovedlokalitet", "kommune", "fylke",
                    "lat", "lon", "count", "observers"])
        w.writerows(rows)
    print(f"Wrote {len(rows)} public localities (>= {args.min_observers} observers, "
          f">= {args.min_count} records, name-collisions dropped) to {args.output}, "
          f"from {len(sites)} sites seen.", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
