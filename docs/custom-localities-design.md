# Custom localities — design notes

How to let each user use, see, and create their **own** custom localities, with polygons,
while keeping the app offline-in-field and zero-login. Notes from an evening of API
probing — treat the Artskart specifics as "promising, needs one more reverse-engineering
pass", not settled.

## ✅ SOLVED (2026-06-02): the authoritative allmenn flag — `FindSitesByName`

The public observation-search page (`/ViewSighting/SearchSighting`, no login) drives its
locality autocomplete with:

    POST https://www.artsobservasjoner.no/ViewSighting/FindSitesByName
    Header: X-Requested-With: XMLHttpRequest          (required; no auth, no antiforgery token)
    Body (JSON): {"Term":"Uttian","IncludePublicBirdSites":true,
                  "IncludeOthersPrivateSites":false,"InAreas":[],"ForProject":null}
    -> [{"Id":352401,"Name":"Uttian","ParentName":"","Region":"Tø","IsPublicSite":true,...}]

- **`Id` == our `locationID`**, and **`IsPublicSite`** is the real allmenn flag.
- An **empty 200 body = no public match = private** (a valid answer, not an error).
- At most **15 results** per term, so for a generic name our site may be public yet not in
  the top 15 — only demote when a name is fully enumerated (<15 hits).
- `process/fetch_public_flags.py` (`just public-flags`) queries every locality name and sets
  the real flag, retiring the observer/polygon heuristic (kept only as a capped/failed
  fallback). A periodic "Uttian" canary aborts the run if the server starts throttling.

This **supersedes the Artskart `List` dead-end below** for the public/private question.

## The import-matching constraint (recap)

⚠️ The table below is the OLD, disproven model. The verified rule is in
`docs/artsobs-import.md`: paste matches the **exact registered `Lokalitetsnavn`** verbatim
(a qualified "Lok, Hovedlok, Kommune, Fylke" *hard-fails*; coordinates mint a private
duplicate). Kept here only for history.

| Kind | What to export | Why |
|---|---|---|
| **Public** (allmenn) | ~~qualified~~ exact registered name, no coords | links to the public registry |
| **My own custom** | **bare** name, no coords | bare name matches *your own* localities |
| **Brand-new spot** | name **+ coordinates** | mints a new custom locality on Artsobs |

## Data source for "my localities": Artskart PublicApi (public, no auth)

Base: `https://artskart.artsdatabanken.no/PublicApi/api/Observations/List`

Each observation record includes (verified live):
- `Collector` — the observer name (e.g. "Per Arvid Åsen")
- `Locality` — the locality name
- `FootprintWKT` — the locality **geometry**, `POINT` or **`POLYGON`**, in **UTM33 / EPSG:25833**
- `Precision` — radius in metres (for point localities)
- `Longitude` / `Latitude` — WGS84 centroid; `County`/`Municipality`; `TaxonId`; `DatasetName`

**Polygons are available** (your earlier project pulled polygon localities for a species).

⚠️ **Caveat found tonight:** this `List` endpoint returned a capped ~20-record *sample* and
ignored `MaxResults`, `Offset`/`Page`/`Skip`, and `Collector`. So it is NOT the query path
the Artskart frontend uses for full, filtered, paged results. Finding that real call (likely
a different endpoint — a map/aggregate endpoint plus a detail fetch, or a POST with a filter
body — `gmWktPolygon` (Google-Mercator) area filtering *did* work) is the one open task before
this is a sure thing.

## Proposed flow

### A. Pull "my localities" (occasional, online, in-app — not in the field)
1. **Settings:** user types their Artsobservasjoner display name once (= `Collector`). This is
   a filter value, **not a login** — the app stays zero-login and shareable.
2. App queries Artskart over the user's birding region(s) (or by collector nationwide if the
   real endpoint allows), pages through, and **client-side keeps `Collector == my name`**
   (the field is in every record, so client-side filtering works even though the server
   filter is ignored).
3. Extract distinct localities: name + geometry (reproject UTM33 → WGS84) + radius.
4. Cache to a local file (e.g. `mylocalities.json`) for offline field use.

### B. Classify for export
- Picked locality is in the bundled public registry → **qualified name**.
- Picked locality is one of *mine* but not public → **bare name**.
- Brand-new (created in-app, not yet on Artsobs) → **name + coords** (mints). After the next
  pull it becomes a known "mine".

### B2. Copy *someone else's* custom localities (user-requested 2026-06-02)
The bundled list already surfaces other people's nicely-drawn custom localities (e.g.
many around Frøya appear to belong to **Emil Krokan**). They're great spots, but you
can't report at one directly - on Artsobservasjoner you must **copy** it into your own
localities first, manually. So: let the user name a person (Collector), pull that
person's custom localities (same Artskart/GBIF client-side-by-Collector pull as A), and
offer to **adopt/copy** chosen ones into the user's own set. "Copy" = mint it as the
user's own on export (name + coords), after which bare-name matches it like any of your
own. Needs the geometry (we have it now from `footprintWKT`) to show what you're copying.

### C. Create a new custom locality in-app
- "Ny lokalitet her" → drop a pin (or use GPS), give it a name. Store as a pending custom
  locally; show it on the map in a distinct colour (**yellow**, like the website).
- On export it mints on Artsobs; editable later on the website; re-synced on the next pull.

### D. Map display
- Public localities: **green** (bundled).
- My customs: **yellow**.
- Draw real **polygons** where we have them (Artskart `FootprintWKT`); fall back to the
  point + radius circle otherwise.

## Bonus: polygons for PUBLIC localities too

`FootprintWKT` also carries polygons for public localities. So a build-time Artskart harvest
by area could enrich the bundled `localities.csv` with **real footprints** (issue #3) without
the gated Artsobs API. Same reprojection + paging caveats. This may make the "map is so much
better with polygons" upgrade possible from public data alone.

## Open questions / risks
- **Find the real Artskart query endpoint** for full, paged, filterable results (the `List`
  endpoint is a limited sample). This is the gating unknown.
- **Collector identity:** display-name exact match; homonyms; display name vs username.
- **No allmenn flag** from Artskart (it is GBIF-sourced) → the bundled public set still needs
  the observer/route heuristic.
- **Reprojection** UTM33 → WGS84 (pyproj at build time, or a small inline transform in-app).
- **Volume / rate limits** for busy areas.
- Privacy: only the user's own name; fine.

## Fallback if the Artskart per-person pull proves hard
The user exports their own localities/observations from Artsobservasjoner (logged in) once as
a file; a build tool or the app imports it. Reliable, less seamless.

## Recommendation
1. **Reverse-engineer the working Artskart query** (per-person + polygons, paged) at build
   time first — prove it end-to-end with a script.
2. Then move it in-app as an online **"Synk mine lokaliteter"** action.
3. Implement **create-new** + the **3-way export classification** independently — they don't
   need Artskart and unblock custom localities immediately (mint on first use, bare-name
   thereafter).
