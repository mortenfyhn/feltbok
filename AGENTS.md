# Feltbok — repo guide

Offline-first Android app (Kotlin + Jetpack Compose, package `io.github.mortenfyhn.feltbok`, minSdk 26)
for capturing bird observations in Norway and exporting a TSV to paste into Artsobservasjoner's
"Importer observasjoner". Zero-login and shareable; works fully offline in the field.

## Build / test / run
Recipes live in `just --list`. Gotchas not covered there:

- `just install` / `just run` build and install the **debug** build on a connected device. **Ask
  before testing on the phone** — the usual flow is that the maintainer runs `just run` and tests by
  hand. Build and report; never install on the device unless okayed in that moment.
- `allWarningsAsErrors` means any deprecation introduced by a dependency bump is a build failure.
  `./gradlew test` and `assembleRelease` do NOT compile `src/androidTest`, so after bumping
  Compose/AndroidX, also compile that source set (`just itest` or
  `assembleNorwayReleaseTestAndroidTest`, no device needed).
- Always run `./gradlew test` before opening a PR; `just test` also runs the Python tests.
- **Release runs through R8** (`isMinifyEnabled`, keep rules in `app/proguard-rules.pro`). R8
  tree-shakes by static reachability, so reflective code (reflective mappers, `Class.forName`,
  WebView JS bridges) can be stripped and break the release build at runtime only. When adding such
  a dependency, check `assembleRelease` output for `Missing class`/`-dontwarn` notes and add a keep
  rule if needed.
- **Instrumented R8 smoke tests** (`just itest`, device-gated) run against the minified `releaseTest`
  variant, so an R8 strip fails a test instead of shipping broken. Skipped cleanly when no device is
  attached. Also carries a UI-wiring layer (`UndoFlowTest`, `NavigationFlowTest`, `EditFlowTest`) —
  add to it only where a pure-JVM test genuinely can't reach, and only once the UI has settled.
- `just format` / `just lint` are CI-gated (ktlint / ruff). ktlint only fixes whitespace/imports —
  it won't churn line structure or naming.
- Fresh worktree: copy `local.properties` from the repo root (gitignored, Gradle needs it), and link
  the Python venv: `ln -s ../../../.venv .venv` from the worktree root.
- CI runs on Semaphore (`.semaphore/semaphore.yml`); inspect failures with `sem get pipelines` /
  `sem logs <jobid>`. Releases are cut locally with `./release.sh` (see `docs/release.md`).

## Device safety
Two builds coexist on the device: debug carries the `.debug` applicationId suffix and label
`Feltbok (dev)`; the maintainer's daily **release** app is `io.github.mortenfyhn.feltbok`. Always go
through `just` (or use the `.debug` package explicitly) when installing, launching (`am start -n
io.github.mortenfyhn.feltbok.debug/io.github.mortenfyhn.feltbok.MainActivity` — the class keeps the
un-suffixed namespace), or clearing data. **NEVER `adb uninstall` or `pm clear`
`io.github.mortenfyhn.feltbok`** — that wipes the maintainer's real field notes (`notes.json` lives
in internal storage).

Instrumented tests share one install and `MainViewModel` persists notes, so every test that builds
a `MainViewModel` must start with `ClearAppDataRule` — otherwise the suite becomes order-dependent.

## Conventions
- **Minimal diffs.** Implement only what's asked; prefer the smallest change and removing code over
  adding it. Prefer pure functions; small focused classes. Match surrounding style and comment
  density (comments explain WHY).
- **Keep new logic platform-free.** Put new non-UI logic in the `:shared` module
  (`shared/src/commonMain`, plain Kotlin, no Android or JVM imports; like `Search.kt`), not in
  `Model.kt` or `MainViewModel.kt`, so the coming iOS app (#115) gets it too. It builds for JVM
  everywhere and for iOS on macOS hosts only.
- **Avoid custom styling unless necessary.** Lean on Material defaults and existing shared
  composables; extend a component (e.g. a flag on an existing row) over bespoke layout.
- **Commit messages in English, explaining the why. No trailers** (`Co-Authored-By` etc. — history
  is trailer-free). User-facing strings and the changelog stay Norwegian Bokmål — radical bokmål
  (`lagra`, `henta`, `sletta`; nudge, don't churn). Only commit messages are English.
- **Closing issues:** put `Fix #50` (etc.) in the commit message so GitHub auto-closes.
- **Merging:** the usual flow is branch → quick manual test → merge to `master`, no PR. Only open a
  PR when explicitly asked.
- **Never push without explicit consent** — including `master`.
- **Disclose AI authorship** when filing issues or PRs.
- **Docs & changelog stay in sync, same commit, unprompted.** When a change alters user-visible
  behavior or makes a doc inaccurate, update it (especially `docs/behavior.md`) and add a
  `CHANGELOG.md` entry in the same commit.
- **Changelog:** `CHANGELOG.md` is user-facing (Bokmål, casual `du`-tone), per-version sections with
  short benefit-framed highlights (fold cosmetic tweaks into a "Ymse småforbedringer" line). Add a
  bullet under the standing **"Neste utgivelse"** heading for any user-visible change. At release
  time that heading is renamed by hand during `release.sh`'s pause step, which pulls notes from the
  `## v<version>` section (aborts if missing) — the same text is pasted into the GitHub release.
- **Shared working tree:** parallel agents may leave unrelated uncommitted changes; leave them alone
  and commit only your own files. The user often makes last-minute manual edits — re-check the diff
  at commit time rather than reverting or assuming the tree matches what you last wrote.
- **New UI lands through several rounds.** While a design is still in flux, don't eagerly delete
  now-unused helpers, rename things, or churn tests — save tidy-up until the user says they're
  happy with how it looks.
- **Worktrees are opt-in.** Work in the main checkout by default. In a worktree
  (`.claude/worktrees/<name>/`), every file operation MUST target that path — shell cwd follows you
  in, but absolute paths in tool calls are NOT rewritten, so a stale main-checkout path silently
  edits the wrong tree. Re-grep inside the worktree after entering; confirm `git status` before
  trusting a build.

## Where things live
- `shared/` — Kotlin Multiplatform module with the platform-free code (search, frequency, use
  scores, the species, locality and note models, locality geometry, live localities, co-observer and
  selection logic, date formatting, the JSON formats of the app's files and API responses), consumed
  by the app as a JVM library. Being moved into piece by piece for the iOS port (#115).
- `app/src/main/java/io/github/mortenfyhn/feltbok/Model.kt` — CSV loading, file load+save, TSV
  export.
- `MainViewModel.kt` — all UI state (four-ish screens, no nav lib). `Ui.kt` — the Compose screens.
- `MapPicker.kt` — osmdroid locality-picker map + overlays. `SyncScreen.kt` — WebView "Synk mine
  lokaliteter".
- `app/src/<flavor>/assets/localities.csv` (public, bundled), `species.csv` (checklist + Rødlista
  status). The user's own privates live in `my-localities.csv` — device-only, never bundled.
- `scripts/` — Python build/harvest pipelines (localities, species, red list) + dev helpers
  (`scripts/dev/`) and the ruff config. Driven by the `justfile`.

## Data / API notes
- Locality + checklist data come from Artsobservasjoner; the mobile API
  (`mobil.artsobservasjoner.no/core/...`) is the modern source. See
  `docs/custom-localities-design.md`.
- Releases: signed APK cut locally with `./release.sh` (tags `v*`). See `docs/release.md`.
