#!/usr/bin/env python3
"""Set each locality's public/private flag from Artsobservasjoner's *authoritative*
allmenn flag, replacing the observer/polygon heuristic.

The public observation-search page calls POST /ViewSighting/FindSitesByName (no auth,
no token - just an X-Requested-With header) to autocomplete localities. With
IncludePublicBirdSites=true it returns every PUBLIC ("allmenn") bird site matching a
term, each as {Id, Name, ParentName, Region, IsPublicSite}. The `Id` is the GBIF
`locationID` we already key on, so we query each locality's name and mark a locality
public iff Artsobservasjoner returns it as a public site.

Two real-world wrinkles are handled so we never make the table *worse*:
  * the endpoint returns at most 15 matches, so for a generic name (Myra, Hola) our
    site may be public yet not in the top 15 -> only DEMOTE a locality to private when
    its name was fully enumerated (<15 hits) and it still wasn't there; otherwise keep
    the prior flag.
  * transient throttling -> retry with backoff; a name that ultimately fails leaves its
    localities' prior flags untouched.
Manual corrections in locality_overrides.csv always win.

    .venv/bin/python process/fetch_public_flags.py
"""
import csv
import importlib.util
import json
import pathlib
import sys
import time
import urllib.request

URL = "https://www.artsobservasjoner.no/ViewSighting/FindSitesByName"
CSV = "app/src/main/assets/localities.csv"
CAP = 15
UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"

_mp = pathlib.Path(__file__).parent / "mark_public.py"
_spec = importlib.util.spec_from_file_location("mp", _mp)
mp = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(mp)


def find_sites(term: str, tries: int = 5):
    body = json.dumps({"InAreas": [], "ForProject": None, "IncludePublicBirdSites": True,
                       "IncludeOthersPrivateSites": False, "Term": term}).encode()
    req = urllib.request.Request(URL, data=body, headers={
        "Content-Type": "application/json", "X-Requested-With": "XMLHttpRequest", "User-Agent": UA})
    for attempt in range(tries):
        try:
            with urllib.request.urlopen(req, timeout=25) as r:
                return json.loads(r.read().decode("utf-8"))
        except Exception:
            if attempt == tries - 1:
                return None
            time.sleep(0.6 * (attempt + 1))
    return None


def main() -> int:
    rows = list(csv.DictReader(open(CSV)))
    names = sorted({r["lokalitet"] for r in rows if r["lokalitet"]})
    public_ids: set[str] = set()      # ids Artsobs returned as public (any query)
    enumerated: set[str] = set()      # names whose result set was complete (< CAP)
    failed = 0
    for i, name in enumerate(names, 1):
        res = find_sites(name)
        if res is None:
            failed += 1
        else:
            if len(res) < CAP:
                enumerated.add(name)
            for s in res:
                if s.get("IsPublicSite"):
                    public_ids.add(str(s["Id"]))
        if i % 25 == 0:
            print(f"\r  {i}/{len(names)} names | {len(public_ids)} public ids | {failed} failed",
                  end="", file=sys.stderr)
        time.sleep(0.4)
    print(file=sys.stderr)

    overrides = mp.load_overrides()
    promoted = demoted = kept = 0
    fields = list(rows[0].keys())
    for r in rows:
        prior = r["public"]
        if r["id"] in public_ids:
            flag = "1"                                   # Artsobs confirms public
        elif r["lokalitet"] in enumerated:
            flag = "0"                                   # fully enumerated, not public
        else:
            flag = prior                                 # capped/failed -> don't worsen
        flag = overrides.get(r["id"], flag)              # manual correction wins
        if flag == prior:
            kept += 1
        elif flag == "1":
            promoted += 1
        else:
            demoted += 1
        r["public"] = flag
    with open(CSV, "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=fields)
        w.writeheader()
        w.writerows(rows)
    npub = sum(1 for r in rows if r["public"] == "1")
    print(f"Authoritative allmenn flags applied. public={npub}/{len(rows)} "
          f"(+{promoted} promoted, -{demoted} demoted, {kept} unchanged). "
          f"{len(public_ids)} public ids seen, {failed} name lookups failed.", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
