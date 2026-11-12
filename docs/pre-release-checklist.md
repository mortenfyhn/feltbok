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
- `just itest` also covers the UI wiring on top of the unit-tested logic: **undo** (snackbar after a
  delete, Angre puts the note back, navigating away dismisses it, undo after a **discarded draft**
  returns to the intact editor, and undo after a **saved single edit** restores the original values,
  **Back** out of species search, the export overlay, Synk, settings, the map picker and the
  co-observer picker, the **list keeping its scroll position** across a trip into the editor,
  **batch edit** writing only to the marked notes, **Kopier** carrying the original's locality
  into the new draft, and saving a **new spot** keeping you on the picker.
- `just itest` also covers the **co-observer picker** (switching names on, Slå av alle, marking and
  deleting names with undo), the **papirkurv** (restore, footer hidden while marking), a fast
  **pinch zoom**, and notes, papirkurv and use scores **surviving a restart**.
- Host tests cover the logic behind the network features with a fake server: the **login update**
  (own localities, toasts, failures), the **live locality fetch** (splitting cut-off responses,
  the 2 km cells), **site-id export**, and **files written by v1.4** loading after the update.

## Hand-test these — automation can't (or doesn't yet)

**Map locality tap-gating** *(the top risk — pixel/projection-bound, deliberately left manual)*
- Pick a locality **zoomed out** and **zoomed in** — the right one selects each time.
- In a **dense cluster**, the tap lands on the intended locality, not a neighbour.
- **Polygon** localities: a tap inside selects it; a tap well outside its footprint does **not**.
- A large locality stays selectable when it (nearly) fills the screen, but a zoomed-in tap doesn't
  grab a huge one you're only skimming the edge of.
- **Create a new spot** (＋): after Lagre it's highlighted on the map, and tapping it picks it.

**GPS-dependent behaviour** (no fix in the test harness, so unverified there)
- Status strip shows the nearest locality + distance once GPS settles; "du er her" within range.
- Current locality **sticks** as you move a little, re-snaps after a genuine move (~50 m).
- Just outside a **polygon** locality, the strip shows the distance to its edge, not "du er her".
- A new spot made from the status strip becomes the current locality.

**Paste-import against the live site** *(the export format end-to-end — automation stops at "TSV
renders"; the real import site can't be scripted)*
- `just seed` a clean batch, then export from the app and paste into Artsobservasjoner "Importer
  observasjoner". `release.sh` offers to do the install + seed + launch for you, so during a release
  only the paste is by hand. The seed spans every export path (new-spot coords + radius, blank `Antall`,
  uncertain, same-day/multi-day ranges, the two no-time cases, 0/1/12 co-observers).
- It should validate with **no errors** and rows should reach **Kontroller funn** (not just the green
  banner). Any error is a real regression — see [artsobs-import.md](artsobs-import.md) for the format.
- Leave the import form's area (kommune/fylke) field **empty**: it rejects every site id outside it.
- In Kontroller funn, the **Austrått** row links to Ørland's site (354419), not Sandnes'.
- On your own phone, export one note at **one of your private localities** and one at a
  **live-fetched** public locality (one the map toast reported). Each links to that exact site,
  and the import doesn't create a private duplicate.

**Upgrade from the previous release** *(the host test reads v1.4's files; nothing installs the new
APK over the old one)*
- `release.sh` keeps the signed APK locally until the push prompt. Install it over the daily
  release app before answering `yes`. Notes, papirkurv, co-observers, the party, name-language
  settings and private localities are all still there. Notes saved under the old version still
  export (by name).

**Login and network on the release build** *(the R8 smoke tests don't reach these code paths, and
host tests use a fake server)*
- Logged out, the picker footer says "Logg inn for å vise private lokaliteter". Logging in closes
  the page by itself and returns to the picker with the draft intact. Your private localities
  appear with one toast, and the footer turns into "Oppdater lokaliteter".
- "Oppdater lokaliteter" shows the spinner and one toast. In flight mode it says "Ingen
  internettilkobling".
- "Logg ut" shows "Logga ut" and turns the footer back into "Logg inn for å vise private
  lokaliteter". The next login asks for the password.
- "‹ Tilbake" from the login page returns to the picker.
- Kill and restart the app: still logged in (the session cookie was saved).
- Logged out in a dense area (central Trondheim, zoom 15-16), localities fill in over a few
  seconds with nothing missing. Logged in, the same view loads in one go.
- Co-observer search online: 3 letters shows "Søker…", then users with place. Search a common
  name: the brukeralias shows only for namesakes from the same place. Offline, only "Legg til «…»".

**Large text and screen edges** *(no tests check layout)*
- Set font size and display size to max on the narrowest phone you have. Walk the list, the
  selection bar while marking, the editor, the time dialog, the co-observer picker, the export
  screen, the feedback dialog and the new-locality panel. No overlap, no words broken mid-word,
  no dialog as wide as the screen.
- With both gesture navigation and 3-button navigation: the green bars reach the screen edges,
  status-bar icons are readable, and nothing tappable sits under the navigation bar (list footer,
  picker footer, "Slå av alle").

**Other interaction bits** *(what's left after the itest wiring layer — see above)*
- Search names with **å/æ/ø** return the expected birds.
- A tap just outside a circle's edge selects it. A map fling starts moving right away.
- Copy an observation → the locality picker **centres on that observation's own locality** (#91-era).
  The tests only get as far as "the copy kept the locality": `pickerCenter` is unit-tested, but the
  map is an AndroidView, so where it actually centred never reaches the semantics tree.

After a clean soak (a few field days, no surprises), cut the release per [release.md](release.md).
