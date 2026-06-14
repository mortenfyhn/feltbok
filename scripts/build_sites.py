#!/usr/bin/env python3
"""Build app/src/main/assets/localities.csv from a mobile-API site harvest.

The harvest (harvest_sites_mobil.py) carries everything we need straight from
Artsobservasjoner, so this is the whole pipeline now - no GBIF, no footprints, no
observer/polygon heuristic, no FindSitesByName flag fetch. It is a flat JSON array of
site rows, already in WGS84 with a per-row county (so no reprojection, no fylke guess).

`name` -> lokalitet (the exact registered name paste-import matches), geometry -> lat/lon
+ a WGS84 POLYGON or none, accuracy -> radius (0 = point), isPrivate -> public/mine,
`parentSiteId` resolved against the harvest's own id->name map -> hovedlokalitet. Manual
corrections in locality_overrides.csv (keyed by siteId) still win.

We ship only the PUBLIC (allmenn) localities: ByBoundingBox returns private sites too -
including other people's - so its private set isn't reliably yours. Your own privates come
from the in-app ByUser sync instead, not this build.

    .venv/bin/python scripts/build_sites.py [--raw FILE]
"""

import argparse
import csv
import json
import os
import pathlib

# Where the harvested raw JSON lives (kept out of the repo - it's large). Override with
# FELTBOK_DATA_DIR; pass --raw to point at a specific file.
DATA_DIR = os.environ.get(
    "FELTBOK_DATA_DIR", "/home/morten/Documents/projects/app-feltbok"
)
CSV = "app/src/main/assets/localities.csv"  # public/allmenn - bundled + committed + shared
OVERRIDES = pathlib.Path(__file__).parent / "locality_overrides.csv"


def load_overrides() -> dict:
    """Manual public/private corrections {siteId: '0'|'1'}, applied after the isPrivate flag."""
    if not OVERRIDES.exists():
        return {}
    out = {}
    with open(OVERRIDES, newline="") as f:
        for row in csv.DictReader(r for r in f if not r.lstrip().startswith("#")):
            if row.get("id"):
                out[row["id"].strip()] = row["public"].strip()
    return out


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
    "super",
]


def rows_from_mobil(raw, overrides):
    """A flat list of mobile-API site rows, already WGS84 with a per-row county. Returns the
    CSV rows for sites that are public (or forced public by an override)."""
    names = {r["id"]: r["name"] for r in raw}  # resolve parentSiteId -> parent name
    # A site is a superlocality (has sublocalities) iff some other site names it as parent.
    # Derived from the FULL harvest, so a public super with only private children still counts.
    supers = {r["parentSiteId"] for r in raw if r.get("parentSiteId")}
    out = []
    for r in raw:
        public = overrides.get(str(r["id"]), "0" if r["isPrivate"] else "1")
        if public != "1":
            continue
        geom = ""
        if r.get("isPolygon") and r.get("polygonCoordinates"):
            pc = r[
                "polygonCoordinates"
            ]  # [[lon, lat], ...] in WGS84, already parsed here
            coords = json.loads(pc) if isinstance(pc, str) else pc
            if coords and coords[0] != coords[-1]:  # close the ring for valid WKT
                coords = coords + [coords[0]]
            # 6 decimals (~0.1 m) matches the point lat/lon precision and is plenty for a
            # locality outline; the raw feed's 8 decimals were dead weight in the bundled CSV.
            geom = (
                "POLYGON(("
                + ", ".join(f"{round(lon, 6)} {round(lat, 6)}" for lon, lat in coords)
                + "))"
            )
        out.append(
            {
                "id": str(r["id"]),
                "lokalitet": r["name"] or "",
                "hovedlokalitet": names.get(r.get("parentSiteId"), "") or "",
                "kommune": r.get("municipalityName") or "",
                "fylke": r.get("countyName") or "",
                "lat": round(r["latitude"], 6),
                "lon": round(r["longitude"], 6),
                "count": 0,
                "observers": 0,
                "radius": int(r.get("accuracy") or 0),
                "geometry": geom,
                "public": "1",
                "mine": "0",  # public build never ships privates; mine comes from ByUser sync
                "super": "1" if r["id"] in supers else "0",
            }
        )
    return out


def main() -> int:
    ap = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    ap.add_argument("--raw", default=f"{DATA_DIR}/artsobs-sites-mobil.json")
    args = ap.parse_args()

    rows = rows_from_mobil(json.load(open(args.raw)), load_overrides())
    rows.sort(key=lambda r: (r["lokalitet"].lower(), r["id"]))

    # Public/allmenn localities are bundled, committed and shared in the APK.
    with open(CSV, "w", newline="") as fh:
        w = csv.DictWriter(fh, fieldnames=COLS)
        w.writeheader()
        w.writerows(rows)
    npoly = sum(1 for r in rows if r["geometry"])
    print(f"Wrote {len(rows)} public localities ({npoly} polygons) to {CSV}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
