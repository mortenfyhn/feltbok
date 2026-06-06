#!/usr/bin/env python3
"""Enrich an existing localities.csv with locality polygons from GBIF `footprintWKT`.

GBIF's interpreted bird occurrences carry `footprintWKT` (the locality's drawn area)
for polygon-type localities, in UTM33. We page the same occurrences, take one polygon
per locationID, reproject to WGS84, and add a `geometry` column - joining by locationID
so the existing (already filtered) set of localities is unchanged. Point-type localities
get no geometry; the app draws their radius circle instead.

    .venv/bin/python process/add_footprints.py     # rewrites app/src/main/assets/localities.csv
"""

import csv
import importlib.util
import pathlib
import re
import shutil
import sys

from pyproj import Transformer

_bl = pathlib.Path(__file__).parent / "build_localities.py"
_spec = importlib.util.spec_from_file_location("bl", _bl)
bl = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(bl)

_T = Transformer.from_crs("EPSG:25833", "EPSG:4326", always_xy=True)  # UTM33 -> WGS84


def reproject(wkt: str) -> str:
    """Rewrite a UTM33 WKT's coordinate pairs to WGS84 lon/lat (6 dp)."""

    def conv(m):
        lon, lat = _T.transform(float(m.group(1)), float(m.group(2)))
        return f"{lon:.6f} {lat:.6f}"

    return re.sub(r"(-?\d+(?:\.\d+)?)\s+(-?\d+(?:\.\d+)?)", conv, wkt)


def write(path, rows, found):
    """Write the geometry column from `found` and mirror to repo root (for push-data)."""
    fields = list(rows[0].keys())
    if "geometry" not in fields:
        fields.append("geometry")
    for r in rows:
        r["geometry"] = found.get(r["id"], "")
    with open(path, "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=fields)
        w.writeheader()
        w.writerows(rows)
    shutil.copy(path, "localities.csv")


def main() -> int:
    path = "app/src/main/assets/localities.csv"
    rows = list(csv.DictReader(open(path)))
    want = {r["id"] for r in rows}
    bbox = (8.0, 63.5, 9.3, 63.9)
    found: dict[str, str] = {}
    offset, stale, page = 0, 0, 0
    while offset + 300 <= 100_000 and stale < 30:  # stop when polygons stop coming
        d = bl._gbif_page(
            {
                "datasetKey": bl.GBIF_DATASET,
                "limit": 300,
                "offset": offset,
                "hasCoordinate": "true",
                "taxonKey": 212,
                "geometry": bl.wkt_box(*bbox),
            }
        )
        if d is None:
            break
        new = 0
        for o in d.get("results", []):
            lid = str(o.get("locationID") or "").strip()
            w = o.get("footprintWKT") or ""
            if lid in want and lid not in found and w.upper().startswith("POLYGON"):
                found[lid] = reproject(w)
                new += 1
        stale = 0 if new else stale + 1
        offset += 300
        page += 1
        if page % 15 == 0:  # flush partial results periodically
            write(path, rows, found)
        print(
            f"\r  {offset} records, {len(found)} polygons (stale {stale})",
            end="",
            file=sys.stderr,
        )
        if d.get("endOfRecords") or not d.get("results"):
            break
    print(file=sys.stderr)
    write(path, rows, found)
    print(f"Added polygons to {len(found)}/{len(rows)} localities.", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
