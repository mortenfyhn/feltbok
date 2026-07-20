#!/usr/bin/env python3
"""Emit a notes.json of varied observations for the debug build. It serves two purposes:

1. **Eyeballing the notes list / editor UI** — rows deliberately span the combinations that stress
   the row layout: every sex, short/long ages, blank vs long activities, with/without locality and
   comments, each red-list colour, an uncertain determination, long species/locality names, a
   near-duplicate pair (same species+place, different age/sex — the "which is chicks, which parents"
   case the age/sex preview exists for), and the co-observer variations (#128): none, one, a few,
   and a 12-name crowd (stresses the "+N" badge and 10+ Medobservatør columns).
2. **A live paste-import test** — seed clean, then export FROM THE APP and paste into
   Artsobservasjoner "Importer observasjoner". So the rows also cover every path `exportTsv` takes:
   a name-only registry locality, a brand-new spot (coordinates + radius, mints a private locality —
   each import mints a fresh dupe), a blank Antall (unknown count), the uncertain flag, a same-day
   and a multi-day time range, and the two no-time cases (#155: date kept, klokkeslett blank — single
   day and multi-day). To keep import errors meaningful, real inputs the site validates are used:
   localities are **real, globally-unique public localities** (unique across the whole country, so
   they resolve to the public locality even without kommune-scoping the import — no "matchet flere
   allmenne lokaliteter"), co-observers are **real registered users** (made-up or ambiguous names
   fail to resolve), and an "I par" row uses an even count (the site requires partall). So a clean
   seed should now import with **no errors** — any error is a real signal worth chasing.

Each row's first argument is a short `why` — what that row is meant to exercise — so the intent
lives next to the data (it's dev documentation, not emitted to the JSON).

There is no in-app seeding: notes.json lives in the app's internal filesDir, which a plain
`adb push` can't reach, so `just seed` pushes this script's output to the debug app's external
files dir, and the app imports it on next launch. Timestamps are relative to now (so day-grouping
shows "I dag"/"I går").

Activities/ages/sexes must be exact values from the app's Norwegian Country vocabularies, or the
import rejects the row. Run via `just seed`, or `python scripts/dev/make_sample_notes.py` to inspect
the JSON (add `--why` to instead print each row's purpose).
"""

import json
import sys
import time

# (species, latin, status) — status drives the badge colour; latin must match species.csv.
GRAAMAAKE = ("gråmåke", "Larus argentatus", "VU")  # red
STORSPOVE = ("storspove", "Numenius arquata", "EN")  # red
STORSKARV = ("storskarv", "Phalacrocorax carbo", "NT")  # red
NILAND = ("niland", "Alopochen aegyptiaca", "SE")  # black (alien)
STOKKAND = ("stokkand", "Anas platyrhynchos", "")
KJOTTMEIS = ("kjøttmeis", "Parus major", "")
KRAAKE = (
    "kråke",
    "Corvus corone",
    "",
)  # carrion+hooded crow lumped -> Corvus corone (#155)
SVARTTROST = ("svarttrost", "Turdus merula", "")
FISKEMAAKE = ("fiskemåke", "Larus canus", "")
STEINSKVETT = (
    "svartstrupesteinskvett",
    "Oenanthe pleschanka",
    "",
)  # long name (no badge)
FLUESNAPPER = (
    "svarthvit fluesnapper",
    "Ficedula hypoleuca",
    "",
)  # long name (no badge)
# Long names that ARE red-listed, so a long-name row still shows a badge (status is a live
# species.csv lookup by latin, so these latins must match the checklist).
AFRIKASVARTSTRUPE = (
    "afrikasvartstrupe",
    "Saxicola rubicola",
    "EN",
)  # long name + red badge
GRESSHOPPESANGER = (
    "gresshoppesanger",
    "Locustella naevia",
    "NT",
)  # long name + red badge
DOBBELTBEKKASIN = ("dobbeltbekkasin", "Gallinago media", "NT")  # long name + red badge
SVARTHALESPOVE = ("svarthalespove", "Limosa limosa", "CR")  # long name + red badge

UNKNOWN_COUNT = -1
MINUTE = 60_000
HOUR = 60 * MINUTE
DAY = 24 * HOUR

# Trondheim-ish coordinates; the list doesn't show them, so one point is fine for all.
LAT, LON = 63.43, 10.40
# A brand-new spot's own coordinates (a real point near Ladehammeren, Trondheim), for the one
# newLoc row that exercises the coordinate + radius export (mints a private locality on import).
NEW_LAT, NEW_LON = 63.4485, 10.4380

# A long, real, globally-unique Trondheim public locality — long enough to ellipsize in the row
# (UI stress) yet a name the import still validates. Real spots avoid the "matchet flere" errors that
# common names (Munkholmen, Bymarka, …) hit; #-marked ones below are all globally-unique + public.
LONG_SITE = "sjøområdet mellom Brattøra og Munkholmen"

# Real registered Artsobservasjoner users, so the paste-import links co-observers instead of
# failing on unknown/ambiguous names. PARTY is reused across rows for the sticky-følget look (#128);
# CROWD is a 12-name group that stresses the "+N" badge and the 10+ Medobservatør export columns.
# (Names that resolve to several users — e.g. "Monica Esbensen" — fail as ambiguous, so aren't used.)
PARTY = ["Kristin Bøkestad", "Christian Sjødin"]
CROWD = [
    "Hanna Tomasgård Olstad",
    "Helge Olav Otterstad",
    "Kjersti Nes",
    "Hilde K Nilsen",
    "Johannes Borgfjord",
    "Marit Stormoen",
    "Line Karlsen",
    "Ida Aasland",
    "Anders Bratlie",
    "Linda Jakobsen",
    "Mari Myrebøe",
    "John Thomas Renå",
]


def note(
    why,
    mins,
    species,
    count,
    age,
    sex,
    act,
    loc,
    pub="",
    priv="",
    unc=False,
    coobs=None,
    dur_min=None,
    no_time=False,
    new_loc=False,
    radius=0,
):
    """One row. `why` = what it exercises (dev doc, not exported). `mins` = minutes before now
    (drives the day header). `dur_min`, if set, adds an end time that many minutes after the start
    (a range; large values span days — keep the end in the past, the site rejects future times).
    `no_time` marks the time-of-day unspecified. `new_loc` exports coordinates + `radius`."""
    return dict(
        why=why,
        mins=mins,
        species=species,
        count=count,
        age=age,
        sex=sex,
        act=act,
        loc=loc,
        pub=pub,
        priv=priv,
        unc=unc,
        coobs=coobs or [],
        dur_min=dur_min,
        no_time=no_time,
        new_loc=new_loc,
        radius=radius,
    )


ROWS = [
    # --- Today ("I dag") ---
    note(
        "full house: red badge, every field set, locality, a 2-person party",
        5,
        GRAAMAAKE,
        4,
        "Adult",
        "Hann",
        "Rastende",
        "Ladehammeren",
        coobs=PARTY,
    ),
    note(
        "near-duplicate of the row above (the chicks): same species+place, different age/sex/count",
        8,
        GRAAMAAKE,
        2,
        "1K",
        "Hunn",
        "Rastende",
        "Ladehammeren",
        coobs=PARTY,
    ),
    note(
        "unknown count ('?' in list / blank Antall on export), long activity, solo",
        40,
        STORSPOVE,
        UNKNOWN_COUNT,
        "1K",
        "Hunn",
        "Sang/spill, ikke hekking",
        "Ilsvika",
    ),
    note(
        "pair symbol, public comment, black alien badge, one co-observer",
        70,
        NILAND,
        2,
        "Adult",
        "I par",
        "Trekkende",
        "Ilabekken",
        pub="To voksne med unger",
        coobs=["Hege Bjotveit"],
    ),
    note(
        "hunnfarget symbol, long locality (ellipsizes), private comment, solo",
        95,
        STOKKAND,
        12,
        "2K+",
        "Hunnfarget",
        "Næringssøkende",
        LONG_SITE,
        priv="Ved dammen",
    ),
    note(
        "pulli, no sex/activity, red NT badge, 12-name crowd (-> '+12' badge, 12 Medobs cols)",
        130,
        STORSKARV,
        35,
        "Pulli",
        "",
        "",
        "Leangenbukta",
        coobs=CROWD,
    ),
    note(
        "long species name + uncertain determination + one co-observer — width stress",
        160,
        STEINSKVETT,
        1,
        "2K",
        "Hann",
        "Stasjonær",
        "Estenstaddammen",
        unc=True,
        coobs=["Eivind Bering"],
    ),
    note(
        "baseline: no age/sex/activity/co-observers at all",
        200,
        KJOTTMEIS,
        1,
        "",
        "",
        "",
        "Ilsvika",
    ),
    note(
        "same-day time range (Fra < Til, same date) with a public comment",
        230,
        SVARTTROST,
        3,
        "Adult",
        "Hann",
        "Næringssøkende",
        "Baklidammen",
        pub="sang i to strofer",
        dur_min=90,
        coobs=["Gro Tång"],
    ),
    note(
        "brand-new spot: exports coordinates + 1 m radius (mints a private locality on import)",
        260,
        STORSPOVE,
        1,
        "Adult",
        "",
        "Rastende",
        "Ny plass ved Ladehammeren",
        pub="ny lokalitet",
        new_loc=True,
        radius=1,
    ),
    note(
        "no-time, single day (#155): date kept, both klokkeslett blank",
        300,
        KRAAKE,
        1,
        "Adult",
        "",
        "Næringssøkende",
        "Haukvatnet",
        pub="lagt inn i etterkant, husker ikke klokkeslettet",
        no_time=True,
    ),
    # --- Yesterday ("I går") and older — a fuller batch; rows rarely fit this easily in the field ---
    note(
        "'everything': big count, uncertain, red badge, age+sex, BOTH comments, a party",
        DAY // MINUTE + 60,
        STORSPOVE,
        1000,
        "Adult",
        "Hann",
        "Rastende",
        "Kyvatnet",
        pub="Stor ansamling på fjæra",
        priv="Talt i teleskop",
        unc=True,
        coobs=PARTY,
    ),
    note(
        "multi-day range WITH times (Til date > Fra date): started yesterday, ended today",
        DAY // MINUTE + 300,
        FISKEMAAKE,
        8,
        "Adult",
        "Hunnfarget",
        "Rastende",
        "Være",
        pub="samme flokk begge dager",
        dur_min=DAY // MINUTE + 120,
    ),
    note(
        "no-time, MULTI-day range (#155): both dates kept, both klokkeslett blank (ends in the past)",
        3 * DAY // MINUTE,
        FISKEMAAKE,
        4,
        "Adult",
        "",
        "Rastende",
        "Jonsvatnet",
        pub="flere dager, ukjent klokkeslett",
        dur_min=DAY // MINUTE,
        no_time=True,
    ),
    note(
        "plain row carrying BOTH a public and a private comment",
        DAY // MINUTE + 95,
        KJOTTMEIS,
        3,
        "1K",
        "Hann",
        "Ved fôring",
        "Theisendammen",
        pub="Ved fuglematerne",
        priv="Hann med fargering",
    ),
    note(
        "worst case: everything + a really long species name (red-listed, so the badge still shows)",
        DAY // MINUTE + 150,
        AFRIKASVARTSTRUPE,
        12,
        "2K",
        "Hann",
        "Stasjonær",
        "Lianvatnet",
        pub="Uvanlig langt nord",
        priv="Fotografert",
        unc=True,
        coobs=["Kristin Bøkestad"],
    ),
    note(
        "worst case: everything + a really long locality name",
        DAY // MINUTE + 210,
        STORSPOVE,
        40,
        "1K",
        "Hunn",
        "Næringssøkende",
        LONG_SITE,
        pub="Flokk på mudderflata",
        priv="Sjekk ringmerking",
        unc=True,
        coobs=PARTY,
    ),
    note(
        "worst case: BOTH names long (long species + long locality)",
        DAY // MINUTE + 280,
        AFRIKASVARTSTRUPE,
        8,
        "2K+",
        "Hunnfarget",
        "Sang/spill, ikke hekking",
        LONG_SITE,
        pub="Sang fra busk",
        priv="Samme som i fjor?",
        unc=True,
        coobs=PARTY + ["Hege Bjotveit"],
    ),
    note(
        "ordinary row, another long red-listed name",
        DAY // MINUTE + 360,
        GRESSHOPPESANGER,
        1,
        "2K",
        "Hann",
        "Sang/spill, ikke hekking",
        "Devlebukta",
        pub="Snerpende sang",
        coobs=["Eivind Bering"],
    ),
    note(
        "ordinary row, private comment only",
        DAY // MINUTE + 440,
        DOBBELTBEKKASIN,
        2,
        "Adult",
        "",
        "Overflygende",
        "Jonsvatnet",
        priv="Leik i skumringa",
    ),
    note(
        "'I par' with an EVEN count (the site requires partall), single co-observer",
        DAY // MINUTE + 520,
        SVARTHALESPOVE,
        6,
        "Adult",
        "I par",
        "Næringssøkende",
        "Være",
        coobs=["Gro Tång"],
    ),
    note(
        "plainest row: count + activity only",
        DAY // MINUTE + 680,
        KRAAKE,
        20,
        "",
        "",
        "Overflygende",
        "Ladehammeren",
    ),
]


def build(now_ms):
    notes = []
    for i, r in enumerate(ROWS):
        sp, latin, status = r["species"]
        t = now_ms - r["mins"] * MINUTE
        new_loc = r["new_loc"]
        n = {
            "id": t + i,  # creation time; +i keeps ids unique if two share a minute
            "time": t,
            "species": sp,
            "latin": latin,
            "count": r["count"],
            "age": r["age"],
            "activity": r["act"],
            "sex": r["sex"],
            "publicComment": r["pub"],
            "privateComment": r["priv"],
            "locName": r["loc"],
            # A registry locality carries a qualified fullname; a brand-new spot has none.
            "locFull": ""
            if new_loc or not r["loc"]
            else f"{r['loc']}, Trondheim, Trøndelag",
            "lat": NEW_LAT if new_loc else LAT,
            "lon": NEW_LON if new_loc else LON,
            "newLoc": new_loc,
            "locRadius": r["radius"],
            "uncertain": r["unc"],
            "coObservers": r["coobs"],
            "kommune": "Trondheim",
        }
        # Optional fields mirror noteToJson: present only when meaningful (endTime omitted when null,
        # timeUnknown omitted when false) so the seed round-trips exactly like real saved notes.
        if r["dur_min"]:
            n["endTime"] = t + r["dur_min"] * MINUTE
        if r["no_time"]:
            n["timeUnknown"] = True
        notes.append(n)
    return notes


if __name__ == "__main__":
    if (
        "--why" in sys.argv
    ):  # print the per-row purposes, for a quick legend of what's seeded
        for i, r in enumerate(ROWS, 1):
            print(f"{i:2}. {r['species'][0]} — {r['why']}")
    else:
        # Single-line JSON: `adb shell`'s PTY would translate embedded newlines, so keep it newline-free.
        print(json.dumps(build(int(time.time() * 1000)), ensure_ascii=False))
