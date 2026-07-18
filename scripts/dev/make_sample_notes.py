#!/usr/bin/env python3
"""Emit a notes.json of varied observations for eyeballing the notes list on the debug build.

There is no in-app seeding: notes.json lives in the app's internal filesDir, which a plain
`adb push` can't reach, so the `just seed-notes` recipe pushes this script's output to the debug
app's external files dir, and the app imports it on next launch. Timestamps are relative to now (so
day-grouping shows "I dag"/"I går"), and the rows deliberately span the combinations that stress the
row layout: every sex, the short/long ages, blank vs long activities, with/without locality and
comments, each red-list colour, an uncertain determination, a long species name, a near-duplicate
pair (same species and place, different age/sex) — the "which is the chicks, which the parents" case
the age/sex preview exists for — and the co-observer variations (#128): none, one, a few, and a
12-name crowd (which stresses the "+N" badge and 10+ Medobservatør export columns), reusing one
"party" across several rows to mirror the sticky-følget look.

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
KRAAKE = (
    "kråke",
    "Corvus corone",
    "",
)  # carrion+hooded crow lumped -> Corvus corone (#155)
SVARTTROST = ("svarttrost", "Turdus merula", "")
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

# A long locality name that ellipsizes hard, for the worst-case crowding rows.
LONG_SITE = "Ladehammeren fuglefredningsområde"

# A "field party" reused across several rows so the seed shows the sticky-følget look (#128).
PARTY = ["Kari Nordmann", "Ola Hansen"]
# A deliberately huge list to stress the "+N" badge and 10+ Medobservatør export columns.
CROWD = [
    "Kari Nordmann",
    "Ola Hansen",
    "Per Berg",
    "Nina Dahl",
    "Lars Lie",
    "Mona Rud",
    "Ivar Aas",
    "Siri Moen",
    "Tor Vik",
    "Gro Ek",
    "Bjørn Lund",
    "Åse Holt",
]

# Each row: (minutes-ago, species-tuple, count, age, sex, activity, locName, pub, priv,
#            uncertain, coObservers)
ROWS = [
    # Full house: red badge, every field set, locality present, + a two-person party.
    (5, GRAAMAAKE, 4, "Adult", "Hann", "Rastende", "Munkholmen", "", "", False, PARTY),
    # Near-duplicate of the above (same species + place) but the chicks: different age/sex/count.
    # Same party — the sticky-følget case (a run of obs sharing companions).
    (8, GRAAMAAKE, 2, "1K", "Hunn", "Rastende", "Munkholmen", "", "", False, PARTY),
    # Unknown count (-> "?"), long activity, NO locality, solo (no co-observers).
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
        [],
    ),
    # Pair symbol, public comment (blue i), black alien badge, one co-observer.
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
        ["Per Berg"],
    ),
    # Hunnfarget symbol, long locality (ellipsizes), private comment (grey i), solo.
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
        [],
    ),
    # Pulli age, no sex/activity, red NT badge, and the 12-name crowd (-> "+12" badge).
    (130, STORSKARV, 35, "Pulli", "", "", "Korsvika", "", "", False, CROWD),
    # Long species name + uncertain determination (name?) + one co-observer — width stress.
    (
        160,
        STEINSKVETT,
        1,
        "2K",
        "Hann",
        "Stasjonær",
        "Estenstadmarka",
        "",
        "",
        True,
        ["Kari Nordmann"],
    ),
    # Baseline: no age/sex/activity/co-observers at all (row as before the features).
    (200, KJOTTMEIS, 1, "", "", "", "Lade", "", "", False, []),
    # Long name + long activity + both comments + three co-observers — the worst-case crowding.
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
        PARTY + ["Per Berg"],
    ),
    # Big count, sex only, one co-observer.
    (
        300,
        KRAAKE,
        100,
        "",
        "Hann",
        "Overflygende",
        "Trondheim sentrum",
        "",
        "",
        False,
        ["Ola Hansen"],
    ),
    # Yesterday — exercises the "I går" day header; carries the party.
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
        PARTY,
    ),
    # --- A fuller "yesterday" batch: in the field, rows rarely fit this easily. ---
    # "Everything": big count, uncertain, red badge, age + sex, BOTH comments, co-observers.
    (
        DAY // MINUTE + 60,
        STORSPOVE,
        1000,
        "Adult",
        "Hann",
        "Rastende",
        "Munkholmen",
        "Stor ansamling på fjæra",
        "Talt i teleskop",
        True,
        PARTY,
    ),
    # A plain row that still carries BOTH a public and a private comment.
    (
        DAY // MINUTE + 95,
        KJOTTMEIS,
        3,
        "1K",
        "Hann",
        "Ved fôring",
        "Lade",
        "Ved fuglematerne",
        "Hann med fargering",
        False,
        [],
    ),
    # Worst case: everything + a really long species name (red-listed, so the badge still shows).
    (
        DAY // MINUTE + 150,
        AFRIKASVARTSTRUPE,
        12,
        "2K",
        "Hann",
        "Stasjonær",
        "Lade",
        "Uvanlig langt nord",
        "Fotografert",
        True,
        ["Kari Nordmann", "Per Berg"],
    ),
    # Worst case: everything + a really long locality name.
    (
        DAY // MINUTE + 210,
        STORSPOVE,
        40,
        "1K",
        "Hunn",
        "Næringssøkende",
        LONG_SITE,
        "Flokk på mudderflata",
        "Sjekk ringmerking",
        True,
        PARTY,
    ),
    # Worst case: BOTH names long (long species + long locality).
    (
        DAY // MINUTE + 280,
        AFRIKASVARTSTRUPE,
        8,
        "2K+",
        "Hunnfarget",
        "Sang/spill, ikke hekking",
        LONG_SITE,
        "Sang fra busk",
        "Samme som i fjor?",
        True,
        PARTY + ["Per Berg"],
    ),
    # More crowded rows mixing the other long red-listed names.
    (
        DAY // MINUTE + 360,
        GRESSHOPPESANGER,
        1,
        "2K",
        "Hann",
        "Sang/spill, ikke hekking",
        "Bymarka",
        "Snerpende sang",
        "",
        False,
        ["Ola Hansen"],
    ),
    (
        DAY // MINUTE + 440,
        DOBBELTBEKKASIN,
        2,
        "Adult",
        "",
        "Overflygende",
        "Øysand",
        "",
        "Leik i skumringa",
        False,
        PARTY,
    ),
    (
        DAY // MINUTE + 520,
        SVARTHALESPOVE,
        5,
        "Adult",
        "I par",
        "Næringssøkende",
        "Gaulosen",
        "",
        "",
        False,
        ["Kari Nordmann"],
    ),
    # A couple of ordinary yesterday rows so the slot isn't all worst-cases.
    (
        DAY // MINUTE + 600,
        STOKKAND,
        8,
        "Adult",
        "Hann",
        "Rastende",
        "Nidelva",
        "",
        "",
        False,
        [],
    ),
    (
        DAY // MINUTE + 680,
        KRAAKE,
        20,
        "",
        "",
        "Overflygende",
        "Trondheim sentrum",
        "",
        "",
        False,
        [],
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
        coobs,
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
                "coObservers": coobs,
                "kommune": "Trondheim",
            }
        )
    return notes


if __name__ == "__main__":
    # Single-line JSON: `adb shell`'s PTY would translate embedded newlines, so keep it newline-free.
    print(json.dumps(build(int(time.time() * 1000)), ensure_ascii=False))
