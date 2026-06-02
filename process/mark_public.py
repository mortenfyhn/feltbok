#!/usr/bin/env python3
"""Mark each locality public (allmenn) or private from its distinct *reporters*.

GBIF's `recordedBy` is "Reporter | Co-observer | ...", the primary reporter first.
A locality reported by >= 2 distinct reporters is genuinely public; one reported by a
single person - however many co-observers they tag along - is that person's private
locality. (Our old observer-count heuristic counted the co-observers too, so private
localities looked public.) This re-counts reporters per locationID and writes a
`public` column onto localities.csv.

It also streams the **full, unmodified occurrence records** to
`localities-occurrences.jsonl`, so any future re-processing (new heuristics, fields,
thresholds) reads from that cache with no re-harvest.

    .venv/bin/python process/mark_public.py
"""
import collections
import csv
import importlib.util
import json
import pathlib
import shutil
import sys

_bl = pathlib.Path(__file__).parent / "build_localities.py"
_spec = importlib.util.spec_from_file_location("bl", _bl)
bl = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(bl)

RAW = "localities-occurrences.jsonl"


def reporter(recorded_by: str) -> str:
    """The primary reporter = the first name in recordedBy (before any co-observers)."""
    return (recorded_by or "").split("|")[0].strip()


def write(path, rows, reporters):
    fields = list(rows[0].keys())
    if "public" not in fields:
        fields.append("public")
    for r in rows:
        r["public"] = "1" if len(reporters.get(r["id"], ())) >= 2 else "0"
    with open(path, "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=fields)
        w.writeheader()
        w.writerows(rows)
    shutil.copy(path, "localities.csv")


def main() -> int:
    path = "app/src/main/assets/localities.csv"
    rows = list(csv.DictReader(open(path)))
    want = {r["id"] for r in rows}
    reporters: dict[str, set] = collections.defaultdict(set)
    bbox = (8.0, 63.5, 9.3, 63.9)
    raw = open(RAW, "w", encoding="utf-8")
    offset, page = 0, 0
    while offset + 300 <= 100_000:
        d = bl._gbif_page({"datasetKey": bl.GBIF_DATASET, "limit": 300, "offset": offset,
                           "hasCoordinate": "true", "taxonKey": 212,
                           "geometry": bl.wkt_box(*bbox)})
        if d is None:
            break
        for o in d.get("results", []):
            raw.write(json.dumps(o, ensure_ascii=False) + "\n")   # full unmodified record
            lid = str(o.get("locationID") or "").strip()
            rb = o.get("recordedBy") or ""
            if lid in want and rb:
                reporters[lid].add(reporter(rb))
        offset += 300
        page += 1
        if page % 15 == 0:
            write(path, rows, reporters)
        pub = sum(1 for v in reporters.values() if len(v) >= 2)
        priv = sum(1 for v in reporters.values() if len(v) == 1)
        print(f"\r  {offset} records, {pub} public / {priv} private so far", end="", file=sys.stderr)
        if d.get("endOfRecords") or not d.get("results"):
            break
    raw.close()
    write(path, rows, reporters)
    npriv = sum(1 for r in rows if r["public"] == "0")
    print(f"\nMarked {npriv}/{len(rows)} localities private. Raw harvest -> {RAW}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
