#!/usr/bin/env python3
"""Build a no-coordinates import sheet to test how Artsobservasjoner matches
locality *names* — so we know exactly what string the app must emit.

Background: importing *with* coordinates creates a new locality per point (the
duplicate mess). Importing *without* coordinates matches the Lokalitetsnavn to
an existing public locality. The registry's name is hierarchical/qualified, e.g.
"Ørndalen, Sistranda, Frøya, Tø" (sublocality, parent, kommune, fylke-abbr).

For each real Frøya locality below we emit three rows, no coordinates, to see
which form links to the existing locality vs creates a new one:
  A = exact registry name (full qualified)
  B = "sub, kommune, fylke"  (your suggested form — parent dropped)
  C = bare sublocality name

Paste the result into Artsobservasjoner -> Rapportere -> Importer observasjoner
(do NOT publish). The validation/preview tells you, per row, whether it matched
an existing locality or wants to create a new one. The private comment on each
row says which variant it is and what we expect.

    python process/make_locality_test.py        # -> lokalitetstest.xlsx
"""
import importlib.util
import sys

from openpyxl import load_workbook

_spec = importlib.util.spec_from_file_location("process", "process/process.py")
proc = importlib.util.module_from_spec(_spec)
sys.modules["process"] = proc
_spec.loader.exec_module(proc)

TEMPLATE = "docs/artsobs-template-v3.0.xlsx"
DATE = "31.05.2026"            # today; not a future date
SPECIES = "Kjøttmeis"          # common, unambiguous — this test is about localities

# Real, established Frøya localities straight from the registry (GBIF/Artsdatabanken):
# full qualified name exactly as stored.
LOCALITIES = [
    "Ørndalen, Sistranda, Frøya, Tø",
    "Flatvalsundet, Flatval, Frøya, Tø",
    "Ellingsundet, Uttian, Frøya, Tø",
]


def variants(full: str):
    """(label, name, expectation) for the three name forms of one locality."""
    parts = [p.strip() for p in full.split(",")]
    sub, kommune, fylke = parts[0], parts[-2], parts[-1]
    return [
        ("A full",  full,                          "forventer: kobles til eksisterende lokalitet"),
        ("B s+k+f", f"{sub}, {kommune}, {fylke}",   "forventer: ? (din foreslåtte form, uten mellomledd)"),
        ("C bar",   sub,                            "forventer: ? (tvetydig / ny lokalitet)"),
    ]


def main() -> None:
    wb = load_workbook(TEMPLATE)
    cols = proc.fugl_headers(wb)
    ws = wb["Fugl"]
    priv = proc.pick(cols, "Privat merknad (kun synlig for deg selv)",
                     "Privat kommentar (kun synlig for deg selv)")
    pub = proc.pick(cols, "Merknad (synlig for alle)", "Kommentar (synlig for alle)")

    row = 3
    n = 0
    for full in LOCALITIES:
        for label, name, expect in variants(full):
            t = f"{8 + n // 6:02d}:{(n * 7) % 60:02d}"   # spread times a little
            proc.write_row(ws, row, cols, {
                "Artsnavn": SPECIES,
                "Lokalitetsnavn": name,
                "Fra dato": DATE,
                "Til dato": DATE,
                "Fra klokkeslett": t,
                "Til klokkeslett": t,
                "Antall": 1,
                pub: "TEST – ikke publiser",
                priv: f"{label}: «{name}» — {expect}",
            })
            print(f"  {label:7s} {name}")
            row += 1
            n += 1

    out = "lokalitetstest.xlsx"
    wb.save(out)
    print(f"\nWrote {n} rows ({len(LOCALITIES)} localities × 3 name forms) to {out}")
    print("Paste into Importer observasjoner (don't publish); check per-row locality match.")


if __name__ == "__main__":
    main()
