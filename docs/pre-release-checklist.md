# Pre-release manual checklist

What to walk through by hand before cutting a release, on top of the automated suites. Keep it to
the **interaction-heavy paths automation can't reach** — don't re-test what the tests below already
cover. The spirit is a short **field soak** (use the build for a few real outings; see the v1 plan),
not a one-off tap-through.

## Already automated — don't re-test these by hand

- `./gradlew test` — pure-JVM units: the export's checks and request body, note JSON round-trip, species-search ranking
  (search is a pure function, so its logic is covered here, not on-device).
- `just itest` (device, minified `releaseTest` variant; also a `release.sh` pre-flight): app launch,
  osmdroid Projection, asset loads, **add-observation flow** (search → save → in list), **export
  flow** against a fake server (sent, the bar, the result, deleting). A regression in these fails the gate, not the shipped APK.
- `just itest` also covers the UI wiring on top of the unit-tested logic: **undo** (snackbar after a
  delete, Angre puts the note back, navigating away dismisses it, undo after a **discarded draft**
  returns to the intact editor, and undo after a **saved single edit** restores the original values,
  **Back** out of species search, the export overlay, Synk, settings, the map picker and the
  co-observer picker, the **list keeping its scroll position** across a trip into the editor,
  **batch edit** writing only to the marked notes, **Kopier** carrying the original's locality
  into the new draft, and saving a **new spot** keeping you on the picker.
- `just itest` also covers the **co-observer picker** (switching names on, Ingen medobservatører, marking and
  deleting names with undo), the **papirkurv** (restore, footer hidden while marking), a fast
  **pinch zoom**, and notes, papirkurv and use scores **surviving a restart**.
- Host tests cover the logic behind the network features with a fake server: the **login update**
  (own localities, toasts, failures), the **live locality fetch** (splitting cut-off responses,
  the 2 km cells), the **export** (checks before sending, stopping and resuming, a rejected note,
  the login check), and **files written by v1.4** loading after the update.
- `just upgrade-test` (device; also a `release.sh` pre-flight): builds the last release, enters
  notes, a deleted note, a co-observer and a name-language setting through its UI, installs this
  build over it, and checks the app still shows them all.

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

**Export against the live site** *(host tests use a fake server)*
- Run `LiveUploadTest` with a logged-in cookie (the command is in its KDoc). It uploads a note for
  every case, reads each back, checks it and deletes it. Then delete the "Feltbok live-test" site
  it leaves under Mine lokaliteter.
- `just seed` a fresh batch and export from the app. Each note three days back, and the one dated
  in the future, is listed with its error line (`scripts/dev/seed_notes.py --why` says which).
  Delete those and export again. All the rest reach **Kontroller funn**, and the **Austrått** row links to Ørland's site (354419), not
  Sandnes'. Delete them there afterwards.
- On your own phone, export one note at **one of your private localities** and one at a
  **live-fetched** public locality (one the map toast reported). Each links to that exact site.

**Upgrade from the previous release** *(`just upgrade-test` covers the data on the test build,
and `UpdateFromV14Test` the private localities and old notes)*
- `release.sh` keeps the signed APK locally until the push prompt. Install it over the daily
  release app before answering `yes`, to check the signed build installs as an update.

**Login and network on the release build** *(the R8 smoke tests don't reach these code paths, and
host tests use a fake server)*
- Logged out, the picker footer says "Logg inn for å se private lokaliteter". Logging in closes
  the page by itself and returns to the picker with the draft intact. Your private localities
  appear with one toast, and the footer turns into "Oppdater private lokaliteter".
- "Oppdater private lokaliteter" shows the spinner and one toast. In flight mode it says "Ingen
  internettilkobling".
- "Logg ut" shows "Logga ut" and turns the footer back into "Logg inn for å se private
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
  picker footer, "Ingen medobservatører").

**Other interaction bits** *(what's left after the itest wiring layer — see above)*
- Search names with **å/æ/ø** return the expected birds.
- A tap just outside a circle's edge selects it. A map fling starts moving right away.
- Copy an observation → the locality picker **centres on that observation's own locality** (#91-era).
  The tests only get as far as "the copy kept the locality": `pickerCamera` is unit-tested, but the
  map is an AndroidView, so where it actually centred never reaches the semantics tree.

After a clean soak (a few field days, no surprises), cut the release per [release.md](release.md).
