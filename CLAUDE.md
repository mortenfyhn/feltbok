# Feltbok — repo guide

Offline-first Android app (Kotlin + Jetpack Compose, package `com.feltbok`, minSdk 26) for
capturing bird observations in Norway and exporting a TSV to paste into Artsobservasjoner's
"Importer observasjoner". Zero-login and shareable; works fully offline in the field.

## Build / test / run
- `./gradlew test` — JVM unit tests (also `just test`, which adds the Python tests).
- `./gradlew assembleDebug` — debug APK. `./gradlew assembleRelease` — signed release (needs the keystore).
- `just install` / `just run` — build + install (+ launch) on a connected device. When a device is
  connected (`adb devices`), use these to verify a change in the real app; drive the UI with
  `adb shell input tap/text` and inspect with `adb exec-out screencap`.
- Always run `./gradlew test` before opening a PR.
- `just fmt` auto-formats Kotlin (ktlint) + `process/` Python (ruff); `just lint` checks both. Both
  are gated in CI (ktlint in the Kotlin job, ruff in a parallel job). The ktlint ruleset
  (`.editorconfig`) only fixes whitespace/indentation/import order — it deliberately leaves line
  structure, line length, and naming alone, so it won't churn the terse hand-written style. ruff
  (`ruff.toml`) is the full black-style formatter for the Python pipelines.
- In a fresh worktree, copy `local.properties` from the repo root first (it's gitignored, and Gradle
  needs its `sdk.dir`).

## Conventions
- **Minimal diffs.** Implement only what the issue asks; don't expand scope or add features unasked.
  Prefer the smallest change, and removing code over adding it. Prefer pure functions; small focused classes.
- Match the surrounding style, naming, and comment density. Comments explain **WHY**, not what.
- **Commit messages explain the why. Do NOT add `Co-Authored-By` or other trailers** (this repo's
  history is trailer-free).
- **Disclose AI authorship.** When an agent files an issue or opens a PR, state upfront that the
  body is AI-generated so a human reader doesn't mistake it for human-written text.
- Norwegian (Bokmål) for user-facing strings.
- **Worktrees are opt-in.** Work in the main checkout by default. Only reach for EnterWorktree when
  the user explicitly asks, or when you know another session is editing the same files concurrently —
  the rebase/index tangles they create usually outweigh the isolation benefit.
- **Shared working tree.** Sometimes parallel agents run in the same checkout without worktrees, so
  expect unrelated uncommitted changes from another agent's work — that's normal, not a mistake to
  fix. Leave those changes alone and commit only the files that are part of your own task.
- **Expect last-minute tweaks.** The user often makes small manual edits to the work before asking
  you to commit. Re-check the diff at commit time and include those tweaks; don't revert them or
  assume the tree still matches what you last wrote.

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
