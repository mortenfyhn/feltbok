# Pre-release manual checklist

What to walk through by hand before cutting a release, on top of the automated suites. Keep it to
the **interaction-heavy paths automation can't reach** — don't re-test what the tests below already
cover. The spirit is a short **field soak** (use the build for a few real outings; see the v1 plan),
not a one-off tap-through.

## Already automated — don't re-test these by hand

- `./gradlew test` — pure-JVM units: TSV export cells, note JSON round-trip, species-search ranking
  (search is a pure function, so its logic is covered here, not on-device).
- `just itest` (device, minified `releaseTest` variant; also a `release.sh` pre-flight): app launch,
  osmdroid Projection, asset loads, **add-observation flow** (search → save → in list), **export
  flow** (open → TSV renders → copy). A regression in these fails the gate, not the shipped APK.

## Hand-test these — automation can't (or doesn't yet)

**Map locality tap-gating** *(the top risk — pixel/projection-bound, deliberately left manual)*
- Pick a locality **zoomed out** and **zoomed in** — the right one selects each time.
- In a **dense cluster**, the tap lands on the intended locality, not a neighbour.
- **Polygon** localities: a tap inside selects it; a tap well outside its footprint does **not**.
- A large locality stays selectable when it (nearly) fills the screen, but a zoomed-in tap doesn't
  grab a huge one you're only skimming the edge of.
- **Create a new spot** (＋), drop it, confirm it's selectable for the next observation.

**GPS-dependent behaviour** (no fix in the test harness, so unverified there)
- Status strip shows the nearest locality + distance once GPS settles; "du er her" within range.
- Current locality **sticks** as you move a little, re-snaps after a genuine move (~50 m).

**Paste-import against the live site** *(the export format end-to-end — automation stops at "TSV
renders"; the real import site can't be scripted)*
- `just seed` a clean batch, then export from the app and paste into Artsobservasjoner "Importer
  observasjoner". The seed spans every export path (new-spot coords + radius, blank `Antall`,
  uncertain, same-day/multi-day ranges, the two no-time cases, 0/1/12 co-observers).
- It should validate with **no errors** and rows should reach **Kontroller funn** (not just the green
  banner). Any error is a real regression — see [artsobs-import.md](artsobs-import.md) for the format.

**Other interaction bits**
- Search names with **å/æ/ø** return the expected birds.
- Copy an observation → the locality picker **centres on that observation's own locality** (#91-era).
- Undo snackbar after a delete / discarded draft; system Back behaves per screen.

After a clean soak (a few field days, no surprises), cut the release per [release.md](release.md).
