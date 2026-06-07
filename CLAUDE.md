# Feltbok — repo guide

Offline-first Android app (Kotlin + Jetpack Compose, package `com.feltbok`, minSdk 26) for
capturing bird observations in Norway and exporting a TSV to paste into Artsobservasjoner's
"Importer observasjoner". Zero-login and shareable; works fully offline in the field.

## Build / test / run
Recipes live in `just --list` — the notes below are only the why's and gotchas that aren't in there.
- `just install` / `just run` verify a change in the real app on a connected device (`adb devices`);
  drive the UI with `adb shell input tap/text` and inspect with `adb exec-out screencap`.
- Always run `./gradlew test` (Kotlin units) before opening a PR; `just test` also runs the Python tests.
- `./gradlew assembleRelease` — signed release, needs the keystore (no `just` recipe for it).
- **Two builds coexist on the device.** The dev build carries the `.debug` applicationId suffix
  (`com.feltbok.debug`), so it installs alongside the maintainer's daily **release** app
  (`com.feltbok`). `just install`/`just run`/`just uninstall` all target the **debug** package — so
  always go through `just` (or use `com.feltbok.debug` explicitly) when installing, launching
  (`am start -n com.feltbok.debug/com.feltbok.MainActivity`), or clearing data. NEVER
  `adb uninstall com.feltbok` or `pm clear com.feltbok`: that is the maintainer's real app and wipes
  their actual field notes (`notes.json` lives in internal storage, lost on uninstall). The two
  builds are distinguishable by the footer version string (`… (dev)` = debug) and the app label
  (`Feltbok (dev)` for debug vs `Feltbok (beta)` for release).
- `just format`/`just lint` are gated in CI (ktlint in the Kotlin job, ruff in a parallel block). The
  ktlint ruleset (`.editorconfig`) only fixes whitespace/indentation/import order — it deliberately
  leaves line structure, line length, and naming alone, so it won't churn the terse hand-written
  style. ruff (`ruff.toml`) is the full black-style formatter for the Python pipelines.
- In a fresh worktree, copy `local.properties` from the repo root first (it's gitignored, and Gradle
  needs its `sdk.dir`).
- CI runs on Semaphore (`.semaphore/semaphore.yml`): a `Test & build` block (Android container) and a
  parallel `Lint` block (plain VM — no SDK needed for ruff). Releases are cut locally with
  `./release.sh` (see `docs/release.md`), not by CI. Inspect
  failing runs locally with the `sem` CLI (`sem get pipelines`, `sem logs <jobid>`). Pipeline YAML
  reference: https://docs.semaphore.io/reference/pipeline-yaml (note: once one block sets
  `dependencies`, all blocks must).

## Conventions
- **Minimal diffs.** Implement only what the issue asks; don't expand scope or add features unasked.
  Prefer the smallest change, and removing code over adding it. Prefer pure functions; small focused classes.
- Match the surrounding style, naming, and comment density. Comments explain **WHY**, not what.
- **Commit messages explain the why. Do NOT add `Co-Authored-By` or other trailers** (this repo's
  history is trailer-free).
- **Closing issues.** When a commit resolves an issue, put a closing keyword (`Fix #50`, `Closes #50`)
  in the commit message so GitHub auto-closes the issue when it lands on `master`.
- **Disclose AI authorship.** When an agent files an issue or opens a PR, state upfront that the
  body is AI-generated so a human reader doesn't mistake it for human-written text.
- **Changelog.** `CHANGELOG.md` is the user-facing changelog (Bokmål, casual `du`-tone). It starts
  at v1.0 — don't add entries before then. The v1.0 entry is a quick overview of the main features;
  v1.1 onward are proper per-version entries with short, benefit-framed highlights (fold minor/cosmetic
  tweaks into one "Ymse småforbedringer" line rather than listing each). When landing a user-visible
  change, add a bullet under the current unreleased version. The same per-version text is pasted into
  the GitHub release ("Nytt i …", see `docs/release.md`).
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
- Releases: signed APK cut locally with `./release.sh` (tags `v*`). See `docs/release.md`.
