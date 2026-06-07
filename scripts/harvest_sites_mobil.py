#!/usr/bin/env python3
"""Harvest Artsobservasjoner's authoritative locality ("site") list via the new mobile API.

The modern mobile site (mobil.artsobservasjoner.no, an Angular SPA over a Duende BFF) calls
GET /core/Sites/ByBoundingBox. With IncludePublicSites=true it returns every selectable site
in a bbox - PUBLIC (allmenn) sites plus *your own* private ones - as a flat JSON array, each
row carrying id, name, presentationName (the qualified "Name, Parent, Kommune, FylkeAbbr"),
longitude/latitude (WGS84 - no reprojection), isPrivate, accuracy (= radius m),
municipalityName, countyName, parentSiteId, isPolygon and polygonCoordinates.

This supersedes the legacy harvest_sites.py (POST /Map/GetSitesGeoJson), kept as a fallback:
the data is already WGS84 (no Web-Mercator reprojection), carries the fylke per row (the old
build had to assume one), and a single call returns every tier - so none of the old multi-zoom
union hack. Process the output into localities.csv with build_sites.py (it detects this shape).

AUTH: the BFF uses HttpOnly cookies you cannot read from JS. Capture them from the browser:
log in at https://mobil.artsobservasjoner.no/, open DevTools -> Network, click any /core/
request, and copy its entire `Cookie:` request-header value (it must contain `__Host-bff`)
into COOKIE_FILE. Sessions expire; the harvest is resumable, so just refresh and rerun.

Two server limits drive the tiling: a bbox may span at most ~50 km in Web Mercator per side,
and a response returns at most 1000 sites (no paging). So we tile in Web Mercator at a safe
size and recursively quarter any tile that hits the 1000-site cap. Output: a JSON array of
rows saved to OUT, gentle and resumable.

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
COOKIE_FILE = os.environ.get("BFF_COOKIE_FILE", "/tmp/bff_cookie.txt")
# Where the raw rows are written (kept out of the repo - it's large). Override with
# FELTBOK_DATA_DIR; pass --out to point at a specific file.
DATA_DIR = os.environ.get(
    "FELTBOK_DATA_DIR", "/home/morten/Documents/projects/app-feltbok"
)
# All of mainland Norway. --bbox to narrow (Frøya+Hitra was the old default: 8.0,63.5,9.3,63.9).
DEFAULT_BBOX = (4.0, 57.9, 31.2, 71.3)
# Web-Mercator tile size in metres. The server rejects a box wider than ~50 km/side
# ("BoundingBox too large"), so stay safely under it.
TILE_M = 40_000
MAX_SITES = 1000  # the server's hard per-response cap; hitting it => subdivide
MIN_TILE_M = 400  # stop subdividing below this (accept truncation; ~never reached)
DELAY = 0.8


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


def make_session(cookie_str):
    s = requests.Session()
    s.headers.update(
        {
            "Accept": "application/json",
            "X-CSRF": "1",
            "Cookie": cookie_str,
            "User-Agent": "Mozilla/5.0 (X11; Linux x86_64; rv:151.0) Gecko/20100101 Firefox/151.0",
        }
    )
    return s


def fetch(session, mx0, my0, mx1, my1, tries=4):
    """Query one Web-Mercator box (corners given in mercator metres). Returns the row list,
    or None on a transient failure. Exits on an expired session so a refresh can resume."""
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
            r = session.get(URL, params=params, timeout=60, allow_redirects=False)
            if r.status_code == 200:
                return r.json()
            if r.status_code in (301, 302, 401, 403):
                print(
                    f"\nSession expired (status {r.status_code}). Refresh the cookie in "
                    f"{COOKIE_FILE} and rerun - the harvest resumes.",
                    file=sys.stderr,
                )
                raise SystemExit(2)  # 2 = cookie expired -> caller should stop
        except SystemExit:
            raise
        except Exception:
            pass
        time.sleep(1.5 * (attempt + 1))
    return None


def main() -> int:
    ap = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    ap.add_argument(
        "--bbox", help="minlon,minlat,maxlon,maxlat (default: all of Norway)"
    )
    ap.add_argument("--out", default=f"{DATA_DIR}/artsobs-sites-mobil.json")
    args = ap.parse_args()
    bbox = tuple(float(x) for x in args.bbox.split(",")) if args.bbox else DEFAULT_BBOX
    lon0, lat0, lon1, lat1 = bbox
    session = make_session(pathlib.Path(COOKIE_FILE).read_text().strip())

    rows = {}  # id -> row; ACCUMULATES across runs
    if pathlib.Path(args.out).exists():
        for r in json.load(open(args.out)):
            rows[r["id"]] = r
    ckpt = args.out + ".tiles"  # resume an interrupted run (per-bbox)
    done = set()
    if pathlib.Path(ckpt).exists():
        d = json.load(open(ckpt))
        if d.get("bbox") == list(bbox):  # same area -> resume; new area -> fresh
            done = set(tuple(t) for t in d["done"])

    def save():
        json.dump(list(rows.values()), open(args.out, "w"), ensure_ascii=False)
        json.dump({"bbox": list(bbox), "done": sorted(done)}, open(ckpt, "w"))

    # Web-Mercator grid over the bbox; each cell is harvested (and quartered on overflow).
    mx0, my0 = merc(lon0, lat0)
    mx1, my1 = merc(lon1, lat1)
    nx = max(1, math.ceil((mx1 - mx0) / TILE_M))
    ny = max(1, math.ceil((my1 - my0) / TILE_M))
    stats = {"req": 0}

    def harvest(a, b, c, d, depth=0):
        """Harvest mercator box [a,c]x[b,d]; quarter it if it hits the 1000-site cap."""
        res = fetch(session, a, b, c, d)
        stats["req"] += 1
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

    for i in range(nx):
        for j in range(ny):
            if (i, j) in done:
                continue
            a = mx0 + (mx1 - mx0) * i / nx
            c = mx0 + (mx1 - mx0) * (i + 1) / nx
            b = my0 + (my1 - my0) * j / ny
            d = my0 + (my1 - my0) * (j + 1) / ny
            harvest(a, b, c, d)
            done.add((i, j))
            if len(done) % 10 == 0:
                save()
                pub = sum(1 for r in rows.values() if not r["isPrivate"])
                print(
                    f"\r  {len(done)}/{nx * ny} tiles | {stats['req']} reqs | "
                    f"{len(rows)} sites ({pub} public)",
                    end="",
                    file=sys.stderr,
                    flush=True,
                )
    save()
    pub = sum(1 for r in rows.values() if not r["isPrivate"])
    print(
        f"\nHarvested {len(rows)} sites ({pub} public) -> {args.out}", file=sys.stderr
    )
    pathlib.Path(ckpt).unlink(missing_ok=True)  # clean finish
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
