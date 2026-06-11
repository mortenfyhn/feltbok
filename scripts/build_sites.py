#!/usr/bin/env python3
"""Build app/src/main/assets/localities.csv from a site harvest.

The harvest carries everything we need straight from Artsobservasjoner, so this is the
whole pipeline now - no GBIF, no footprints, no observer/polygon heuristic, no
FindSitesByName flag fetch. Two harvest shapes are accepted, auto-detected by structure:

  - the new mobile API (harvest_sites_mobil.py): a flat JSON array of site rows, already in
    WGS84 with a per-row county, so no reprojection and no --fylke guess. `hovedlokalitet`
    is resolved from `parentSiteId` against the harvest's own id->name map. PREFERRED.
  - the legacy GeoJSON (harvest_sites.py, POST /Map/GetSitesGeoJson): Web-Mercator features
    reprojected here; carries only the kommune, so --fylke/--fylke-abbr name the fylke.

Either way: `siteName`/`name` -> lokalitet (the exact registered name paste-import matches),
geometry -> lat/lon + a WGS84 POLYGON or none, accuracy -> radius (0 = point), isPrivate ->
public/mine. Manual corrections in locality_overrides.csv (keyed by siteId) still win.

    .venv/bin/python scripts/build_sites.py [--raw FILE] [--fylke Trøndelag --fylke-abbr Tø]
"""

import argparse
import csv
import importlib.util
import json
import os
import pathlib

from pyproj import Transformer

_T = Transformer.from_crs(
    "EPSG:3857", "EPSG:4326", always_xy=True
)  # Web Mercator -> WGS84
# Where the harvested raw GeoJSON lives (kept out of the repo - it's large). Override with
# FELTBOK_DATA_DIR; pass --raw to point at a specific file.
DATA_DIR = os.environ.get(
    "FELTBOK_DATA_DIR", "/home/morten/Documents/projects/app-feltbok"
)
CSV = "app/src/main/assets/localities.csv"  # public/allmenn - bundled + committed + shared
MY_CSV = "my-localities.csv"  # the maintainer's own - gitignored, device-only

_mp = pathlib.Path(__file__).parent / "mark_public.py"
_spec = importlib.util.spec_from_file_location("mp", _mp)
mp = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(mp)

COLS = [
    "id",
    "lokalitet",
    "hovedlokalitet",
    "kommune",
    "fylke",
    "lat",
    "lon",
    "count",
    "observers",
    "radius",
    "geometry",
    "public",
    "mine",
]


def wgs(x, y):
    lon, lat = _T.transform(x, y)
    return round(lat, 6), round(lon, 6)


def make_row(
    site_id, lok, hoved, kom, fylke, lat, lon, radius, geom, is_private, overrides
):
    """Common row assembly + the public/mine filter. The harvest returns public (allmenn)
    sites + the logged-in user's OWN private ones; ship both (others' privates never appear).
    Returns the CSV row, or None to drop a site that is neither public nor ours."""
    mine = "1" if is_private else "0"
    public = overrides.get(str(site_id), "0" if is_private else "1")
    if public != "1" and mine != "1":
        return None
    return {
        "id": str(site_id),
        "lokalitet": lok,
        "hovedlokalitet": hoved,
        "kommune": kom,
        "fylke": fylke,
        "lat": lat,
        "lon": lon,
        "count": 0,
        "observers": 0,
        "radius": radius,
        "geometry": geom,
        "public": public,
        "mine": mine,
    }


def rows_from_mobil(raw, overrides):
    """New mobile-API shape: a flat list of site rows, already WGS84 with a per-row county."""
    names = {r["id"]: r["name"] for r in raw}  # resolve parentSiteId -> parent name
    out = []
    for r in raw:
        geom = ""
        if r.get("isPolygon") and r.get("polygonCoordinates"):
            pc = r["polygonCoordinates"]  # [[lon, lat], ...] in WGS84,
            coords = (
                json.loads(pc) if isinstance(pc, str) else pc
            )  # already parsed on this endpoint
            if coords and coords[0] != coords[-1]:  # close the ring for valid WKT
                coords = coords + [coords[0]]
            # 6 decimals (~0.1 m) matches the point lat/lon precision and is plenty for a
            # locality outline; the raw feed's 8 decimals were dead weight in the bundled CSV.
            geom = (
                "POLYGON(("
                + ", ".join(f"{round(lon, 6)} {round(lat, 6)}" for lon, lat in coords)
                + "))"
            )
        lok = r["name"] or ""
        hoved = names.get(r.get("parentSiteId"), "") or ""
        row = make_row(
            r["id"],
            lok,
            hoved,
            r.get("municipalityName") or "",
            r.get("countyName") or "",
            round(r["latitude"], 6),
            round(r["longitude"], 6),
            int(r.get("accuracy") or 0),
            geom,
            r["isPrivate"],
            overrides,
        )
        if row:
            out.append(row)
    return out


def rows_from_geojson(features, fylke, fylke_abbr, overrides):
    """Legacy GeoJSON shape (POST /Map/GetSitesGeoJson): Web-Mercator, reprojected here."""
    out = []
    for f in features:
        p = f["properties"]
        g = f["geometry"]
        geom = ""
        if g["type"] == "Point":
            lat, lon = wgs(*g["coordinates"])
        else:
            pts = [wgs(x, y) for x, y in g["coordinates"][0]]
            # A GeoJSON ring repeats its first vertex as the last; drop it before averaging
            # so the centroid isn't biased toward whichever vertex the ring starts on.
            ring = pts[:-1] if len(pts) > 1 and pts[0] == pts[-1] else pts
            lat = round(sum(a for a, _ in ring) / len(ring), 6)
            lon = round(sum(b for _, b in ring) / len(ring), 6)
            geom = "POLYGON((" + ", ".join(f"{b} {a}" for a, b in pts) + "))"
        lok = p["siteName"] or ""
        kom = p["siteAreaName"] if p.get("siteAreaDescription") == "Kommune" else ""
        row = make_row(
            p["siteId"],
            lok,
            p["parentName"] or "",
            kom,
            fylke,
            lat,
            lon,
            int(p["accuracy"]),
            geom,
            p["isPrivate"],
            overrides,
        )
        if row:
            out.append(row)
    return out


def main() -> int:
    ap = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    ap.add_argument("--raw", default=f"{DATA_DIR}/artsobs-sites-mobil.json")
    ap.add_argument(
        "--fylke", default="Trøndelag", help="legacy harvest only: fylke name"
    )
    ap.add_argument(
        "--fylke-abbr", default="Tø", help="legacy harvest only: fylke abbreviation"
    )
    args = ap.parse_args()

    overrides = mp.load_overrides()
    raw = json.load(open(args.raw))
    if isinstance(raw, list):  # new mobile API
        rows = rows_from_mobil(raw, overrides)
        # The mobile harvest is PUBLIC-ONLY: ByBoundingBox returns the caller's own privates
        # but also other people's privates, so its `mine` set isn't reliably yours. Don't emit
        # my-localities.csv from here - your own localities come from the ByUser sync instead.
        emit_mine = False
    else:  # legacy GeoJSON FeatureCollection
        rows = rows_from_geojson(
            raw["features"], args.fylke, args.fylke_abbr, overrides
        )
        emit_mine = True  # old endpoint returned only YOUR privates
    rows.sort(key=lambda r: (r["lokalitet"].lower(), r["id"]))

    def write(path, sel):
        with open(path, "w", newline="") as fh:
            w = csv.DictWriter(fh, fieldnames=COLS)
            w.writeheader()
            w.writerows(sel)

    # Public/allmenn localities are bundled, committed and shared in the APK. The maintainer's
    # OWN customs (mine=1, legacy path only) go to a separate gitignored file pushed only to
    # their device - so a friend's APK ships public localities only.
    pub = [r for r in rows if r["public"] == "1"]
    write(CSV, pub)
    npoly = sum(1 for r in pub if r["geometry"])
    msg = f"Wrote {len(pub)} public localities ({npoly} polygons) to {CSV}"
    if emit_mine:
        mine = [r for r in rows if r["mine"] == "1"]
        write(MY_CSV, mine)
        msg += f"; {len(mine)} of yours to {MY_CSV}"
    print(msg)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
