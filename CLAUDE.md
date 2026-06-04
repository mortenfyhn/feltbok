# Feltbok — repo guide

Offline-first Android app (Kotlin + Jetpack Compose, package `com.feltbok`, minSdk 26) for
capturing bird observations in Norway and exporting a TSV to paste into Artsobservasjoner's
"Importer observasjoner". Zero-login and shareable; works fully offline in the field.

## Build / test / run
- `./gradlew test` — JVM unit tests (also `just test`, which adds the Python tests).
- `./gradlew assembleDebug` — debug APK. `./gradlew assembleRelease` — signed release (needs the keystore).
- `just install` / `just run` — build + install on a connected device (dev only).
- Always run `./gradlew test` before opening a PR.

## Conventions
- **Minimal diffs.** Implement only what the issue asks; don't expand scope or add features unasked.
  Prefer the smallest change, and removing code over adding it. Prefer pure functions; small focused classes.
- Match the surrounding style, naming, and comment density. Comments explain **WHY**, not what.
- **Commit messages explain the why. Do NOT add `Co-Authored-By` or other trailers** (this repo's
  history is trailer-free).
- Norwegian (Bokmål) for user-facing strings.
- **Worktree by default for real work.** When starting non-trivial implementation (editing/creating
  source files), first isolate with EnterWorktree so parallel sessions don't collide. Skip it for
  read-only questions, quick one-file edits, and investigations.

## Where things live
- `app/src/main/java/com/feltbok/Model.kt` — data classes, CSV/JSON load+save, TSV export.
- `MainViewModel.kt` — all UI state (four-ish screens, no nav lib). `Ui.kt` — the Compose screens.
- `MapPicker.kt` — osmdroid locality-picker map + overlays. `SyncScreen.kt` — WebView "Synk mine lokaliteter".
- `app/src/main/assets/localities.csv` (public, bundled), `species.csv` (checklist + Rødlista status).
  The user's own privates live in `my-localities.csv` — device-only, never bundled/committed.
- `process/` — Python build/harvest pipelines (localities, species, red list). Driven by the `justfile`.

## Data / API notes
- Locality + checklist data come from Artsobservasjoner; the new mobile API
  (`mobil.artsobservasjoner.no/core/...`) is the modern source. See `docs/custom-localities-design.md`.
- Releases: signed APK via Semaphore on `v*` tags. See `docs/release.md`.
