# Publishing Feltbok to F-Droid

F-Droid builds the app from source on its own buildserver and signs it with the
**F-Droid signing key** (we chose the easy path over reproducible builds). It then
distributes and auto-updates the app from our `v*` git tags.

Feltbok is already F-Droid-ready: AGPL-3.0 licensed, no proprietary dependencies
(only AndroidX, kotlinx-coroutines and osmdroid), and the release build falls back
to *unsigned* when no keystore is present — exactly what the buildserver needs.

## What lives where

- The build recipe (`metadata/io.github.mortenfyhn.feltbok.yml`) lives **only** in
  our fdroiddata fork (<https://gitlab.com/fdroid/fdroiddata>), not in this repo —
  F-Droid never reads a copy from here, and a vendored duplicate just drifts. Edit
  it in the fork and run the checks below before pushing. It must stay in F-Droid's
  **canonical form** (no comments, specific field order) or fdroiddata CI rejects it
  — `fdroid rewritemeta` rewrites anything that isn't. Put explanations here in this
  README, not as YAML comments.
- `fastlane/metadata/android/{nb-NO,en-US}/` (repo root) — store listing text and
  changelogs. F-Droid reads these straight from the tag, so the listing stays in
  source control. The same tree is what Play's publishing tools read too (see
  `../play-store.md`).

## Submitting (one-time)

The app is already submitted; this is the recipe of what was done, and the checks
to re-run after editing the recipe in the fork.

1. Fork <https://gitlab.com/fdroid/fdroiddata> and clone your fork.
2. Edit `metadata/io.github.mortenfyhn.feltbok.yml` in that checkout.
3. Run the same checks the fdroiddata CI runs (from the fdroiddata root):
   ```sh
   fdroid lint io.github.mortenfyhn.feltbok                                                # recipe sanity
   fdroid rewritemeta io.github.mortenfyhn.feltbok      # canonical form; re-run, file must be unchanged
   pipx run check-jsonschema --schemafile schemas/metadata.json metadata/io.github.mortenfyhn.feltbok.yml
   fdroid build -v -l io.github.mortenfyhn.feltbok      # full source build, needs the fdroidserver toolchain
   ```
   `rewritemeta`'s idempotency and the schema check are the CI gates that are easy
   to miss — `lint` and `build` passing does **not** imply the file is canonical or
   schema-valid. (`git diff --exit-code` only works once the change is committed;
   for an uncommitted edit, run `rewritemeta` twice and confirm the file is stable.)
4. Open a merge request (commit `New app: io.github.mortenfyhn.feltbok (Feltbok)` per
   the F-Droid Quick Start Guide). We declare two informational `AntiFeatures`:
   `NonFreeNet` (depends on the proprietary Artsobservasjoner service) and
   `TetheredNet` (relies on OpenStreetMap tile servers — same as osmdroid and other
   live-tile apps). Use the **full commit hash** in `Builds:`, not a tag — reviewers
   require it.

> **`fdroid build` from a pip install** looks for a `gradlew-fdroid` wrapper inside
> its `site-packages/`, which the PyPI wheel doesn't ship — the build dies with
> `No such file or directory: .../gradlew-fdroid`. Drop the matching version's
> script (`gitlab.com/fdroid/fdroidserver` at your installed tag) in there and
> `chmod 755` it. Not needed if you use F-Droid's Docker image instead.

Once merged, every new `v*` tag is picked up automatically — no further action per
release.

## Heads-up: signing identity

F-Droid signs with its own key, so the F-Droid build has a different signature from
the GitHub-released APK even though both are `io.github.mortenfyhn.feltbok`. Android won't upgrade
across signatures, so switching channels means uninstall + reinstall. In practice
this is a non-issue: notes are short-lived (exported, then deleted), so there's
nothing to migrate. New F-Droid users just install fresh.
