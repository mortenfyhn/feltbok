#!/usr/bin/env python3
"""Harvest Artsobservasjoner's authoritative locality ("site") list via the new mobile API.

The modern mobile site (mobil.artsobservasjoner.no, an Angular SPA over a Duende BFF) calls
GET /core/Sites/ByBoundingBox. With IncludePublicSites=true it returns every selectable site
in a bbox - public (allmenn) sites and private ones alike - as a flat JSON array, each
row carrying id, name, presentationName (the qualified "Name, Parent, Kommune, FylkeAbbr"),
longitude/latitude (WGS84 - no reprojection), isPrivate, accuracy (= radius m),
municipalityName, countyName, parentSiteId, isPolygon and polygonCoordinates.

The data is already WGS84 (no Web-Mercator reprojection), carries the fylke per row, and a
single call returns every tier. Process the output into localities.csv with build_sites.py.

No auth needed: the endpoint serves the public allmenn registry over a plain GET (it also
returns private sites - other people's included - but build_sites.py keeps only the public
ones for the bundled localities.csv; your own privates come from the in-app ByUser sync).

Two server limits drive the tiling. A bbox may span at most 50 km in Web Mercator per side;
51 km gives "BoundingBox too large" (errorCode G8), so we cannot start from one big box and
quadtree downward - the grid has to be laid out in advance. And a response is truncated with
no paging: MaxSites must be 1-1000, and the server returns up to about twice what you ask
for, so the 1000-row check below spots truncation well before the real cut-off. We tile in
Web Mercator at a safe size and recursively quarter any tile that comes back truncated.
Output: a JSON array of rows saved to OUT, gentle and resumable.

    .venv/bin/python scripts/harvest_sites_mobil.py [--bbox minlon,minlat,maxlon,maxlat]
"""

import argparse
import json
import math
import os
import pathlib
import sys
import time

import requests

URL = "https://mobil.artsobservasjoner.no/core/Sites/ByBoundingBox"
# Where the raw rows are written (kept out of the repo - it's large). Override with
# FELTBOK_DATA_DIR; pass --out to point at a specific file.
DATA_DIR = os.environ.get(
    "FELTBOK_DATA_DIR", "/home/morten/Documents/projects/app-feltbok"
)
# Norwegian territory as a few land-region boxes, harvested and accumulated into one file.
# A few boxes beat one giant bbox: they skip the vast empty sea *between* regions (the server
# caps a request at ~50 km/side, so we can't cheaply probe a huge area in one go anyway).
# --bbox overrides with a single box.
DEFAULT_REGIONS = [
    (4.0, 57.9, 31.2, 71.3),  # mainland
    (8.0, 74.0, 35.0, 81.5),  # Svalbard + Bjørnøya
    (-9.5, 70.7, -7.5, 71.3),  # Jan Mayen
]
# Web-Mercator tile size in metres. The server rejects a box wider than ~50 km/side
# ("BoundingBox too large"), so stay safely under it.
TILE_M = 40_000
MAX_SITES = 1000  # the largest MaxSites the server accepts (400 outside 1-1000); hitting it => subdivide
MIN_TILE_M = 400  # stop subdividing below this (accept truncation; ~never reached)
DELAY = 0.8
# save() rewrites the whole rows file, which is ~1 GB by the end of a run (~7s to serialize
# and write). Pacing it by tile count made bookkeeping cost more than the requests once the
# run reached empty grid; pace it by time instead, so it stays a few percent of wall clock.
# A clean Ctrl-C still saves, so this only bounds what a crash or power loss can cost.
SAVE_EVERY_S = 120


def merc(lon, lat):
    x = lon * 20037508.34 / 180
    y = (
        math.log(math.tan((90 + lat) * math.pi / 360))
        / (math.pi / 180)
        * 20037508.34
        / 180
    )
    return x, y


def merc_inv(x, y):
    lon = x / 20037508.34 * 180
    lat = math.degrees(
        2 * math.atan(math.exp((y / 20037508.34 * 180) * math.pi / 180)) - math.pi / 2
    )
    return lon, lat


def make_session():
    s = requests.Session()
    s.headers.update(
        {
            "Accept": "application/json",
            "X-CSRF": "1",
            "User-Agent": "Mozilla/5.0 (X11; Linux x86_64; rv:151.0) Gecko/20100101 Firefox/151.0",
        }
    )
    return s


def fetch(session, mx0, my0, mx1, my1, tries=4):
    """Query one Web-Mercator box (corners given in mercator metres). Returns the row list,
    or None on a transient failure (the tile is skipped; rerun resumes it)."""
    lon0, lat0 = merc_inv(mx0, my0)
    lon1, lat1 = merc_inv(mx1, my1)
    params = {
        "MinX": lon0,
        "MinY": lat0,
        "MaxX": lon1,
        "MaxY": lat1,
        "MaxSites": MAX_SITES,
        "IncludePublicSites": "true",
    }
    for attempt in range(tries):
        try:
            r = session.get(URL, params=params, timeout=60)
            if r.status_code == 200:
                return r.json()
        except Exception:
            pass
        time.sleep(1.5 * (attempt + 1))
    return None


def hms(seconds):
    """Compact duration for progress: 4521 -> '1h15m', 312 -> '5m12s', 8 -> '8s'."""
    s = int(seconds)
    h, m, sec = s // 3600, (s % 3600) // 60, s % 60
    if h:
        return f"{h}h{m:02d}m"
    if m:
        return f"{m}m{sec:02d}s"
    return f"{sec}s"


def main() -> int:
    ap = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    ap.add_argument(
        "--bbox",
        help="minlon,minlat,maxlon,maxlat (default: mainland + Svalbard + Jan Mayen)",
    )
    ap.add_argument("--out", default=f"{DATA_DIR}/artsobs-sites-mobil.json")
    args = ap.parse_args()
    regions = (
        [tuple(float(x) for x in args.bbox.split(","))]
        if args.bbox
        else DEFAULT_REGIONS
    )
    session = make_session()

    def grid(bbox):
        """Web-Mercator tile grid over a bbox: (mx0, my0, mx1, my1, nx, ny)."""
        mx0, my0 = merc(bbox[0], bbox[1])
        mx1, my1 = merc(bbox[2], bbox[3])
        return (
            mx0,
            my0,
            mx1,
            my1,
            max(1, math.ceil((mx1 - mx0) / TILE_M)),
            max(1, math.ceil((my1 - my0) / TILE_M)),
        )

    grids = [grid(r) for r in regions]
    total = sum(g[4] * g[5] for g in grids)

    rows = {}  # id -> row; ACCUMULATES across runs
    ckpt = args.out + ".tiles"  # resume an interrupted run
    done = [set() for _ in regions]  # done tiles per region
    if pathlib.Path(args.out).exists():
        try:
            for r in json.load(open(args.out)):
                rows[r["id"]] = r
            if pathlib.Path(ckpt).exists():
                d = json.load(open(ckpt))
                if d.get("regions") == [
                    list(r) for r in regions
                ]:  # same areas -> resume
                    done = [{tuple(t) for t in s} for s in d["done"]]
        except (json.JSONDecodeError, OSError) as e:
            # Don't trust a partial rows file + its checkpoint: resuming would skip tiles
            # whose rows are gone, leaving silent gaps. Start fresh and re-fetch everything.
            print(f"\n{args.out} is unreadable ({e}); starting fresh.", file=sys.stderr)
            rows, done = {}, [set() for _ in regions]

    def save():
        # Atomic: write to a temp file then rename, so a kill mid-write can't corrupt the
        # resume file (a direct json.dump leaves a truncated file if interrupted).
        ck = {"regions": [list(r) for r in regions], "done": [sorted(s) for s in done]}
        for path, data in ((args.out, list(rows.values())), (ckpt, ck)):
            tmp = path + ".tmp"
            with open(tmp, "w") as f:
                json.dump(data, f, ensure_ascii=False)
            os.replace(tmp, path)

    stats = {"req": 0}

    def harvest(a, b, c, d, depth=0):
        """Harvest mercator box [a,c]x[b,d]; quarter it if it hits the 1000-site cap."""
        res = fetch(session, a, b, c, d)
        stats["req"] += 1
        # Most of the grid is empty sea and ice (~86% of tiles). Those answer in ~0.04s, so
        # pausing after them spends hours of wall clock being polite about nothing.
        if res:
            time.sleep(DELAY)
        if res is None:
            return
        for r in res:
            rows[r["id"]] = r
        if (
            len(res) >= MAX_SITES and (c - a) > MIN_TILE_M
        ):  # truncated -> recurse into quarters
            mxm = (a + c) / 2
            mym = (b + d) / 2
            harvest(a, b, mxm, mym, depth + 1)
            harvest(mxm, b, c, mym, depth + 1)
            harvest(a, mym, mxm, d, depth + 1)
            harvest(mxm, mym, c, d, depth + 1)

    start = time.time()
    ndone = sum(
        len(s) for s in done
    )  # tiles done across all regions (resumed + this run)
    processed = 0  # tiles harvested THIS run (excludes resumed ones), for the ETA rate
    last_save = start
    # Counting public rows walks every row, so refresh it when we save, not on every print.
    pub = sum(1 for r in rows.values() if not r["isPrivate"])
    try:
        for (mx0, my0, mx1, my1, nx, ny), reg_done in zip(grids, done):
            for i in range(nx):
                for j in range(ny):
                    if (i, j) in reg_done:
                        continue
                    a = mx0 + (mx1 - mx0) * i / nx
                    c = mx0 + (mx1 - mx0) * (i + 1) / nx
                    b = my0 + (my1 - my0) * j / ny
                    d = my0 + (my1 - my0) * (j + 1) / ny
                    harvest(a, b, c, d)
                    reg_done.add((i, j))
                    ndone += 1
                    processed += 1
                    if time.time() - last_save > SAVE_EVERY_S:
                        save()
                        last_save = time.time()
                        pub = sum(1 for r in rows.values() if not r["isPrivate"])
                    if ndone % 10 == 0:
                        elapsed = time.time() - start
                        eta = f", ~{hms(elapsed / processed * (total - ndone))} left"
                        print(
                            f"\r  {ndone}/{total} tiles | {hms(elapsed)} elapsed{eta} | "
                            f"{stats['req']} requests | {len(rows)} sites ({pub} public)",
                            end="",
                            file=sys.stderr,
                            flush=True,
                        )
    except KeyboardInterrupt:
        save()  # progress is safe; the checkpoint stays so a rerun resumes
        print(
            f"\nStopped at {ndone}/{total} tiles - rerun to resume.",
            file=sys.stderr,
        )
        return 0
    save()
    pub = sum(1 for r in rows.values() if not r["isPrivate"])
    print(
        f"\nHarvested {len(rows)} sites ({pub} public) in {hms(time.time() - start)} "
        f"-> {args.out}",
        file=sys.stderr,
    )
    pathlib.Path(ckpt).unlink(missing_ok=True)  # clean finish
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
