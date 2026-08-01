# Releasing Feltbok

Releases are **signed APKs published as GitHub releases**, cut locally with `./release.sh`.
The release page is the share-with-birders landing spot: each one prepends the get-started
steps from `release-notes-install.md` via `gh release create --notes-file`, and those steps
link to the one screenshot walkthrough in [install.md](install.md). The README points users at
the latest release for install — so install info lives in exactly those two files, never duplicated.
The bundled APK ships **public localities only** - the maintainer's own customs
(`my-localities.csv`) are gitignored and pushed to the dev phone separately.

## Cut a release

Soak the build first and walk the [pre-release checklist](pre-release-checklist.md) — the
interaction paths (map tap-gating especially) that the automated suites can't cover.

First draft the version's entry in `CHANGELOG.md` (terse, user-facing highlights only). Then:

```sh
./release.sh 0.8
```

The script bumps `versionCode`+`versionName` in `app/build.gradle.kts`, then **pauses** so you
can finalize the `## v0.8` changelog section — that section becomes the release notes, so nothing
is tagged or pushed until you press Enter. After that it commits (version bump + changelog), tags
`v0.8`, builds both flavors' signed APK (`feltbok-v0.8.apk` for Norway and
`feltbok-se-v0.8.apk` for Sweden — both attached to the one release), pushes, and runs
`gh release create` with notes assembled from the install steps + the changelog entry + the
auto-generated "What's Changed" list. No manual GitHub edit afterwards.

**F-Droid "what's new":** F-Droid shows `fastlane/metadata/android/<locale>/changelogs/<versionCode>.txt`
inline per version. The script **generates the Norwegian one** (`nb-NO`) from the `## vX` section, so
`CHANGELOG.md` stays the single source for it — but skips generation if the file already exists, so a
hand-tuned entry (e.g. one omitting an installer-only note) wins. The **English** one (`en-US`) is
hand-written each release (Norway-only app, so non-nb users are rare); the script warns if it's
missing but doesn't block. Write it during the pause.

A drafted (uncommitted) `CHANGELOG.md` — and the per-version `changelogs/*.txt` files — are allowed
to be dirty when you start; everything else must be committed. `versionCode` auto-increments so each release is an upgrade
Android will install over the last; the in-app version string comes from `git describe`, so it
shows `0.8` at the tag. Needs a clean `master`, the keystore (below), and an authenticated `gh`.
Building locally is much faster than the old cold Semaphore tag build.

## Signing

The release is signed with `feltbok-release.keystore` (RSA, alias `feltbok`). The keystore and
its `keystore.properties` are **gitignored - never commit them** (public repo). `build.gradle.kts`
reads the keystore from `keystore.properties` locally, or from env vars in CI; with neither, the
release builds **unsigned** (so a plain checkout still compiles).

> **Back up `feltbok-release.keystore` and its password.** Losing the key means you can no
> longer ship updates that install over an existing install - everyone would have to uninstall
> first.

Build one locally:

```sh
./gradlew assembleRelease   # -> app/build/outputs/apk/release/app-release.apk
```

## Target API level

Play requires new apps and updates to target a recent API — **36 (Android 16) from
31 Aug 2026** — and raises the bar every year, so expect to bump `compileSdk`/`targetSdk`
annually once we're on Play. F-Droid has no such requirement, so a bump is always for
Play's sake. Bumping is rarely free: 34 -> 36 needed AGP + Gradle bumps, and edge-to-edge
handling, since from targetSdk 35 the system draws behind the system bars with no opt-out.

## CI

Semaphore (`.semaphore/semaphore.yml`) runs tests, ktlint, and a debug build on every push;
it no longer builds releases (those are cut locally now), so no signing secret is needed there.

## Distribution roadmap (Play Protect / #13)

Sideloaded APKs always trigger a **Play Protect** warning ("Play Protect har ikke sett
noen apper fra denne utvikleren før"). Nothing in the APK can suppress it — it's a
client-side Google feature keyed on whether the app/developer is known to Google Play.
We mitigate it for now with clear install instructions (above); the steps below remove
or future-proof it.

- **Free Limited Distribution Account** (Android developer verification). Early access
  June 2026; **no government ID**, **up to 20 devices**. Register the release signing key
  + `io.github.mortenfyhn.feltbok`. Doesn't silence today's scan prompt, but future-proofs installability
  in Norway when verification enforcement reaches Europe (2027+), and is the same identity
  reused for Play later.
  See <https://support.google.com/android-developer-console/answer/16561738>.
- **Google Play** (in progress, #74 — free, not priced). The only channel with *no*
  Play Protect prompt — apps installed via Play aren't treated as unknown. Upload is an
  **AAB**, not an APK (`./gradlew bundleRelease`). With **Play App Signing** Google holds
  the final signing key and we upload with an *upload key*, so a Play install carries a
  third signature (GitHub keystore / F-Droid key / Play key all differ). Consequence worth
  knowing: nobody can move between channels without uninstalling first. Harmless here —
  notes are exported then deleted, so there's nothing to migrate. Set the upload key from
  `feltbok-release.keystore` so Play releases stay consistent with each other. Account
  paperwork and listing assets are tracked on #74, not in this repo.
- **F-Droid** (deferred, free). Convenience/auto-update; still sideload-scanned.
