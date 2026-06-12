#!/usr/bin/env python3
"""Find which of *my* private Artsobservasjoner localities have zero observations,
so the unused ones can be pruned (deleted on the website).

No login needed. The public observation-search page can be filtered by site id, and the
result grid's total count is rendered straight into the page (the `BindSightingsGrid?...
TotalPageCount=N` URL, where N is the total matching sightings, despite the misleading
name). So per site we just POST the search filtered to that one site and read N; N == 0
means the locality has never been used.

Input: the localities to check, either
  - my-localities.csv (the file the app's "Synk mine lokaliteter" writes; col 0 = id,
    col 1 = lokalitet), passed as the first arg, or
  - with no arg, the private rows (isPrivate) of the mobile-API sites harvest in
    FELTBOK_DATA_DIR/artsobs-sites-mobil.json (same set the app would sync).

    .venv/bin/python scripts/dev/find_unused_localities.py [my-localities.csv]
"""

import csv
import os
import re
import sys
import time

import requests

BASE = "https://www.artsobservasjoner.no"
SEARCH = f"{BASE}/ViewSighting/SearchSighting"
TABLE = f"{BASE}/ViewSighting/ViewSightingAsTable"
UA = "Mozilla/5.0 (X11; Linux x86_64; rv:151.0) Gecko/20100101 Firefox/151.0"
SITES_FIELD = "SearchViewModel.StoredSearchCriterias.SearchCriterias.Sites"
DELAY = 0.4
TOTAL_RE = re.compile(r"BindSightingsGrid\?TotalPageCount=(\d+)")
TOKEN_RE = re.compile(r'__RequestVerificationToken[^>]*value="([^"]+)"')


def load_targets(arg):
    """Return [(id, name)] of localities to check."""
    if arg:
        with open(arg, encoding="utf-8") as f:
            rows = list(csv.reader(f))
        return [(r[0], r[1]) for r in rows[1:] if r and r[0].strip().isdigit()]
    data_dir = os.environ.get(
        "FELTBOK_DATA_DIR", "/home/morten/Documents/projects/app-feltbok"
    )
    import json

    rows = json.load(open(f"{data_dir}/artsobs-sites-mobil.json"))
    return [(str(r["id"]), r["name"]) for r in rows if r.get("isPrivate")]


def new_session():
    """Fresh session + antiforgery token (token is session-scoped, reused across sites)."""
    s = requests.Session()
    s.headers["User-Agent"] = UA
    html = s.get(SEARCH, timeout=30).text
    m = TOKEN_RE.search(html)
    if not m:
        raise SystemExit("could not read antiforgery token from search page")
    return s, m.group(1)


def count_for_site(s, token, site_id):
    """Total observations registered at one site id (None on failure -> caller retries)."""
    r = s.post(
        TABLE,
        data={"__RequestVerificationToken": token, SITES_FIELD: site_id},
        timeout=60,
    )
    m = TOTAL_RE.search(r.text)
    return int(m.group(1)) if m else None


def main():
    targets = load_targets(sys.argv[1] if len(sys.argv) > 1 else None)
    print(f"checking {len(targets)} private localities...", file=sys.stderr)
    s, token = new_session()
    unused, errors = [], []
    for i, (sid, name) in enumerate(targets, 1):
        n = count_for_site(s, token, sid)
        if n is None:  # session/token likely stale -> refresh once and retry
            s, token = new_session()
            n = count_for_site(s, token, sid)
        if n is None:
            errors.append((sid, name))
        elif n == 0:
            unused.append((sid, name))
        print(
            f"\r  {i}/{len(targets)} | {len(unused)} unused so far",
            end="",
            file=sys.stderr,
        )
        time.sleep(DELAY)
    print(file=sys.stderr)

    unused.sort(key=lambda t: t[1].lower())
    print(f"\n{len(unused)} unused (0 observations) of {len(targets)}:\n")
    for sid, name in unused:
        print(f"  {sid}\t{name}")
    if errors:
        print(f"\n{len(errors)} could not be checked:", file=sys.stderr)
        for sid, name in errors:
            print(f"  {sid}\t{name}", file=sys.stderr)


if __name__ == "__main__":
    main()
