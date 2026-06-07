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

    .venv/bin/python scripts/harvest_sites.py [--bbox minlon,minlat,maxlon,maxlat]
"""

import argparse
import json
import math
import os
import pathlib
import sys
import time
import urllib.request

import requests

URL = "https://www.artsobservasjoner.no/Map/GetSitesGeoJson"
COOKIE_FILE = os.environ.get("ARTSOBS_COOKIE_FILE", "/tmp/aspx_cookie.txt")
# Where the raw GeoJSON is written (kept out of the repo - it's large). Override with
# FELTBOK_DATA_DIR; pass --out to point at a specific file.
DATA_DIR = os.environ.get(
    "FELTBOK_DATA_DIR", "/home/morten/Documents/projects/app-feltbok"
)
# Frøya + Hitra default; --bbox to widen (eventually all of Norway: ~4.0,57.9,31.2,71.3).
DEFAULT_BBOX = (8.0, 63.5, 9.3, 63.9)
USER_ID = int(os.environ.get("ARTSOBS_USER_ID", "58758"))
# (zoomLevel, max tile span in degrees) passes. Low zoom -> superlocalities (one big tile);
# high zoom -> leaves (small tiles). Tiles are sized so each returns its sites un-clustered.
PASSES = [(11, 99), (12, 0.7), (13, 0.4), (15, 0.18), (16, 0.09)]
DELAY = 1.2


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


def kommune_bbox(name):
    """Resolve a kommune's lon/lat bbox via the public area-autocomplete (no login)."""
    body = json.dumps({"Term": name}).encode()
    req = urllib.request.Request(
        "https://www.artsobservasjoner.no/Map/FindAreasByNameForAutocomplete",
        data=body,
        headers={
            "Content-Type": "application/json",
            "X-Requested-With": "XMLHttpRequest",
        },
    )
    areas = json.loads(urllib.request.urlopen(req, timeout=30).read())
    hit = next(
        (
            a
            for a in areas
            if a["value"].lower() == name.lower()
            and "kommune" in (a.get("subvalue") or "").lower()
        ),
        None,
    )
    if not hit:
        print(
            f"No kommune named {name!r} (got: {[a['value'] for a in areas][:6]})",
            file=sys.stderr,
        )
        raise SystemExit(3)  # 3 = name mismatch -> caller can skip
    x1, y1, x2, y2 = (float(v) for v in hit["bbox"].split(","))
    lon0, lat0 = merc_inv(x1, y1)
    lon1, lat1 = merc_inv(x2, y2)
    return lon0, lat0, lon1, lat1


def make_session(cookie_str):
    """A Session seeded with the captured cookie. It keeps any renewed auth cookie the
    server returns (Set-Cookie), so the harvester's own continuous requests renew the
    sliding session - one capture lasts the whole sweep (unless the site uses absolute
    expiry, in which case it still eventually dies and needs a fresh cookie)."""
    s = requests.Session()
    for part in cookie_str.split(";"):
        part = part.strip()
        if "=" in part:
            k, v = part.split("=", 1)
            s.cookies.set(k.strip(), v.strip())
    s.headers.update(
        {
            "Content-Type": "application/json",
            "X-Requested-With": "XMLHttpRequest",
            "User-Agent": "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36",
        }
    )
    return s


def fetch(session, x1, y1, x2, y2, zoom, tries=4):
    body = {
        "zoomLevel": zoom,
        "bbox": f"{x1},{y1},{x2},{y2}",
        "userId": USER_ID,
        "coordSyst": 0,
        "speciesGroupId": "8",
        "taxonId": None,
    }
    for attempt in range(tries):
        try:
            r = session.post(URL, json=body, timeout=60, allow_redirects=False)
            if r.status_code == 200 and r.text.lstrip().startswith("{"):
                return r.json()  # session auto-keeps any renewed auth cookie
            if r.status_code in (301, 302, 401, 403):
                print(
                    f"Session expired (status {r.status_code}). Refresh the cookie in "
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
    ap.add_argument("--bbox", help="minlon,minlat,maxlon,maxlat")
    ap.add_argument(
        "--kommune",
        help="harvest a whole kommune (bbox resolved via the area autocomplete)",
    )
    ap.add_argument("--out", default=f"{DATA_DIR}/artsobs-sites-raw.json")
    args = ap.parse_args()
    if args.kommune:
        bbox = kommune_bbox(args.kommune)
        print(
            f"  {args.kommune}: bbox {','.join(f'{v:.3f}' for v in bbox)}",
            file=sys.stderr,
        )
    elif args.bbox:
        bbox = tuple(float(x) for x in args.bbox.split(","))
    else:
        bbox = DEFAULT_BBOX
    lon0, lat0, lon1, lat1 = bbox
    session = make_session(pathlib.Path(COOKIE_FILE).read_text().strip())

    feats = {}  # the raw ACCUMULATES across areas
    if pathlib.Path(args.out).exists():
        for f in json.load(open(args.out)).get("features", []):
            feats[f["properties"]["siteId"]] = f
    ckpt = args.out + ".tiles"  # resume an interrupted run (per-bbox)
    done = set()
    if pathlib.Path(ckpt).exists():
        d = json.load(open(ckpt))
        if d.get("bbox") == list(bbox):  # same area -> resume; new area -> fresh tiles
            done = set(tuple(t) for t in d["done"])

    def save():
        json.dump(
            {"features": list(feats.values())}, open(args.out, "w"), ensure_ascii=False
        )
        json.dump({"bbox": list(bbox), "done": sorted(done)}, open(ckpt, "w"))

    n = 0
    for zoom, span in PASSES:
        nx = max(1, math.ceil((lon1 - lon0) / span))
        ny = max(1, math.ceil((lat1 - lat0) / span))
        for i in range(nx):
            for j in range(ny):
                if (zoom, i, j) in done:
                    continue
                a = lon0 + (lon1 - lon0) * i / nx
                b = lon0 + (lon1 - lon0) * (i + 1) / nx
                c = lat0 + (lat1 - lat0) * j / ny
                d = lat0 + (lat1 - lat0) * (j + 1) / ny
                r = fetch(session, *merc(a, c), *merc(b, d), zoom)
                if r is not None:
                    # A tile with no points (or no polygons) may omit the key or null it;
                    # tolerate that instead of crashing the whole resumable sweep.
                    for kind in ("points", "polygons"):
                        for f in (r.get(kind) or {}).get("features", []):
                            feats[f["properties"]["siteId"]] = f
                    done.add((zoom, i, j))
                n += 1
                if n % 10 == 0:
                    save()
                    pub = sum(
                        1 for f in feats.values() if not f["properties"]["isPrivate"]
                    )
                    print(
                        f"\r  {n} tiles | {len(feats)} sites ({pub} public)",
                        end="",
                        file=sys.stderr,
                        flush=True,
                    )
                time.sleep(DELAY)
    save()
    pub = sum(1 for f in feats.values() if not f["properties"]["isPrivate"])
    print(
        f"\nHarvested {len(feats)} sites ({pub} public) -> {args.out}", file=sys.stderr
    )
    pathlib.Path(ckpt).unlink(missing_ok=True)  # clean finish
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
