# Publishing Feltbok to Google Play

Play is more involved than F-Droid: it's a manual Play Console upload of a signed
**AAB** (not the APK), plus a pile of one-time listing/compliance forms. Unlike
F-Droid there's no build-from-source automation — you build locally and upload.

## Prerequisites (one-time)

- **A registered Play Console developer account.** The account admin (registration,
  identity verification, the closed-testing gate) is tracked on issue #74, not here —
  it's one-off paperwork, not something a future reader of this repo needs.
- **Privacy policy URL** — mandatory for any app requesting location. We request
  `ACCESS_FINE/COARSE_LOCATION`, so a hosted policy page is required (a section in
  the repo's GitHub Pages / README link is fine).
- **Target API level** — done: `compileSdk`/`targetSdk = 36` (Android 16), which
  Play requires for new apps and updates from 31 Aug 2026. F-Droid has no such
  requirement — the bump was for Play.
- **Free, not priced** — so no payments profile and no merchant account.

## Listing assets

The store text reuses the same `fastlane/metadata/android/{nb-NO,en-US}/` tree as
F-Droid. Play additionally needs assets we don't have yet:

- **App icon** 512×512 PNG.
- **Feature graphic** 1024×500 PNG.
- **Phone screenshots** — at least 2 (drop into
  `fastlane/metadata/android/<locale>/images/phoneScreenshots/`). Capture with
  `adb exec-out screencap -p > shot.png` on a real run.
- **Data safety form** + **content rating questionnaire** — filled in the Console.
  Feltbok keeps notes on-device and sends nothing to a backend, so the data-safety
  answers are minimal (location used on-device, no data collected/shared).

## Build the upload artifact

Play wants an Android App Bundle:

```sh
./gradlew bundleRelease   # -> app/build/outputs/bundle/release/app-release.aab
```

Signed the same way as the APK (keystore.properties / env vars; see `release.md`).

## Signing

With **Play App Signing**, Google holds the final signing key; you upload with an
*upload key* and Google re-signs. So the Play install has yet another signature for
`io.github.mortenfyhn.feltbok` (GitHub keystore / F-Droid key / Play key are all different). This is
a non-issue here — Android won't cross-upgrade between channels, but switching means
uninstall + reinstall, and notes are short-lived (exported then deleted), so there's
nothing to migrate. Just set the upload key from `feltbok-release.keystore` so future
Play releases stay consistent with each other.

## Optional automation later

`gradle-play-publisher` (Triple-T) can push the AAB + the `fastlane/` listing tree
to a Play track from CI. Not worth wiring up until the manual first release is done
and the account is verified.
