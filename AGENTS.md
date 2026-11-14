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
  `assembleReleaseTestAndroidTest`, no device needed).
- Before every commit, run `just ci`: the same checks as Semaphore (lint, tests, builds). Don't
  commit on red.
- iOS code (`iosMain`, `iosApp/`) can't compile on Linux; only GitHub's macOS runner builds it. After
  touching it, push to a branch and wait for the iOS workflow to pass before merging to `master`
  (pushing still needs consent).
- **Release runs through R8** (`isMinifyEnabled`, keep rules in `app/proguard-rules.pro`). R8
  tree-shakes by static reachability, so reflective code (reflective mappers, `Class.forName`,
  WebView JS bridges) can be stripped and break the release build at runtime only. When adding such
  a dependency, check `assembleRelease` output for `Missing class`/`-dontwarn` notes and add a keep
  rule if needed.
- **Instrumented R8 smoke tests** (`just itest`, device-gated) run against the minified `releaseTest`
  variant, so an R8 strip fails a test instead of shipping broken. Skipped cleanly when no device is
  attached. Also carries a UI-flow layer (`UndoFlowTest`, `NavigationFlowTest`, `EditFlowTest`) for
  behaviour a pure-JVM test can't reach.
- `just format` / `just lint` are CI-gated (ktlint / ruff). ktlint only fixes whitespace/imports —
  it won't churn line structure or naming.
- Fresh worktree: copy `local.properties` from the main checkout (gitignored, Gradle needs it), and
  link its Python venv. Worktrees don't all live at the same depth, so use the main checkout's path:
  `main=$(git worktree list | head -1 | cut -d' ' -f1); cp $main/local.properties .; ln -s $main/.venv .venv`.
- CI runs on Semaphore (`.semaphore/semaphore.yml`); inspect failures with `sem get pipelines` /
  `sem logs <jobid>`. Releases are cut locally with `./release.sh` (see `docs/release.md`).
- iOS CI runs on GitHub Actions' free macOS runners (`.github/workflows/ios.yml` ->
  `iosApp/ci-test.sh`): `:shared`'s tests on the iOS simulator, then the app's launch smoke test (an
  XCUITest that opens the list and the map). Put new `:shared` tests in `commonTest` (`kotlin.test`)
  so they run on both JVM and iOS; only JVM-only ones go in `jvmTest`.

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
- **Goal: no manual testing beyond a quick bench check** (#201). Releases shouldn't depend on
  soaking the app in the field, so every change to behaviour comes with tests, not only bug fixes:
  - A bug fix gets a regression test. Check that it fails without the fix.
  - Prefer a pure-JVM test (`commonTest` in `:shared`, or `androidHostTest`). When only the UI can
    reach the behaviour, write an instrumented test. Being UI is no reason to skip the test.
  - Look for untested behaviour in the code nearby, especially cheap wins, and propose tests for
    it, so coverage grows over time.
  - If no test can reach a change at a reasonable cost, say so and discuss it. Never skip it
    silently.
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

## Usability
Strive to follow Jakob Nielsen's ten usability heuristics
(https://www.nngroup.com/articles/ten-usability-heuristics/):

1. **Visibility of system status:** designs should keep users informed about what is going on,
   through appropriate, timely feedback.
2. **Match between system and the real world:** the design should speak the users' language. Use
   words, phrases, and concepts familiar to the user, rather than internal jargon.
3. **User control and freedom:** users often perform actions by mistake. They need a clearly marked
   "emergency exit" to leave the unwanted action.
4. **Consistency and standards:** users should not have to wonder whether different words,
   situations, or actions mean the same thing. Follow platform conventions.
5. **Error prevention:** good error messages are important, but the best designs carefully prevent
   problems from occurring in the first place.
6. **Recognition rather than recall:** minimize the user's memory load by making elements, actions,
   and options visible. Avoid making users remember information.
7. **Flexibility and efficiency of use:** shortcuts, hidden from novice users, may speed up the
   interaction for the expert user.
8. **Aesthetic and minimalist design:** interfaces should not contain information which is
   irrelevant. Every extra unit of information in an interface competes with the relevant units of
   information.
9. **Recognize, diagnose, and recover from errors:** error messages should be expressed in plain
   language (no error codes), precisely indicate the problem, and constructively suggest a solution.
10. **Help and documentation:** it's best if the design doesn't need any additional explanation.
    However, it may be necessary to provide documentation to help users complete their tasks.

## Where things live
- `shared/` — Kotlin Multiplatform module with the platform-free code (search, frequency, use
  scores, the species, locality and note models, locality geometry, live localities, co-observer and
  selection logic, date formatting, the JSON formats of the app's files and API responses, loading
  and saving them through a per-platform `Storage`), consumed by the app as a JVM library. Being
  moved into piece by piece for the iOS port (#115).
- `ui/` — Kotlin Multiplatform module (Android + iOS, Compose Multiplatform) with the UI, built on
  `:shared`. `commonMain`: `MainViewModel.kt` (all UI state, no nav lib), `Ui.kt` and `App.kt` (the
  Compose screens), `Model.kt` (TSV export), `SyncScreen.kt` ("Hent mine lokaliteter", over a
  platform `SyncWeb`), `MapLogic.kt` (the picker map's shared rules), theme, `Strings`, `Country`,
  and `Platform.kt` — what the UI needs from each platform: the `Platform` interface the view model
  gets, `PlatformUi` (pickers, links, toasts) and `expect` composables for the map. `androidMain`
  implements them: `AndroidPlatform.kt`, `PlatformUi.android.kt`,
  `MapPicker.kt` (osmdroid picker map), `SyncWeb.android.kt` (WebView), `LocationTracker.kt`,
  `AndroidStorage.kt`. `iosMain` is the same for iOS (`IosPlatform.kt`, `PlatformUi.ios.kt`,
  `MapPicker.kt` + `MapLayers.kt` on MapLibre, `SyncWeb.ios.kt` on WKWebView, CoreLocation, and
  `MainViewController.kt` as the root). Unit tests are
  `androidHostTest` (`:ui:testAndroidHostTest`).
- `iosApp/` — the iOS app: a thin SwiftUI host for `:ui`'s framework. `xcodegen` there generates the
  Xcode project from `project.yml` (not committed); `install-device.sh` builds and installs on a
  connected iPhone (Xcode 26 for iOS 15 devices), `upload-testflight.sh` uploads a TestFlight build
  (see `docs/release.md`). The bundled CSVs come from `app/src/main/assets`.
- `app/` — just the Android shell: `MainActivity`, `FeltbokApp` (fills `AppInfo` from BuildConfig),
  resources, assets, and the instrumented tests.
- `app/src/main/assets/localities.csv` (public, bundled), `species.csv` (checklist + Rødlista
  status). The user's own privates live in `my-localities.csv` — device-only, never bundled.
- `scripts/` — Python build/harvest pipelines (localities, species, red list) + dev helpers
  (`scripts/dev/`) and the ruff config. Driven by the `justfile`.

## Data / API notes
- Locality + checklist data come from Artsobservasjoner; the mobile API
  (`mobil.artsobservasjoner.no/core/...`) is the modern source. See
  `docs/custom-localities-design.md`.
- Releases: signed APK cut locally with `./release.sh` (tags `v*`). See `docs/release.md`.
