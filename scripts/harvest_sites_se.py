#!/usr/bin/env python3
"""Harvest Swedish public localities ("fyndplatser") from Artportalen's map API.

The website's report map calls POST artportalen.se/Map/GetSitesGeoJson with a Web-Mercator
bbox and returns the sites in view as GeoJSON points, each carrying the exact registered
siteName, accuracy (radius m), kommun (siteAreaName), parentId, and the authoritative
isPrivate flag. We tile a region in Web Mercator and recursively quarter any tile the
server truncates (completeResult=false), keep the public sites, reproject the Mercator
points to WGS84, and emit the bundled localities.csv schema (Sweden has no polygons).

The endpoint requires a logged-in session, so pass the browser's Cookie and user id:

    AP_COOKIE='<cookie header>' AP_USERID=158090 \
        python scripts/harvest_sites_se.py --region gotland > localities.csv

Authoritative names + public/private flag — the Swedish counterpart of harvest_sites_mobil.py
(supersedes the GBIF-facet build_sites_se.py, which needs no auth but lacks both).
"""

import argparse
import csv
import json
import math
import os
import sys
import time

import requests

URL = "https://artportalen.se/Map/GetSitesGeoJson"
SPECIES_GROUP = "8"  # Fåglar (birds) — as the report form sends
TILE_M = 30_000  # Web-Mercator tile side; quarter on truncation
MIN_TILE_M = 500  # stop subdividing below this
DELAY = float(os.environ.get("AP_DELAY", "0.4"))  # per-tile pause; raise (AP_DELAY) to be gentle

# (minLat, maxLat, minLon, maxLon) per region the maintainer is visiting (issue #127).
REGIONS = {
    "gotland": (56.85, 58.05, 17.85, 19.45),  # west edge reaches the Karlsö bird islands (~17.9°E)
    "stockholm": (58.70, 60.25, 17.00, 19.30),
    "jamtland": (61.70, 64.60, 12.10, 16.50),
}


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
    cookie = os.environ.get("AP_COOKIE")
    if not cookie:
        sys.exit("set AP_COOKIE (the browser Cookie header for artportalen.se)")
    s = requests.Session()
    s.headers.update(
        {
            "Content-Type": "application/json; charset=UTF-8",
            "X-Requested-With": "XMLHttpRequest",
            "Referer": "https://artportalen.se/SubmitSighting/Report",
            "Cookie": cookie,
            "User-Agent": "Mozilla/5.0 (X11; Linux x86_64; rv:151.0) Gecko/20100101 Firefox/151.0",
        }
    )
    return s


def fetch(session, user_id, mx0, my0, mx1, my1):
    """One Mercator box -> (point_features, polygon_features, complete). complete=False => truncated.
    Polygon localities (e.g. nature reserves) come in a separate `polygons` array from the points."""
    body = {
        "zoomLevel": 15,
        "bbox": f"{mx0},{my0},{mx1},{my1}",
        "userId": user_id,
        "speciesGroupId": SPECIES_GROUP,
        "showOnlyProjects": False,
        "showCluster": False,
        "breakYear": 2000,
        "showValidated": True,
    }
    for attempt in range(4):
        try:
            r = session.post(URL, data=json.dumps(body), timeout=60, allow_redirects=False)
            # An expired session redirects to the login page. Abort loudly rather than treat
            # every remaining tile as empty and silently ship a half-harvested region.
            if r.status_code in (301, 302) or "/LogOn" in r.headers.get("Location", ""):
                sys.exit("auth expired (redirected to login) — refresh AP_COOKIE and rerun")
            if r.status_code == 200 and r.headers.get("content-type", "").startswith(
                "application/json"
            ):
                d = r.json()
                return (
                    d["points"]["features"],
                    d["polygons"]["features"],
                    d.get("completeResult", True),
                )
        except requests.RequestException:
            pass
        time.sleep(1.5 * (attempt + 1))
    print(f"  ! tile failed after retries near {mx0:.0f},{my0:.0f} (possible gap)", file=sys.stderr)
    return [], [], True


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--region", choices=REGIONS)
    ap.add_argument("--bbox", help="minlon,minlat,maxlon,maxlat — overrides --region (one-off areas)")
    ap.add_argument("--no-header", action="store_true")
    args = ap.parse_args()
    user_id = int(os.environ.get("AP_USERID", "0")) or sys.exit("set AP_USERID")

    if args.bbox:
        minlon, minlat, maxlon, maxlat = (float(x) for x in args.bbox.split(","))
    elif args.region:
        minlat, maxlat, minlon, maxlon = REGIONS[args.region]
    else:
        sys.exit("pass --region or --bbox")
    session = make_session()
    sites = {}  # siteId -> properties (+ lon/lat)

    def harvest(a, b, c, d):
        points, polygons, complete = fetch(session, user_id, a, b, c, d)
        time.sleep(DELAY)
        for f in points:
            p = f["properties"]
            lon, lat = merc_inv(*f["geometry"]["coordinates"])
            sites[p["siteId"]] = {**p, "lon": lon, "lat": lat, "wkt": ""}
        for f in polygons:
            p = f["properties"]
            # Outer ring only (matches the Norwegian localities.csv WKT), reprojected to WGS84.
            outer = [merc_inv(x, y) for x, y in f["geometry"]["coordinates"][0]]
            wkt = "POLYGON((" + ", ".join(f"{lon:.6f} {lat:.6f}" for lon, lat in outer) + "))"
            clon = sum(o[0] for o in outer) / len(outer)
            clat = sum(o[1] for o in outer) / len(outer)
            sites[p["siteId"]] = {**p, "lon": clon, "lat": clat, "wkt": wkt}
        if not complete and (c - a) > MIN_TILE_M:
            mx, my = (a + c) / 2, (b + d) / 2
            harvest(a, b, mx, my)
            harvest(mx, b, c, my)
            harvest(a, my, mx, d)
            harvest(mx, my, c, d)

    mx0, my0 = merc(minlon, minlat)
    mx1, my1 = merc(maxlon, maxlat)
    nx = max(1, math.ceil((mx1 - mx0) / TILE_M))
    ny = max(1, math.ceil((my1 - my0) / TILE_M))
    for i in range(nx):
        for j in range(ny):
            harvest(
                mx0 + (mx1 - mx0) * i / nx,
                my0 + (my1 - my0) * j / ny,
                mx0 + (mx1 - mx0) * (i + 1) / nx,
                my0 + (my1 - my0) * (j + 1) / ny,
            )
        print(f"  col {i + 1}/{nx}: {len(sites)} sites", file=sys.stderr)

    name_by_id = {sid: s["siteName"] for sid, s in sites.items()}
    w = csv.writer(sys.stdout)
    if not args.no_header:
        w.writerow(
            "id lokalitet hovedlokalitet kommune fylke lat lon "
            "count observers radius geometry public mine super".split()
        )
    kept = 0
    for sid, s in sorted(sites.items()):
        if s.get("isPrivate"):
            continue  # bundle only public (allmän) localities
        kommune = s["siteAreaName"] if s.get("siteAreaDescription") == "Kommun" else ""
        parent = name_by_id.get(s.get("parentId"), "")
        w.writerow(
            [
                sid,
                s["siteName"],
                parent,
                kommune,
                "",
                f"{s['lat']:.6f}",
                f"{s['lon']:.6f}",
                0,
                0,
                int(round(s.get("accuracy") or 0)) or 1,
                s.get("wkt", ""),
                "1",
                "0",
                "1" if s.get("siteType") == 2 else "0",
            ]
        )
        kept += 1
    print(
        f"{args.region}: {len(sites)} sites, {kept} public -> localities.csv",
        file=sys.stderr,
    )


if __name__ == "__main__":
    raise SystemExit(main())
