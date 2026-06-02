#!/usr/bin/env python3
"""Set each locality's public/private flag from Artsobservasjoner's *authoritative*
allmenn flag, replacing the observer/polygon heuristic.

The public observation-search page calls POST /ViewSighting/FindSitesByName (no auth,
no token - just an X-Requested-With header) to autocomplete localities. With
IncludePublicBirdSites=true it returns every PUBLIC ("allmenn") bird site matching a
term, each as {Id, Name, ParentName, Region, IsPublicSite}. The `Id` is the GBIF
`locationID` we already key on, so we query each locality's name and mark a locality
public iff Artsobservasjoner returns it as a public site.

Wrinkles handled so we never make the table *worse*:
  * the endpoint returns at most 15 matches, so for a generic name (Myra, Hola) our
    site may be public yet not in the top 15 -> only DEMOTE when a name was fully
    enumerated (<15 hits); otherwise keep the prior flag.
  * the server throttles bursts -> a keep-alive Session, retry/backoff, and pacing; a
    name that still fails leaves its localities' prior flags untouched.
Manual corrections in locality_overrides.csv always win.

    .venv/bin/python process/fetch_public_flags.py
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
CKPT = "/tmp/public_flags_ckpt.json"   # resume point, so an interruption never re-hammers the API
CAP = 15
DELAY = 1.5          # be gentle: ~1 request / 1.5-2s (+ jitter), well under interactive typing rates

_mp = pathlib.Path(__file__).parent / "mark_public.py"
_spec = importlib.util.spec_from_file_location("mp", _mp)
mp = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(mp)


def make_session() -> requests.Session:
    s = requests.Session()
    s.headers.update({
        "Content-Type": "application/json", "X-Requested-With": "XMLHttpRequest",
        "User-Agent": "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"})
    return s


def find_sites(s: requests.Session, term: str, tries: int = 4):
    """The matching public bird sites. An empty 200 body means *no public match*
    (a private/unknown locality), which is a valid answer -> []. None only on a
    genuine network failure after retries."""
    payload = {"InAreas": [], "ForProject": None, "IncludePublicBirdSites": True,
               "IncludeOthersPrivateSites": False, "Term": term}
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


def _load_ckpt():
    try:
        d = json.load(open(CKPT))
        return set(d["done"]), set(d["public_ids"]), set(d["enumerated"])
    except Exception:
        return set(), set(), set()


def _save_ckpt(done, public_ids, enumerated):
    tmp = CKPT + ".tmp"
    json.dump({"done": sorted(done), "public_ids": sorted(public_ids),
               "enumerated": sorted(enumerated)}, open(tmp, "w"))
    pathlib.Path(tmp).replace(CKPT)


def fetch_public_ids(s, names, log_every=50):
    """Query every name; return (public_ids set, fully-enumerated names set, n_failed).
    Checkpoints to CKPT so an interruption resumes instead of re-querying (gentle on the
    API). A periodic "Uttian" canary aborts on silent throttling, since an empty body would
    otherwise be misread as private."""
    done, public_ids, enumerated = _load_ckpt()
    failed = 0
    todo = [n for n in names if n not in done]
    if done:
        print(f"  resuming: {len(done)} already done, {len(todo)} to go", file=sys.stderr)
    for i, name in enumerate(todo, 1):
        res = find_sites(s, name)
        if res is None:
            failed += 1
        else:
            if len(res) < CAP:
                enumerated.add(name)
            public_ids.update(str(x["Id"]) for x in res if x.get("IsPublicSite"))
            done.add(name)
        if i % 100 == 0:
            canary = find_sites(s, "Uttian")
            if not canary or not any(x.get("IsPublicSite") for x in canary):
                _save_ckpt(done, public_ids, enumerated)
                raise SystemExit(f"\nCanary failed at {i}/{len(todo)} - throttled; checkpointed and "
                                 "aborting without writing. Rerun later to resume.")
            _save_ckpt(done, public_ids, enumerated)
        if i % log_every == 0:
            print(f"\r  {i}/{len(todo)} | {len(public_ids)} public ids | {failed} net-fail",
                  end="", file=sys.stderr, flush=True)
        time.sleep(DELAY + random.uniform(0.0, 0.6))    # jitter - less bot-like
    print(file=sys.stderr)
    return public_ids, enumerated, failed


def main() -> int:
    rows = list(csv.DictReader(open(CSV)))
    names = sorted({r["lokalitet"] for r in rows if r["lokalitet"]})
    s = make_session()
    public_ids, enumerated, failed = fetch_public_ids(s, names)

    overrides = mp.load_overrides()
    promoted = demoted = kept = 0
    for r in rows:
        prior = r["public"]
        if r["id"] in public_ids:
            flag = "1"
        elif r["lokalitet"] in enumerated:
            flag = "0"
        else:
            flag = prior                         # capped/failed -> don't worsen
        flag = overrides.get(r["id"], flag)
        if flag == prior:
            kept += 1
        elif flag == "1":
            promoted += 1
        else:
            demoted += 1
        r["public"] = flag
    with open(CSV, "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
        w.writeheader()
        w.writerows(rows)
    pathlib.Path(CKPT).unlink(missing_ok=True)   # clean run -> fresh next time
    npub = sum(1 for r in rows if r["public"] == "1")
    print(f"Authoritative allmenn flags applied. public={npub}/{len(rows)} "
          f"(+{promoted} promoted, -{demoted} demoted, {kept} unchanged). "
          f"{len(public_ids)} public ids, {failed}/{len(names)} name lookups failed.", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
