#!/usr/bin/env python3
"""Emit a notes.json of varied observations for the debug build. It serves two purposes:

1. **Eyeballing the notes list / editor UI** — rows deliberately span the combinations that stress
   the row layout: every sex, short/long ages, blank vs long activities, with/without locality and
   comments, each red-list colour, an uncertain determination, long species/locality names, a
   near-duplicate pair (same species+place, different age/sex — the "which is chicks, which parents"
   case the age/sex preview exists for), and the co-observer variations (#128): none, one, a few,
   a 12-name crowd (stresses the "+N" badge and 10+ Medobservatør columns), and a 100-user crowd
   (proves the import takes far more Medobservatør columns than the template's 10).
2. **A live paste-import test** — seed clean, then export FROM THE APP and paste into
   Artsobservasjoner "Importer observasjoner". So the rows also cover every path `exportTsv` takes:
   a registry locality exported by site id (including Austrått, whose name also exists in another
   kommune, so only the id links it correctly), a name-only one (a note saved before notes kept the
   id), a brand-new spot (coordinates + radius, mints a private locality —
   each import mints a fresh dupe), a blank Antall (unknown count), the uncertain flag, a hide-until date
   (#181), a same-day and a multi-day time range, and the two no-time cases (#155: date kept, klokkeslett blank — single
   day and multi-day). To keep import errors meaningful, real inputs the site validates are used:
   localities are **real, globally-unique public localities** (unique across the whole country, so
   they resolve to the public locality even without kommune-scoping the import — no "matchet flere
   allmenne lokaliteter"), co-observers are **real registered users** (made-up or ambiguous names
   fail to resolve), and an "I par" row uses an even count (the site requires partall).
   `INVALID_ROWS` add one note per export error line, all on one day. Export once to see them,
   delete that day (and the future-dated note), then export again: the rest should go in with
   **no errors**, so any error is a real signal worth chasing.

Each row's first argument is a short `why` — what that row is meant to exercise — so the intent
lives next to the data (it's dev documentation, not emitted to the JSON).

There is no in-app seeding: notes.json lives in the app's internal filesDir, which a plain
`adb push` can't reach, so `just seed` pushes this script's output to the debug app's external
files dir, and the app imports it on next launch. Timestamps are relative to now (so day-grouping
shows "I dag"/"I går").

Activities/ages/sexes must be exact values from the app's Norwegian Country vocabularies, or the
import rejects the row. Run via `just seed`, or `python scripts/dev/seed_notes.py` to inspect
the JSON (add `--why` to instead print each row's purpose).

The 100-user crowd's brukeraliases are looked up on Artsobservasjoner on the first run and cached
in the gitignored `coobs-cache.json` next to this script, so the repo doesn't carry a scraped user
directory. Delete the cache to look them up again.
"""

import csv
import json
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path

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
# Species typed by hand (#144): not in the app's bird checklist, so latin is blank (no registry
# key, no status badge). The export looks each name up with TaxonName/Search (any species group) and
# takes the one taxon with exactly that name, so these must be real names on the site.
RAADYR = ("rådyr", "", "")
# Insects, by Norwegian and by scientific name; subspecies, with their own Norwegian name and in the
# site's "X, underarten Y" form (capitalised: case is ignored); and a typo no taxon has.
SITRONSOMMERFUGL = ("sitronsommerfugl", "", "")
MARIHONE_LATIN = ("Coccinella septempunctata", "", "")
SVARTRYGGERLE = ("svartryggerle", "", "")
AERFUGL_BOREALIS = ("Ærfugl, underarten borealis", "", "")
TYPO = ("sitronsommerfulg", "", "")
# Taxa that aren't species (#162, #190), from other_taxa.csv. They upload by taxon id like a species.
UB_TERNE = ("ub. makrell-/rødnebbterne", "Sterna hirundo/paradisaea", "")
UB_SVANE = ("ub. svane", "Cygnus", "")
AERFUGL_BOREALIS_LISTED = (
    "ærfugl, underarten borealis",
    "Somateria mollissima borealis",
    "",
)
BRUNNAKKE_X_STOKKAND = (
    "brunnakke x stokkand",
    "Mareca penelope x Anas platyrhynchos",
    "",
)
KRAAKEFAMILIEN = ("kråkefamilien", "Corvidae", "")

UNKNOWN_COUNT = -1
MINUTE = 60_000
HOUR = 60 * MINUTE
DAY = 24 * HOUR

# Fallback coordinates for a locality not in the bundled list (the private one). A registry note
# carries its locality's own position, as a real note does: the map draws it there, and the app
# finds an old note's site id by name and position (backfillLocIds).
LAT, LON = 63.43, 10.40
LOCALITIES = Path(__file__).parents[2] / "app/src/main/assets/localities.csv"
# A brand-new spot's own coordinates (a real point near Ladehammeren, Trondheim), for the one
# newLoc row that exercises the coordinate + radius export (mints a private locality on import).
NEW_LAT, NEW_LON = 63.4485, 10.4380

# A long, real, globally-unique Trondheim public locality — long enough to ellipsize in the row
# (UI stress) yet a name the import still validates. Real spots avoid the "matchet flere" errors that
# common names (Munkholmen, Bymarka, …) hit; #-marked ones below are all globally-unique + public.
LONG_SITE = "sjøområdet mellom Brattøra og Munkholmen"

# Artsobservasjoner site ids of the public localities used below, as the app stores them on a note
# picked from the list (exported in place of the name). Austrått also exists in Sandnes, so a bare
# name would be ambiguous; the id links the Ørland one.
SITE_IDS = {
    "Ladehammeren": "354139",
    "Ilsvika": "352771",
    "Ilabekken": "352936",
    LONG_SITE: "882001",
    "Leangenbukta": "349975",
    "Estenstaddammen": "645239",
    "Baklidammen": "725711",
    "Haukvatnet": "477750",
    "Kyvatnet": "477748",
    "Være": "368841",
    "Jonsvatnet": "354430",
    "Theisendammen": "349763",
    "Lianvatnet": "351372",
    "Devlebukta": "350985",
    "Austrått": "354419",
}

# Real registered Artsobservasjoner users, so the paste-import links co-observers instead of
# failing on unknown/ambiguous names. PARTY is reused across rows for the sticky-følget look (#128);
# CROWD is a 12-name group that stresses the "+N" badge and the 10+ Medobservatør export columns.
# (Names that resolve to several users — e.g. "Monica Esbensen" — fail as ambiguous, so aren't used.)
# A user picked from the co-observer search is stored by brukeralias instead, which links even when
# the name is shared: Ibe55 is the Inge Berg whose import first failed (the other one is Pingu).
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
    private_loc=False,
    hide_days=None,
    legacy=False,
):
    """One row. `why` = what it exercises (dev doc, not exported). `mins` = minutes before now
    (drives the day header). `dur_min`, if set, adds an end time that many minutes after the start
    (a range; large values span days — keep the end in the past, the site rejects future times).
    `no_time` marks the time-of-day unspecified. `new_loc` exports coordinates + `radius`.
    `private_loc` marks `loc` as one of the user's own private localities (links by bare name, so no
    qualified fullname), matching how the app stores a note picked on a private site. `hide_days`
    sets "Skjul funn til dato" that many days from now (#181). `legacy` leaves out the site id, like a
    note saved before notes kept it, so it exports the name."""
    return {
        "why": why,
        "mins": mins,
        "species": species,
        "count": count,
        "age": age,
        "sex": sex,
        "act": act,
        "loc": loc,
        "pub": pub,
        "priv": priv,
        "unc": unc,
        "coobs": coobs or [],
        "dur_min": dur_min,
        "no_time": no_time,
        "new_loc": new_loc,
        "radius": radius,
        "private_loc": private_loc,
        "hide_days": hide_days,
        "legacy": legacy,
    }


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
        "hidden until well past the breeding season (Skjul funn til dato, #181)",
        55,
        STORSPOVE,
        2,
        "Adult",
        "I par",
        "Permanent revir",
        "Ilsvika",
        hide_days=400,
    ),
    note(
        "hide-until date that has passed (an old note hidden for a while)",
        52,
        STORSPOVE,
        1,
        "",
        "",
        "",
        "Ilsvika",
        hide_days=-10,
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
        coobs=["Eivind Bering", "Ibe55"],
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
    note(
        "name shared with another kommune (Austrått: Ørland and Sandnes): only the site id links it",
        DAY // MINUTE + 700,
        KRAAKE,
        3,
        "",
        "",
        "Næringssøkende",
        "Austrått",
    ),
    note(
        "saved before notes kept the site id: exports the bare name instead",
        DAY // MINUTE + 720,
        KJOTTMEIS,
        2,
        "",
        "",
        "",
        "Theisendammen",
        legacy=True,
    ),
    note(
        "ub. species-complex entry (#162): normal bird-group fields still apply",
        DAY // MINUTE + 740,
        UB_TERNE,
        2,
        "Adult",
        "",
        "Rastende",
        "Ilsvika",
    ),
    note(
        "ub. genus-level entry (#162)",
        DAY // MINUTE + 800,
        UB_SVANE,
        1,
        "Adult",
        "",
        "Trekkende",
        "Leangenbukta",
    ),
    note(
        "subspecies (#190)",
        DAY // MINUTE + 820,
        AERFUGL_BOREALIS_LISTED,
        3,
        "",
        "",
        "",
        "Ilsvika",
    ),
    note(
        "hybrid (#190)",
        DAY // MINUTE + 830,
        BRUNNAKKE_X_STOKKAND,
        1,
        "",
        "Hann",
        "",
        "Leangenbukta",
    ),
    note(
        "family (#190)", DAY // MINUTE + 840, KRAAKEFAMILIEN, 4, "", "", "", "Ilsvika"
    ),
    note(
        "typed insect, Norwegian name (#144): found on the site by name, group fields blank",
        2 * DAY // MINUTE + 600,
        SITRONSOMMERFUGL,
        1,
        "",
        "",
        "",
        "Ladehammeren",
    ),
    note(
        "typed insect, scientific name (#144), with bird options insects also have (passes)",
        2 * DAY // MINUTE + 610,
        MARIHONE_LATIN,
        3,
        "",
        "Hann",
        "Næringssøkende",
        "Ladehammeren",
    ),
    note(
        "typed subspecies with its own Norwegian name (#144): bird fields still apply",
        2 * DAY // MINUTE + 630,
        SVARTRYGGERLE,
        1,
        "Adult",
        "Hann",
        "Næringssøkende",
        "Ilsvika",
    ),
    note(
        "typed subspecies in the site's 'X, underarten Y' form, capitalised (case is ignored)",
        2 * DAY // MINUTE + 640,
        AERFUGL_BOREALIS,
        4,
        "",
        "",
        "",
        "Ilsvika",
    ),
]

# Notes the export must stop on, one per error line, all on one day (three days ago) so they're easy
# to mark and delete. Seed, export once to see every line, delete that day, then export the rest:
# the remaining rows should all go in.
BAD_DAY = 3 * DAY // MINUTE + 600
LONG_COMMENT = "x" * 255  # the site's limit is 254
INVALID_ROWS = [
    note(
        "private locality without a site id: 'Lokaliteten finnes ikke'",
        BAD_DAY,
        RAADYR,
        2,
        "",
        "",
        "",
        "TKB tredje avdeling",
        private_loc=True,
    ),
    note(
        "typed name the site doesn't have: 'Arten finnes ikke på Artsobservasjoner'",
        BAD_DAY + 1,
        TYPO,
        1,
        "",
        "",
        "",
        "Ilsvika",
    ),
    note(
        "typed insect with a bird activity insects can't have: '«Rastende» kan ikke brukes på virvelløse dyr'",
        BAD_DAY + 2,
        SITRONSOMMERFUGL,
        2,
        "",
        "",
        "Rastende",
        "Ladehammeren",
    ),
    note(
        "age the app no longer has (an old note): 'Ugyldig alder «Ung»'",
        BAD_DAY + 3,
        KRAAKE,
        1,
        "Ung",
        "",
        "",
        "Ilsvika",
    ),
    note(
        "sex the app no longer has: 'Ugyldig kjønn «Hann?»'",
        BAD_DAY + 4,
        KRAAKE,
        1,
        "",
        "Hann?",
        "",
        "Ilsvika",
    ),
    note(
        "activity the app no longer has: 'Ugyldig aktivitet «Rasta»'",
        BAD_DAY + 5,
        KRAAKE,
        1,
        "",
        "",
        "Rasta",
        "Ilsvika",
    ),
    note(
        "odd count for 'I par': '«I par» krever partall'",
        BAD_DAY + 6,
        KRAAKE,
        3,
        "",
        "I par",
        "",
        "Ilsvika",
    ),
    note(
        "end before start: 'Sluttid er før starttid'",
        BAD_DAY + 7,
        KRAAKE,
        1,
        "",
        "",
        "",
        "Ilsvika",
        dur_min=-30,
    ),
    note(
        "private comment over the limit: 'Privat kommentar er for lang (maks 254 tegn)'",
        BAD_DAY + 8,
        KRAAKE,
        1,
        "",
        "",
        "",
        "Ilsvika",
        priv=LONG_COMMENT,
    ),
    note(
        "public comment over the limit: 'Åpen kommentar er for lang (maks 254 tegn)'",
        BAD_DAY + 9,
        KRAAKE,
        1,
        "",
        "",
        "",
        "Ilsvika",
        pub=LONG_COMMENT,
    ),
    note(
        "public comment that only goes over the limit with a typed-in co-observer added to it",
        BAD_DAY + 10,
        KRAAKE,
        1,
        "",
        "",
        "",
        "Ilsvika",
        pub="x" * 245,
        coobs=["Ola Testesen"],
    ),
    note(
        "date in the future (shows under its own day, at the top): 'Datoen er i framtida'",
        -2 * DAY // MINUTE,
        KRAAKE,
        1,
        "",
        "",
        "",
        "Ilsvika",
    ),
]

# Common surnames to search for the 100-user crowd. Users/Search returns at most 15 hits per query.
SURNAMES = [
    "Hansen",
    "Johansen",
    "Olsen",
    "Larsen",
    "Andersen",
    "Pedersen",
    "Nilsen",
    "Kristiansen",
    "Jensen",
    "Karlsen",
    "Berg",
    "Haugen",
    "Hagen",
    "Bakken",
    "Lie",
]


ALIAS_CACHE = Path(__file__).with_name("coobs-cache.json")


def cached_users(n):
    """`n` real users as the app saves one picked from the co-observer search ({name, alias, city,
    id}). Cached; an older cache of bare aliases is fetched again."""
    if ALIAS_CACHE.exists():
        users = json.loads(ALIAS_CACHE.read_text())
        if len(users) >= n and all(isinstance(u, dict) for u in users):
            return users[:n]
    users = fetch_users(n)
    ALIAS_CACHE.write_text(json.dumps(users, ensure_ascii=False))
    return users


def fetch_users(n):
    """`n` distinct real users with a brukeralias, which is what a co-observer is stored by."""
    users = []
    for name in SURNAMES:
        url = (
            "https://mobil.artsobservasjoner.no/core/Users/Search?"
            + urllib.parse.urlencode({"searchRequest": name})
        )
        req = urllib.request.Request(url, headers={"X-CSRF": "1"})
        with urllib.request.urlopen(req, timeout=10) as r:
            for u in json.load(r):
                alias = (u.get("userAlias") or "").strip()
                if alias and alias not in {x["alias"] for x in users}:
                    users.append(
                        {
                            "name": (u.get("name") or "").strip(),
                            "alias": alias,
                            "city": (u.get("city") or "").strip(),
                            "id": u["id"],
                        }
                    )
        if len(users) >= n:
            return users[:n]
    sys.exit(f"Only found {len(users)} of {n} co-observer users")


def big_crowd_row():
    return note(
        "100 co-observers by brukeralias (-> '+100' badge, 100 Medobs cols, far past the template's 10)",
        200,
        STORSKARV,
        4,
        "Adult",
        "",
        "Rastende",
        "Leangenbukta",
        coobs=[u["alias"] for u in cached_users(100)],
    )


def site_positions():
    """{site id: (lat, lon)} from the bundled locality list."""
    with open(LOCALITIES, newline="", encoding="utf-8") as f:
        return {r["id"]: (float(r["lat"]), float(r["lon"])) for r in csv.DictReader(f)}


def build(now_ms, rows=ROWS):
    pos = site_positions()
    notes = []
    for i, r in enumerate(rows):
        sp, latin, _status = r["species"]
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
            # A registry locality carries a qualified fullname; a brand-new spot and a private
            # locality (links by bare name) have none.
            "locFull": ""
            if new_loc or r["private_loc"] or not r["loc"]
            else f"{r['loc']}, Trondheim, Trøndelag",
            "lat": NEW_LAT
            if new_loc
            else pos.get(SITE_IDS.get(r["loc"]), (LAT, LON))[0],
            "lon": NEW_LON
            if new_loc
            else pos.get(SITE_IDS.get(r["loc"]), (LAT, LON))[1],
            "newLoc": new_loc,
            "locRadius": r["radius"],
            "uncertain": r["unc"],
            "coObservers": r["coobs"],
            "kommune": "Trondheim",
        }
        site_id = "" if new_loc or r["legacy"] else SITE_IDS.get(r["loc"], "")
        if site_id:
            n["locId"] = site_id
        # Optional fields mirror noteToJson: present only when meaningful (endTime omitted when null,
        # timeUnknown omitted when false) so the seed round-trips exactly like real saved notes.
        if r["dur_min"]:
            n["endTime"] = t + r["dur_min"] * MINUTE
        if r["no_time"]:
            n["timeUnknown"] = True
        if r["hide_days"]:
            n["hideUntil"] = now_ms + r["hide_days"] * DAY
        notes.append(n)
    return notes


if __name__ == "__main__":
    if (
        "--why" in sys.argv
    ):  # print the per-row purposes, for a quick legend of what's seeded
        for i, r in enumerate(ROWS + INVALID_ROWS, 1):
            print(f"{i:2}. {r['species'][0]} — {r['why']}")
    else:
        # Single-line JSON: `adb shell`'s PTY would translate embedded newlines, so keep it newline-free.
        rows = ROWS + INVALID_ROWS + [big_crowd_row()]
        print(json.dumps(build(int(time.time() * 1000), rows), ensure_ascii=False))
        # --users FILE: the 100-user row's co-observers, as the app saves users picked from the
        # search (coobs_users.json), so they upload as linked users rather than as free text.
        if "--users" in sys.argv:
            out = Path(sys.argv[sys.argv.index("--users") + 1])
            out.write_text(json.dumps(cached_users(100), ensure_ascii=False))
