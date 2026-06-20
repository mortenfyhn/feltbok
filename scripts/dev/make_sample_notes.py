#!/usr/bin/env python3
"""Emit a notes.json of varied observations for eyeballing the notes list on the debug build.

There is no in-app seeding: notes.json lives in the app's internal filesDir, which a plain
`adb push` can't reach, so the `just seed-notes` recipe pipes this script's stdout straight into
the debug app's file via `run-as`. Timestamps are relative to now (so day-grouping shows "I dag"/
"I går"), and the rows deliberately span the combinations that stress the row layout: every sex,
the short/long ages, blank vs long activities, with/without locality and comments, each red-list
colour, an uncertain determination, a long species name, and a near-duplicate pair (same species
and place, different age/sex) — the exact "which is the chicks, which the parents" case the
age/sex/activity preview exists for.

Run via `just seed-notes` (writes the debug app), or `python scripts/dev/make_sample_notes.py`
to inspect the JSON.
"""

import json
import time

# (species, latin, status) — status drives the badge colour; latin must match species.csv.
GRAAMAAKE = ("gråmåke", "Larus argentatus", "VU")  # red
STORSPOVE = ("storspove", "Numenius arquata", "EN")  # red
STORSKARV = ("storskarv", "Phalacrocorax carbo", "NT")  # red
NILAND = ("niland", "Alopochen aegyptiaca", "SE")  # black (alien)
STOKKAND = ("stokkand", "Anas platyrhynchos", "")
KJOTTMEIS = ("kjøttmeis", "Parus major", "")
KRAAKE = ("kråke", "Corvus cornix", "")
SVARTTROST = ("svarttrost", "Turdus merula", "")
STEINSKVETT = ("svartstrupesteinskvett", "Oenanthe pleschanka", "")  # long name
FLUESNAPPER = ("svarthvit fluesnapper", "Ficedula hypoleuca", "")  # long name

UNKNOWN_COUNT = -1
MINUTE = 60_000
HOUR = 60 * MINUTE
DAY = 24 * HOUR

# Trondheim-ish coordinates; the list doesn't show them, so one point is fine for all.
LAT, LON = 63.43, 10.40

# Each row: (minutes-ago, species-tuple, count, age, sex, activity, locName, pub, priv, uncertain)
ROWS = [
    # Full house: red badge, every field set, locality present.
    (5, GRAAMAAKE, 4, "Adult", "Hann", "Rastende", "Munkholmen", "", "", False),
    # Near-duplicate of the above (same species + place) but the chicks: different age/sex/count.
    (8, GRAAMAAKE, 2, "1K", "Hunn", "Rastende", "Munkholmen", "", "", False),
    # Unknown count (-> "?"), long activity, NO locality (the row that used to balloon too tall).
    (
        40,
        STORSPOVE,
        UNKNOWN_COUNT,
        "1K",
        "Hunn",
        "Sang/spill, ikke hekking",
        "",
        "",
        "",
        False,
    ),
    # Pair symbol, public comment (blue i), black alien badge.
    (
        70,
        NILAND,
        2,
        "Adult",
        "I par",
        "Trekkende",
        "Nidelva",
        "To voksne med unger",
        "",
        False,
    ),
    # Hunnfarget symbol, long locality (ellipsizes), private comment (grey i).
    (
        95,
        STOKKAND,
        12,
        "2K+",
        "Hunnfarget",
        "Næringssøkende",
        "Ringve botaniske hage",
        "",
        "Ved dammen",
        False,
    ),
    # Pulli age (-> "Pull"), no sex, no activity, red NT badge.
    (130, STORSKARV, 35, "Pulli", "", "", "Korsvika", "", "", False),
    # Long species name + uncertain determination (name?) + all fields — width stress.
    (160, STEINSKVETT, 1, "2K", "Hann", "Stasjonær", "Estenstadmarka", "", "", True),
    # Baseline: no age/sex/activity at all (row should look like before the feature).
    (200, KJOTTMEIS, 1, "", "", "", "Lade", "", "", False),
    # Long name + long activity + both comments — the worst-case crowding.
    (
        240,
        FLUESNAPPER,
        6,
        "Adult",
        "I par",
        "Reir med egg eller unger",
        "Bymarka",
        "Hekkefunn",
        "Kasse 12",
        False,
    ),
    # Big count, sex only.
    (300, KRAAKE, 100, "", "Hann", "Overflygende", "Trondheim sentrum", "", "", False),
    # Yesterday — exercises the "I går" day header.
    (
        DAY // MINUTE + 120,
        SVARTTROST,
        3,
        "1K+",
        "Hunn",
        "Næringssøkende",
        "Marienborg",
        "",
        "Sang fra hagen",
        False,
    ),
]


def build(now_ms):
    notes = []
    for i, (
        mins,
        (sp, latin, status),
        count,
        age,
        sex,
        act,
        loc,
        pub,
        priv,
        unc,
    ) in enumerate(ROWS):
        t = now_ms - mins * MINUTE
        notes.append(
            {
                "id": t + i,  # creation time; +i keeps ids unique if two share a minute
                "time": t,
                "species": sp,
                "latin": latin,
                "count": count,
                "age": age,
                "activity": act,
                "sex": sex,
                "publicComment": pub,
                "privateComment": priv,
                "locName": loc,
                "locFull": f"{loc}, Trondheim, Trøndelag" if loc else "",
                "lat": LAT,
                "lon": LON,
                "newLoc": False,
                "locRadius": 0,
                "uncertain": unc,
                "kommune": "Trondheim",
            }
        )
    return notes


if __name__ == "__main__":
    # Single-line JSON: `adb shell`'s PTY would translate embedded newlines, so keep it newline-free.
    print(json.dumps(build(int(time.time() * 1000)), ensure_ascii=False))
