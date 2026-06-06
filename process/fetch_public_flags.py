#!/usr/bin/env python3
"""Set each locality's public/private flag from Artsobservasjoner's *authoritative*
allmenn flag, replacing the observer/polygon heuristic.

The public observation-search page calls POST /ViewSighting/FindSitesByName (no auth,
no token - just an X-Requested-With header). With IncludePublicBirdSites=true it returns
every PUBLIC ("allmenn") bird site matching a term: {Id, Name, ParentName, Region,
IsPublicSite}. We query each locality's name and decide public by matching
**Name + ParentName + Region** - NOT Id: GBIF's locationID often differs from the site's
current Artsobservasjoner id (a locality re-created on the site keeps old ids in GBIF), so
id matching gives false negatives (e.g. Stabburshaugen). Name+parent+region is stable.

Handled so we never make the table worse:
  * empty 200 body = no public match = private (a valid answer);
  * <=15 results per term, so only DEMOTE when a name was fully enumerated (<15 hits);
    a capped name keeps the prior flag.
Raw API results are cached to RAW so re-processing (or a matching-logic change) costs no
further requests; a name already cached is never re-queried. A periodic "Uttian" canary
aborts on throttling. Manual corrections in locality_overrides.csv always win.

    .venv/bin/python process/fetch_public_flags.py     # gentle, resumable
"""

import csv
import importlib.util
import json
import pathlib
import random
import sys
import time

import requests

URL = "https://www.artsobservasjoner.no/ViewSighting/FindSitesByName"
CSV = "app/src/main/assets/localities.csv"
RAW = "/tmp/public_flags_raw.json"  # name -> API results; cache + resume point
CAP = 15
DELAY = 1.5  # be gentle: ~1 request / 1.5-2s (+ jitter)

_mp = pathlib.Path(__file__).parent / "mark_public.py"
_spec = importlib.util.spec_from_file_location("mp", _mp)
mp = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(mp)


def norm(x: str) -> str:
    return (x or "").lstrip(", ").strip().lower()


def make_session() -> requests.Session:
    s = requests.Session()
    s.headers.update(
        {
            "Content-Type": "application/json",
            "X-Requested-With": "XMLHttpRequest",
            "User-Agent": "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36",
        }
    )
    return s


def find_sites(s, term, tries=4):
    """Matching public bird sites; [] for an empty body (no match); None only on
    a genuine network failure after retries."""
    payload = {
        "InAreas": [],
        "ForProject": None,
        "IncludePublicBirdSites": True,
        "IncludeOthersPrivateSites": False,
        "Term": term,
    }
    for attempt in range(tries):
        try:
            r = s.post(URL, json=payload, timeout=30)
            if r.status_code == 200:
                txt = r.text.strip()
                return json.loads(txt) if txt else []
        except Exception:
            pass
        time.sleep(1.0 * (attempt + 1))
    return None


def harvest(s, names):
    """Query each name once (skipping cached), caching raw results to RAW. Returns the
    {name: results} cache. Aborts (keeping the cache) if the canary shows throttling."""
    cache = {}
    if pathlib.Path(RAW).exists():
        cache = json.load(open(RAW))
    todo = [n for n in names if n not in cache]
    if cache:
        print(f"  resuming: {len(cache)} cached, {len(todo)} to fetch", file=sys.stderr)
    failed = 0
    for i, name in enumerate(todo, 1):
        res = find_sites(s, name)
        if res is None:
            failed += 1
        else:
            cache[name] = res
        if i % 100 == 0:
            canary = find_sites(s, "Uttian")
            json.dump(cache, open(RAW, "w"), ensure_ascii=False)
            if not canary or not any(x.get("IsPublicSite") for x in canary):
                raise SystemExit(
                    f"\nCanary failed at {i}/{len(todo)} - throttled; cached and aborting. "
                    "Rerun to resume."
                )
        if i % 50 == 0:
            json.dump(cache, open(RAW, "w"), ensure_ascii=False)
            print(
                f"\r  fetched {i}/{len(todo)} | {failed} net-fail",
                end="",
                file=sys.stderr,
                flush=True,
            )
        time.sleep(DELAY + random.uniform(0.0, 0.6))
    print(file=sys.stderr)
    json.dump(cache, open(RAW, "w"), ensure_ascii=False)
    return cache, failed


def classify(rows, cache):
    """Authoritative flag per locality from cached results, matched by name+parent+region."""
    public = set()  # (name, parent, region) confirmed public
    enumerated = set()  # names whose result set was complete (< CAP) -> safe to demote
    for name, res in cache.items():
        if len(res) < CAP:
            enumerated.add(name.strip().lower())
        for x in res:
            if x.get("IsPublicSite"):
                public.add(
                    (
                        x["Name"].strip().lower(),
                        norm(x.get("ParentName")),
                        x.get("Region"),
                    )
                )
    overrides = mp.load_overrides()
    promoted = demoted = kept = 0
    for r in rows:
        prior = r["public"]
        region = r["fullname"].split(",")[-1].strip()
        key = (r["lokalitet"].strip().lower(), norm(r["hovedlokalitet"]), region)
        if key in public:
            flag = "1"
        elif r["lokalitet"].strip().lower() in enumerated:
            flag = "0"
        else:
            flag = prior  # capped/uncached -> don't worsen
        flag = overrides.get(r["id"], flag)
        if flag == prior:
            kept += 1
        elif flag == "1":
            promoted += 1
        else:
            demoted += 1
        r["public"] = flag
    return promoted, demoted, kept


def main() -> int:
    rows = list(csv.DictReader(open(CSV)))
    names = sorted({r["lokalitet"] for r in rows if r["lokalitet"]})
    cache, failed = harvest(make_session(), names)
    promoted, demoted, kept = classify(rows, cache)
    with open(CSV, "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
        w.writeheader()
        w.writerows(rows)
    npub = sum(1 for r in rows if r["public"] == "1")
    print(
        f"Authoritative allmenn flags applied (Name+Parent+Region). public={npub}/{len(rows)} "
        f"(+{promoted} promoted, -{demoted} demoted, {kept} unchanged). "
        f"{failed} name lookups failed. Raw cache: {RAW}",
        file=sys.stderr,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
