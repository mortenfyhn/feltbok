# Custom localities — design notes

How to let each user use, see, and create their **own** custom localities, with polygons,
while keeping the app offline-in-field and zero-login. Notes from an evening of API
probing — treat the Artskart specifics as "promising, needs one more reverse-engineering
pass", not settled.

## Behaviour (as built) — the custom-locality lifecycle

A **brand-new spot** is a `Locality` with `newLoc = true`, empty `id`, `public = false`,
`mine = false`, created via "Ny lokalitet her" (map-centre pin + chosen radius + optional name).

**Storage / lifetime.** A new spot is never persisted on its own — it is *not* written to
`my-localities.csv` (that file only holds synced `mine` localities). It lives in the in-memory
picker for the session, and is re-derived at startup solely from the observations that reference
it (each `Note` copies the spot's name, coords, `newLoc`, and radius). So:
- created but unused → selectable for the rest of the session, gone after restart;
- used by ≥1 observation → re-derived on every launch for as long as any such observation exists;
- last referencing observation deleted → still selectable for the rest of the session, gone on
  the next restart.

This is deliberate: no separate persistence and no orphan cleanup to maintain — the observations
*are* the source of truth for which new spots still matter.

**Sync ("Synk mine lokaliteter").** Pulling from Artsobs replaces only the `mine` set
(`removeAll { it.mine }`, add the freshly synced set, persist to `my-localities.csv`). Public
localities and in-progress new spots are left untouched, and **observations are never modified**.
Online is the source of truth for `mine`.

**Export.** A new-spot observation exports with coordinates + radius (Nøyaktighet), which mints
the locality on Artsobs; registry/`mine` localities export by name only. Clearing observations
after import removes the notes (and with them the only anchor for any new spots). A minted spot is
**not** auto-converted to a `mine` locality — the next manual sync pulls it back with a real id.
Accepted consequence: between minting and the next sync, if the observation is kept and
re-exported, a temporary duplicate can appear. This is tolerated, not specially handled.

## ✅ SOLVED (2026-06-04): the new mobile API — `mobil.artsobservasjoner.no`

**This is the way to fetch a user's own localities, and likely the future for harvest too.**
The new mobile site is an Angular SPA backed by a **Duende BFF**:

- **Auth:** the user logs in on the site (OIDC, fully handled in-page). The BFF sets HttpOnly
  cookies `__Host-bff`, `__Host-bffC1`, `__Host-bffC2`. API calls go **same-origin** to a
  `/core/...` prefix (the SPA's `coreApiUrl` is literally `"core"`), with the header
  **`X-CSRF: 1`** (Duende's anti-forgery); the BFF proxies to the core API with the access token.
  Because the cookies are HttpOnly, you can't read them from `CookieManager.getCookie` — so the
  in-app flow must **fetch from inside the WebView via JS** (same-origin `fetch`, cookies ride
  along automatically), not extract a cookie for a separate HTTP client.

- **`GET /core/Sites/ByUser?pageSize=100&pageNumber=N`** → the user's own localities, paginated
  (`pageSize` caps at 100; response has `totalPages`/`totalCount`). **Session-scoped — no userId.**
  Each row: `id, name, presentationName` (the qualified "Name, Parent, Kommune, FylkeAbbr" the
  import matches), `longitude, latitude` (**WGS84 — no reprojection**), `isPrivate`, `accuracy`
  (= radius m), `municipalityName`, `countyName`, `parentSiteId`, `isPolygon`, `polygonCoordinates`
  (a JSON string `[[lon,lat],…]` in WGS84 → convert to `POLYGON((lon lat, …))` for our parser).

- **Other endpoints in the bundle (for the future), all under `/core/`:**
  `Sites/ByBoundingBox`, `Sites/Search`, `Sites/{id}`, `Sites/ByUser/LastUsed?top=`,
  `Taxons/*`, `TaxonName/Search`, `Areas/Names/*`, `Sightings/*`, `SightingSearch/*`,
  `Users/*`, `Projects`, `Inbox/*`, `MediaFiles/*`. A clean, modern REST surface.

### ✅ DONE (2026-06-04): harvest migrated to `Sites/ByBoundingBox` (issue #16)

`scripts/harvest_sites_mobil.py` (`just harvest`) harvests from the mobile API; the old
`GetSitesGeoJson` harvest and its GBIF/flag heuristics have been removed (the `isPrivate`
flag is now ground truth). The reverse-engineered contract:

    GET /core/Sites/ByBoundingBox?MinX=&MinY=&MaxX=&MaxY=&MaxSites=&IncludePublicSites=true
    Header: X-CSRF: 1   (no auth — see below)

- **No auth needed** (verified 2026-06-14): the endpoint answers an unauthenticated GET,
  returning the public allmenn registry — and, in fact, everyone's private sites too.
  `build_sites.py` keeps only the public ones for the bundled CSV, so the harvest needs no
  BFF cookie. (Your own privates come from the in-app `ByUser` sync below, not this harvest.)
- **`Min/Max X/Y` are WGS84 lon/lat** (X=lon, Y=lat) — no reprojection.
- **`IncludePublicSites=true` is essential**: without it the endpoint returns only private
  sites; with it you also get the public allmenn registry, like the old Report map.
- **Two server limits drive the tiling:** a box may span at most **~50 km in Web Mercator
  per side** (else `400 "BoundingBox too large"`, errorCode G8), and a response returns at
  most **1000 sites** (`MaxSites` caps at 1000; no paging). So the harvester tiles in Web
  Mercator at a safe size and **recursively quarters** any tile that hits the 1000 cap.
- **Rows** carry `id, name, presentationName, longitude, latitude, isPrivate, accuracy
  (=radius m), municipalityName, countyName, parentSiteId, isPolygon, polygonCoordinates`
  (already a `[[lon,lat],…]` list here, not the JSON string `ByUser` returns). `build_sites.py`
  auto-detects this flat-array shape vs the legacy GeoJSON; `hovedlokalitet` comes from
  resolving `parentSiteId` against the harvest's own id→name map.

Why this beats the legacy harvest: native WGS84 (drops the reprojection), a real per-row
`countyName` (the old build assumed one fylke per run), and one call returns every tier
(no multi-zoom union hack).

### In-app "Synk mine lokaliteter" plan (when we build it)
1. A menu action opens a WebView to `https://mobil.artsobservasjoner.no/`; the user logs in
   (the site does OIDC; the app never sees a password). App stays usable/public-only without it.
2. After login, page through `/core/Sites/ByUser` via `evaluateJavascript` running
   `fetch('/core/Sites/ByUser?pageSize=100&pageNumber=N',{headers:{'X-CSRF':'1'}})` until
   `pageNumber > totalPages`. No Kotlin HTTP client, no cookie handling — the WebView carries auth.
3. Map rows → our Locality (mine=1, public=0, WGS84 coords, `accuracy`→radius,
   `polygonCoordinates`→WKT), persist to the device's mine-file, merge into the picker (yellow).
   Online is the source of truth; re-sync replaces the local set. Cookie expiry → just re-login.

## ✅ SOLVED (2026-06-02): the authoritative allmenn flag — `FindSitesByName`

> **Superseded:** the mobile-API harvest now carries the authoritative `isPrivate` flag per
> row, so `build_sites.py` reads the allmenn flag straight from the harvest — no separate
> name-by-name fetch. `fetch_public_flags.py` has been removed. Kept below as a reference to
> the `FindSitesByName` contract in case the per-name flag is ever needed again.

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
- The retired `fetch_public_flags.py` queried every locality name to set this flag, with an
  "Uttian" canary to back off on throttling — before the mobile harvest made it unnecessary.

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
