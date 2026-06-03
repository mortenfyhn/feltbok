#!/usr/bin/env python3
"""Harvest Artsobservasjoner's authoritative locality ("site") list with real geometry.

The "Report new observation" map calls POST /Map/GetSitesGeoJson, which returns every
selectable site in a bbox - PUBLIC (allmenn) sites plus *your own* private ones - as
GeoJSON, split into `points` (with `accuracy` = radius) and `polygons`, each carrying
`siteId`, `siteName`, `parentName`, `siteAreaName` (kommune), `accuracy`, `colorString`
and the authoritative `isPrivate` flag. This is the real registry, far more complete than
the GBIF observation harvest (it includes obs-less superlocalities), so it supersedes the
GBIF -> footprints -> mark_public -> fetch_public_flags chain.

LOGIN REQUIRED: the endpoint 302s to /LogOn without a session. Capture your session from
the browser (DevTools, any request to artsobservasjoner.no) and put the full Cookie header
value - it must include `.ASPXAUTHNO=...` and `__RequestVerificationToken=...` - in
COOKIE_FILE. The session expires after a while; the harvest is resumable, so just refresh
the cookie and rerun. This is a BUILD-TIME tool on the maintainer's laptop; the app still
ships a bundled CSV and stays zero-login.

The map shows superlocalities when zoomed out and leaf localities when zoomed in, so no
single zoom returns everything - we union several zoom levels, tiling the finer ones.
Output: a raw GeoJSON {features:[...]} saved to OUT (default in the data dir), gentle and
resumable. Process it into localities.csv with build_sites.py.

    .venv/bin/python process/harvest_sites.py [--bbox minlon,minlat,maxlon,maxlat]
"""
import argparse
import json
import math
import os
import pathlib
import sys
import time
import urllib.request

URL = "https://www.artsobservasjoner.no/Map/GetSitesGeoJson"
COOKIE_FILE = os.environ.get("ARTSOBS_COOKIE_FILE", "/tmp/aspx_cookie.txt")
DATA_DIR = "/home/morten/Documents/projects/app-feltbok"
# Frøya + Hitra default; --bbox to widen (eventually all of Norway: ~4.0,57.9,31.2,71.3).
DEFAULT_BBOX = (8.0, 63.5, 9.3, 63.9)
USER_ID = int(os.environ.get("ARTSOBS_USER_ID", "58758"))
# (zoomLevel, max tile span in degrees) passes. Low zoom -> superlocalities (one big tile);
# high zoom -> leaves (small tiles). Tiles are sized so each returns its sites un-clustered.
PASSES = [(11, 99), (12, 0.7), (13, 0.4), (15, 0.18), (16, 0.09)]
DELAY = 1.2


def merc(lon, lat):
    x = lon * 20037508.34 / 180
    y = math.log(math.tan((90 + lat) * math.pi / 360)) / (math.pi / 180) * 20037508.34 / 180
    return x, y


def fetch(cookie, x1, y1, x2, y2, zoom, tries=4):
    body = json.dumps({"zoomLevel": zoom, "bbox": f"{x1},{y1},{x2},{y2}", "userId": USER_ID,
                       "coordSyst": 0, "speciesGroupId": "8", "taxonId": None}).encode()
    req = urllib.request.Request(URL, data=body, headers={
        "Content-Type": "application/json", "X-Requested-With": "XMLHttpRequest", "Cookie": cookie})
    for attempt in range(tries):
        try:
            txt = urllib.request.urlopen(req, timeout=60).read().decode("utf-8")
            if txt.lstrip().startswith("{"):
                return json.loads(txt)
            if "LogOn" in txt:
                raise SystemExit("Session expired (redirected to /LogOn). Refresh the cookie "
                                 f"in {COOKIE_FILE} and rerun - the harvest resumes.")
        except SystemExit:
            raise
        except Exception:
            pass
        time.sleep(1.5 * (attempt + 1))
    return None


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--bbox", help="minlon,minlat,maxlon,maxlat (default: Frøya+Hitra)")
    ap.add_argument("--out", default=f"{DATA_DIR}/artsobs-sites-raw.json")
    args = ap.parse_args()
    bbox = tuple(float(x) for x in args.bbox.split(",")) if args.bbox else DEFAULT_BBOX
    lon0, lat0, lon1, lat1 = bbox
    cookie = pathlib.Path(COOKIE_FILE).read_text().strip()

    feats, done = {}, set()
    if pathlib.Path(args.out).exists():                      # resume: keep what we have
        for f in json.load(open(args.out)).get("features", []):
            feats[f["properties"]["siteId"]] = f
    ckpt = args.out + ".tiles"                               # which (zoom,i,j) tiles are done
    if pathlib.Path(ckpt).exists():
        done = set(tuple(t) for t in json.load(open(ckpt)))

    def save():
        json.dump({"features": list(feats.values())}, open(args.out, "w"), ensure_ascii=False)
        json.dump(sorted(done), open(ckpt, "w"))

    n = 0
    for zoom, span in PASSES:
        nx = max(1, math.ceil((lon1 - lon0) / span))
        ny = max(1, math.ceil((lat1 - lat0) / span))
        for i in range(nx):
            for j in range(ny):
                if (zoom, i, j) in done:
                    continue
                a = lon0 + (lon1 - lon0) * i / nx; b = lon0 + (lon1 - lon0) * (i + 1) / nx
                c = lat0 + (lat1 - lat0) * j / ny; d = lat0 + (lat1 - lat0) * (j + 1) / ny
                r = fetch(cookie, *merc(a, c), *merc(b, d), zoom)
                if r is not None:
                    for kind in ("points", "polygons"):
                        for f in r[kind]["features"]:
                            feats[f["properties"]["siteId"]] = f
                    done.add((zoom, i, j))
                n += 1
                if n % 10 == 0:
                    save()
                    pub = sum(1 for f in feats.values() if not f["properties"]["isPrivate"])
                    print(f"\r  {n} tiles | {len(feats)} sites ({pub} public)", end="", file=sys.stderr, flush=True)
                time.sleep(DELAY)
    save()
    pub = sum(1 for f in feats.values() if not f["properties"]["isPrivate"])
    print(f"\nHarvested {len(feats)} sites ({pub} public) -> {args.out}", file=sys.stderr)
    pathlib.Path(ckpt).unlink(missing_ok=True)              # clean finish
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
