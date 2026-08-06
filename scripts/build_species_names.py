#!/usr/bin/env python3
"""Merge the two flavors' bird checklists into one unified schema carrying all three names.

Output schema (both flavors): latin,norsk,svensk,status,count
  - `norsk` and `svensk` are identical across the two files (one shared, authoritative name table).
  - `status` (red list) and `count` (national report count, for search ranking) stay per-country,
    taken verbatim from the existing per-flavor species.csv (not re-scraped here).

Names come from the IOC World Bird List (multilingual), joined by scientific name, EXCEPT:
  - Norwegian: the existing Norway species.csv is already Artsobservasjoner-authoritative and
    bug-free (it carries the exact strings the import accepts, e.g. "bydue" not IOC's "klippedue"),
    so its `norsk` column is kept as-is. IOC only fills the Norwegian name for species that are on
    the Swedish list but not the Norwegian one.
  - Swedish: the existing Sweden species.csv was built from an unreliable GBIF/Dyntaxa vernacular
    join and carries real errors (e.g. "gulsångare" for Hippolais icterina, which is actually
    "härmsångare"). So IOC is authoritative for Swedish, overriding the old column on divergence —
    except the few deliberate domestic/cage forms the portal expects (KEEP_SE below), which mirror
    the "bydue" case on the Norwegian side.

This is an offline merge of files already in the repo plus the IOC spreadsheet; no network.

    just merge-species-names          # regenerates both app/src/*/assets/species.csv
"""

import argparse
import csv
import os
import sys

import openpyxl

# IOC multilingual master (worldbirdnames.org). Not committed (5 MB); point --ioc at a local copy.
# Columns in the "List" sheet: 3 = scientific (IOC_x.y), 18 = Norwegian, 26 = Swedish.
DEFAULT_IOC = os.path.expanduser("~/workspace/lifelist/ioc_multiling.xlsx")
IOC_LATIN, IOC_NORWEGIAN, IOC_SWEDISH = 3, 18, 26

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
NO_CSV = os.path.join(REPO, "app/src/norway/assets/species.csv")
SE_CSV = os.path.join(REPO, "app/src/sweden/assets/species.csv")

# Swedish names to keep from the old column instead of IOC: names the Artportalen import expects
# that differ from IOC's wild-species/split name (cf. "bydue" for Columba livia on the Norway side).
KEEP_SE = {
    "Gallus gallus",  # tamhöna (domestic fowl), not IOC's "röd djungelhöna"
    "Nymphicus hollandicus",  # nymfparakit (cage bird), not IOC's "nymfkakadua"
}

# Forced names (latin -> (norsk, svensk)) where the portal's current *species* name differs from both
# the harvested checklist and IOC — set both languages authoritatively, no IOC/old-column blending.
NAME_OVERRIDE = {
    # Carrion + hooded crow were lumped into one species, Corvus corone, covering black + grey; both
    # portals now report it as plain kråke/kråka. IOC/our old data still split it (svartkråke/-a). We
    # don't model subspecies (svartkråke = ...corone, gråkråke = ...cornix stay typable at import), so
    # this is the single crow species. See LUMP for the now-redundant Corvus cornix row.
    "Corvus corone": ("kråke", "kråka"),
}

# Subspecies-level rows the harvested checklist still lists as their own "species": fold each into its
# real species (latin -> species latin). The row is dropped and its report count added to the species,
# so search ranking reflects the combined reports. The subspecies name stays typable at import.
LUMP = {
    "Corvus cornix": "Corvus corone",  # hooded crow -> the lumped Corvus corone (kråke/kråka)
}


def norm(s):
    return (s or "").strip().lower()


def load_ioc(path):
    """{scientific(lower) -> (norwegian, swedish)} from the IOC multilingual xlsx."""
    ws = openpyxl.load_workbook(path, read_only=True)["List"]
    it = ws.iter_rows(values_only=True)
    next(it)  # header
    out = {}
    for r in it:
        lat = (r[IOC_LATIN] or "").strip()
        if lat:
            out[lat.lower()] = (
                (r[IOC_NORWEGIAN] or "").strip(),
                (r[IOC_SWEDISH] or "").strip(),
            )
    return out


def load_flavor(path, namecol):
    """Ordered list of the flavor's rows and a {latin -> that-flavor's-name} lookup. `namecol` is
    named explicitly (norsk for Norway, svensk for Sweden) so the merge is idempotent: after the
    first run both files share the unified header, and a positional guess would pick `latin`."""
    rows = list(csv.DictReader(open(path, encoding="utf-8")))
    by_latin = {norm(r["latin"]): r.get(namecol, "").strip() for r in rows}
    return rows, by_latin


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument(
        "--ioc",
        default=DEFAULT_IOC,
        help=f"IOC multiling xlsx (default: {DEFAULT_IOC})",
    )
    args = ap.parse_args()

    ioc = load_ioc(args.ioc)
    no_rows, no_name = load_flavor(NO_CSV, "norsk")
    se_rows, se_name = load_flavor(SE_CSV, "svensk")

    # Shared name table over the union of both checklists.
    latins = {norm(r["latin"]): r["latin"].strip() for r in no_rows}
    latins.update({norm(r["latin"]): r["latin"].strip() for r in se_rows})

    norsk, svensk = {}, {}
    se_fixes = []
    for key, latin in latins.items():
        if latin in NAME_OVERRIDE:
            norsk[key], svensk[key] = NAME_OVERRIDE[latin]
            continue
        ioc_no, ioc_se = ioc.get(key, ("", ""))
        # Norwegian: authoritative Norway column wins; IOC fills Swedish-only species.
        norsk[key] = no_name.get(key) or ioc_no
        # Swedish: IOC authoritative, except deliberate keep-forms; fall back to the old column.
        if latin in KEEP_SE:
            svensk[key] = se_name.get(key, "") or ioc_se
        elif ioc_se:
            svensk[key] = ioc_se
            old = se_name.get(key)
            if old and norm(old) != norm(ioc_se):
                se_fixes.append((latin, old, ioc_se))
        else:
            svensk[key] = se_name.get(key, "")

    n_no = write(NO_CSV, no_rows, norsk, svensk)
    n_se = write(SE_CSV, se_rows, norsk, svensk)

    print(f"Norway: {n_no} species -> {NO_CSV}", file=sys.stderr)
    print(f"Sweden: {n_se} species -> {SE_CSV}", file=sys.stderr)
    print(
        f"\n{len(se_fixes)} Swedish names corrected to IOC (latin | old | new):",
        file=sys.stderr,
    )
    for latin, old, new in sorted(se_fixes):
        print(f"  {latin:28} {old:32} -> {new}", file=sys.stderr)


def write(path, rows, norsk, svensk):
    def count_of(r):
        return int(r.get("count", "").strip() or 0)

    # Report counts this flavor carries for each latin, so a lumped subspecies' count can be folded
    # into its species (both may or may not be present in a given flavor's checklist).
    count_by_latin = {r["latin"].strip(): count_of(r) for r in rows}
    written = 0
    with open(path, "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["latin", "norsk", "svensk", "status", "count"])
        for r in rows:
            latin = r["latin"].strip()
            if latin in LUMP:
                continue  # folded into its species row below; dropped as a standalone species
            key = norm(latin)
            count = count_of(r) + sum(
                count_by_latin.get(sub, 0) for sub, sp in LUMP.items() if sp == latin
            )
            w.writerow(
                [
                    latin,
                    norsk.get(key, ""),
                    svensk.get(key, ""),
                    r.get("status", "").strip(),
                    count,
                ]
            )
            written += 1
    return written


if __name__ == "__main__":
    raise SystemExit(main())
