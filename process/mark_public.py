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


OVERRIDES = pathlib.Path(__file__).parent / "locality_overrides.csv"


def load_overrides() -> dict:
    """Manual public/private corrections {id: '0'|'1'}, applied after the heuristic."""
    if not OVERRIDES.exists():
        return {}
    out = {}
    with open(OVERRIDES, newline="") as f:
        for row in csv.DictReader(r for r in f if not r.lstrip().startswith("#")):
            if row.get("id"):
                out[row["id"].strip()] = row["public"].strip()
    return out


def write(path, rows, reporters):
    fields = list(rows[0].keys())
    if "public" not in fields:
        fields.append("public")
    overrides = load_overrides()
    for r in rows:
        r["public"] = "1" if len(reporters.get(r["id"], ())) >= 2 else "0"
        r["public"] = overrides.get(r["id"], r["public"])   # manual correction wins
    with open(path, "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=fields)
        w.writeheader()
        w.writerows(rows)
    shutil.copy(path, "localities.csv")


def occurrences(from_raw: bool):
    """Yield occurrence dicts: from the cached JSONL (instant) or a fresh GBIF harvest
    (which also writes the cache)."""
    if from_raw:
        with open(RAW, encoding="utf-8") as f:
            for line in f:
                yield json.loads(line)
        return
    bbox = (8.0, 63.5, 9.3, 63.9)
    raw = open(RAW, "w", encoding="utf-8")
    offset = 0
    while offset + 300 <= 100_000:
        d = bl._gbif_page({"datasetKey": bl.GBIF_DATASET, "limit": 300, "offset": offset,
                           "hasCoordinate": "true", "taxonKey": 212,
                           "geometry": bl.wkt_box(*bbox)})
        if d is None:
            break
        for o in d.get("results", []):
            raw.write(json.dumps(o, ensure_ascii=False) + "\n")   # full unmodified record
            yield o
        offset += 300
        print(f"\r  harvested {offset} records", end="", file=sys.stderr)
        if d.get("endOfRecords") or not d.get("results"):
            break
    raw.close()
    print(file=sys.stderr)


def main() -> int:
    from_raw = "--from-raw" in sys.argv
    path = "app/src/main/assets/localities.csv"
    rows = list(csv.DictReader(open(path)))
    want = {r["id"] for r in rows}
    reporters: dict[str, set] = collections.defaultdict(set)
    for o in occurrences(from_raw):
        lid = str(o.get("locationID") or "").strip()
        rb = o.get("recordedBy") or ""
        if lid in want and rb:
            reporters[lid].add(reporter(rb))
    write(path, rows, reporters)
    npriv = sum(1 for r in rows if r["public"] == "0")
    print(f"\nMarked {npriv}/{len(rows)} localities private. Raw harvest -> {RAW}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
