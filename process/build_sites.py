#!/usr/bin/env python3
"""Build app/src/main/assets/localities.csv from a harvest_sites.py raw GeoJSON.

The harvest carries everything we need straight from Artsobservasjoner, so this is the
whole pipeline now - no GBIF, no footprints, no observer/polygon heuristic, no
FindSitesByName flag fetch:
  - `siteId`        -> id            (the current Artsobs site id)
  - `siteName`      -> lokalitet     (the exact registered name; what paste-import matches)
  - `parentName`    -> hovedlokalitet (superlokalitet)
  - `siteAreaName`  -> kommune       (when siteAreaDescription == "Kommune")
  - geometry        -> lat/lon (centroid) + a reprojected WGS84 `geometry` POLYGON, or none
  - `accuracy`      -> radius        (0 = point/dot, > 0 = real circle radius in metres)
  - `isPrivate`     -> public        (we keep only public/allmenn sites; the app is shareable)
Manual corrections in locality_overrides.csv (keyed by siteId) still win, but the
authoritative flag means they're rarely needed.

    .venv/bin/python process/build_sites.py [--raw FILE] [--fylke Trøndelag --fylke-abbr Tø]
"""
import argparse
import csv
import importlib.util
import json
import pathlib

from pyproj import Transformer

_T = Transformer.from_crs("EPSG:3857", "EPSG:4326", always_xy=True)   # Web Mercator -> WGS84
DATA_DIR = "/home/morten/Documents/projects/app-feltbok"
CSV = "app/src/main/assets/localities.csv"

_mp = pathlib.Path(__file__).parent / "mark_public.py"
_spec = importlib.util.spec_from_file_location("mp", _mp)
mp = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(mp)

COLS = ["id", "lokalitet", "hovedlokalitet", "kommune", "fylke", "lat", "lon",
        "count", "observers", "fullname", "radius", "geometry", "public", "mine"]


def wgs(x, y):
    lon, lat = _T.transform(x, y)
    return round(lat, 6), round(lon, 6)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--raw", default=f"{DATA_DIR}/artsobs-sites-raw.json")
    ap.add_argument("--fylke", default="Trøndelag", help="fylke name (harvest only gives kommune)")
    ap.add_argument("--fylke-abbr", default="Tø", help="fylke abbreviation for the qualified name")
    args = ap.parse_args()

    overrides = mp.load_overrides()
    rows = []
    for f in json.load(open(args.raw))["features"]:
        p = f["properties"]
        # The Report map returns public (allmenn) sites + the logged-in user's OWN private
        # ones (isPrivate=true). Ship both: public localities (everyone) and "mine" (the
        # user's customs, which the import links by bare name). Others' privates never appear.
        mine = "1" if p["isPrivate"] else "0"
        public = overrides.get(str(p["siteId"]), "0" if p["isPrivate"] else "1")
        if public != "1" and mine != "1":
            continue
        g = f["geometry"]
        geom = ""
        if g["type"] == "Point":
            lat, lon = wgs(*g["coordinates"])
        else:
            pts = [wgs(x, y) for x, y in g["coordinates"][0]]
            lat = round(sum(a for a, _ in pts) / len(pts), 6)
            lon = round(sum(b for _, b in pts) / len(pts), 6)
            geom = "POLYGON((" + ", ".join(f"{b} {a}" for a, b in pts) + "))"
        lok = p["siteName"] or ""
        hoved = p["parentName"] or ""
        kom = p["siteAreaName"] if p.get("siteAreaDescription") == "Kommune" else ""
        full = ", ".join(x for x in (lok, hoved, kom, args.fylke_abbr) if x)
        rows.append({"id": str(p["siteId"]), "lokalitet": lok, "hovedlokalitet": hoved,
                     "kommune": kom, "fylke": args.fylke, "lat": lat, "lon": lon,
                     "count": 0, "observers": 0, "fullname": full,
                     "radius": int(p["accuracy"]), "geometry": geom, "public": public, "mine": mine})
    rows.sort(key=lambda r: (r["lokalitet"].lower(), r["id"]))
    with open(CSV, "w", newline="") as fh:
        w = csv.DictWriter(fh, fieldnames=COLS)
        w.writeheader()
        w.writerows(rows)
    npub = sum(1 for r in rows if r["public"] == "1")
    nmine = sum(1 for r in rows if r["mine"] == "1")
    npoly = sum(1 for r in rows if r["geometry"])
    print(f"Wrote {len(rows)} localities ({npub} public, {nmine} yours) - {npoly} polygons, "
          f"{len(rows)-npoly} points - to {CSV}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
