# Publishing Feltbok to F-Droid

F-Droid builds the app from source on its own buildserver and signs it with the
**F-Droid signing key** (we chose the easy path over reproducible builds). It then
distributes and auto-updates the app from our `v*` git tags.

Feltbok is already F-Droid-ready: AGPL-3.0 licensed, no proprietary dependencies
(only AndroidX, kotlinx-coroutines and osmdroid), and the release build falls back
to *unsigned* when no keystore is present — exactly what the buildserver needs.

## What lives where

- `com.feltbok.yml` (this folder) — the build recipe. It is **not** used by our own
  Gradle build; it's the file we submit to fdroiddata. Versioned here so it's easy
  to keep in sync when a new tag ships.
- `fastlane/metadata/android/{nb-NO,en-US}/` (repo root) — store listing text and
  changelogs. F-Droid reads these straight from the tag, so the listing stays in
  source control. The same tree is what Play's publishing tools read too (see
  `../play-store.md`).

## Submitting (one-time)

1. Fork <https://gitlab.com/fdroid/fdroiddata> and clone your fork.
2. Copy `com.feltbok.yml` to `metadata/com.feltbok.yml` in that checkout.
3. Sanity-check and test-build locally with the F-Droid tooling:
   ```sh
   fdroid readmeta
   fdroid lint com.feltbok
   fdroid build -v -l com.feltbok      # full source build, needs the fdroidserver toolchain
   ```
4. Open a merge request. An F-Droid maintainer reviews it; they may add an
   `AntiFeatures` tag (e.g. `NonFreeNet` for the optional WebView sync to
   Artsobservasjoner) — that's cosmetic, not a blocker.

Once merged, every new `v*` tag is picked up automatically — no further action per
release.

## Heads-up: signing identity

F-Droid signs with its own key, so the F-Droid build has a different signature from
the GitHub-released APK even though both are `com.feltbok`. Android won't upgrade
across signatures, so switching channels means uninstall + reinstall. In practice
this is a non-issue: notes are short-lived (exported, then deleted), so there's
nothing to migrate. New F-Droid users just install fresh.
