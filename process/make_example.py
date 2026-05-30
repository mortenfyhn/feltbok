#!/usr/bin/env python3
"""Write an example output workbook with a few fake observations.

Useful as a fixture: it exercises the same write path as process.py (header
lookup, accuracy buckets, live reverse-geocoding) so you can try the
Artsobservasjoner import without recording anything. Run from the repo root:

    python process/make_example.py                 # with locality names
    python process/make_example.py --no-locality   # blank Lokalitetsnavn

Use --no-locality to test whether the site infers the locality from the GPS
coordinates when no name is given.
"""
import argparse
import importlib.util
import sys

from openpyxl import load_workbook

# Load process.py as a properly-registered module so its dataclass works.
_spec = importlib.util.spec_from_file_location("process", "process/process.py")
proc = importlib.util.module_from_spec(_spec)
sys.modules["process"] = proc
_spec.loader.exec_module(proc)

TEMPLATE = "docs/artsobs-template-v3.0.xlsx"

# Dated to yesterday so a "future observation" rejection can't muddy a test.
DATE = "29.05.2026"

# (clip, parsed-observation) pairs — three plausible Norwegian field notes.
EXAMPLES = [
    (
        proc.Clip("a.m4a", DATE, "08:33", 59.912340, 10.756780, 8),
        {"species": "Gulspurv", "count": 3, "activity": "Sang/spill, ikke hekking",
         "comment": "Syngende fra busktopp", "uncertain": False},
    ),
    (
        proc.Clip("b.m4a", DATE, "09:14", 58.751200, 5.553400, 22),
        {"species": "Storspove", "count": 1, "activity": "Overflygende",
         "comment": "Trakk mot sør langs stranda", "uncertain": False},
    ),
    (
        proc.Clip("c.m4a", DATE, "10:02", 63.430500, 10.395300, 15),
        {"species": "Løvsanger", "count": 2, "activity": "Næringssøkende",
         "comment": "I løvskog", "uncertain": True},
    ),
]


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--no-locality", action="store_true",
                    help="leave Lokalitetsnavn blank to test GPS-based inference")
    ap.add_argument("-o", "--output")
    args = ap.parse_args()
    output = args.output or (
        "eksempel_uten_lokalitet.xlsx" if args.no_locality
        else "eksempel_observasjoner.xlsx")

    wb = load_workbook(TEMPLATE)
    cols = proc.fugl_headers(wb)
    buckets = proc.accuracy_buckets(wb)
    ws = wb["Fugl"]

    row = 3
    for clip, obs in EXAMPLES:
        if args.no_locality:
            locality, lat, lon = "", clip.lat, clip.lon
        else:
            locality, lat, lon = proc.reverse_geocode(clip.lat, clip.lon)
        print(f"  {obs['species']:12s} -> {locality or '(blank)'}")
        proc.write_row(ws, row, cols, {
            "Artsnavn": obs["species"],
            "Lokalitetsnavn": locality,
            "Nord": lat,
            "Øst": lon,
            "Nøyaktighet": proc.nearest_bucket(clip.acc, buckets),
            "Fra dato": clip.date,
            "Til dato": clip.date,
            "Fra klokkeslett": clip.time,
            "Til klokkeslett": clip.time,
            "Antall": obs["count"],
            "Aktivitet": obs["activity"],
            proc.pick(cols, "Merknad (synlig for alle)", "Kommentar (synlig for alle)"): obs["comment"],
            proc.pick(cols, "Privat merknad (kun synlig for deg selv)",
                      "Privat kommentar (kun synlig for deg selv)"): f"[{clip.path}] eksempel",
            "Usikker artsbestemming": "X" if obs["uncertain"] else None,
        })
        row += 1

    wb.save(output)
    print(f"Wrote {len(EXAMPLES)} example rows to {output}")


if __name__ == "__main__":
    main()
