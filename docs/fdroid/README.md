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
  to keep in sync when a new tag ships. Kept in F-Droid's **canonical form** (no
  comments, specific field order) because fdroiddata CI rejects anything that isn't
  — `fdroid rewritemeta` would rewrite it. After editing, run the checks below so it
  still passes; put explanations here in this README, not as YAML comments.
- `fastlane/metadata/android/{nb-NO,en-US}/` (repo root) — store listing text and
  changelogs. F-Droid reads these straight from the tag, so the listing stays in
  source control. The same tree is what Play's publishing tools read too (see
  `../play-store.md`).

## Submitting (one-time)

1. Fork <https://gitlab.com/fdroid/fdroiddata> and clone your fork.
2. Copy `com.feltbok.yml` to `metadata/com.feltbok.yml` in that checkout.
3. Run the same checks the fdroiddata CI runs (from the fdroiddata root):
   ```sh
   fdroid lint com.feltbok                                                # recipe sanity
   fdroid rewritemeta com.feltbok && git diff --exit-code metadata/       # must be a no-op
   pipx run check-jsonschema --schemafile schemas/metadata.json metadata/com.feltbok.yml
   fdroid build -v -l com.feltbok      # full source build, needs the fdroidserver toolchain
   ```
   The middle two are the CI gates that are easy to miss — `lint` and `build`
   passing does **not** imply the file is canonical or schema-valid.
4. Open a merge request (branch `com.feltbok`, commit `New App: com.feltbok` per the
   F-Droid Quick Start Guide). A maintainer reviews it; they may add an
   `AntiFeatures` tag (e.g. `NonFreeNet` for the optional WebView sync to
   Artsobservasjoner) — that's cosmetic, not a blocker.

> **`fdroid build` from a pip install** looks for a `gradlew-fdroid` wrapper inside
> its `site-packages/`, which the PyPI wheel doesn't ship — the build dies with
> `No such file or directory: .../gradlew-fdroid`. Drop the matching version's
> script (`gitlab.com/fdroid/fdroidserver` at your installed tag) in there and
> `chmod 755` it. Not needed if you use F-Droid's Docker image instead.

Once merged, every new `v*` tag is picked up automatically — no further action per
release.

## Heads-up: signing identity

F-Droid signs with its own key, so the F-Droid build has a different signature from
the GitHub-released APK even though both are `com.feltbok`. Android won't upgrade
across signatures, so switching channels means uninstall + reinstall. In practice
this is a non-issue: notes are short-lived (exported, then deleted), so there's
nothing to migrate. New F-Droid users just install fresh.
