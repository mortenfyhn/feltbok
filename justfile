export ANDROID_HOME := env("ANDROID_HOME", home_directory() / "Android/Sdk")

# Dev build carries the .debug applicationId suffix so it installs alongside the release app
# I use daily. Every device-facing recipe targets this id, not the release "io.github.mortenfyhn.feltbok".
# build/install/run take a country code (no=Norway [default], se=Sweden) so `just run` stays the
# Norway daily driver and `just run se` drives the Sweden flavor. Extend _check-country and the
# per-recipe flavor mappings when a new country flavor lands.
app_id := "io.github.mortenfyhn.feltbok.debug"
data_dir := "/sdcard/Android/data/" + app_id + "/files"


# Reject an unknown country code before a recipe derives a flavor from it (silent-default footgun).
_check-country country:
    @case "{{country}}" in no|se) ;; *) echo "Unknown country '{{country}}' (use: no or se)" >&2; exit 1 ;; esac

# List all recipes
set default-list := true

# Build debug APK (no=Norway [default], se=Sweden)
build country="no": (_check-country country)
    ./gradlew assemble{{ if country == "se" { "Sweden" } else { "Norway" } }}Debug

# Run all tests: Python units (if any) + Kotlin units (both flavors)
test:
    .venv/bin/python -m unittest discover -s scripts -p 'test_*.py'
    ./gradlew testNorwayDebugUnitTest testSwedenDebugUnitTest

# Wire up the version-controlled git hooks (run once per fresh clone)
hooks:
    git config core.hooksPath .githooks

# Instrumented R8 smoke tests on an attached device (src/androidTest, minified releaseTest
# variant). Catches reflection-strip regressions unit tests can't — see CLAUDE.md / #117. Skips
# (doesn't fail) when no device is attached, so it's safe to call from a wrapper. NOT in CI: no
# emulator on Semaphore, and it installs io.github.mortenfyhn.feltbok.releasetest — never the real io.github.mortenfyhn.feltbok.
itest:
    #!/usr/bin/env bash
    set -euo pipefail
    if [ -z "$(adb devices | sed '1d' | grep -w device || true)" ]; then
        echo "No device attached — skipping instrumented tests."; exit 0
    fi
    # The launch test needs the activity to reach RESUMED, so the screen must stay on and unlocked
    # throughout (a dark/locked screen leaves it STOPPED). `stayon` holds the screen on for the
    # whole run — a one-shot wake re-dozes during the build; then wake + dismiss a swipe-keyguard.
    # (A secure PIN/pattern lock can't be dismissed by adb — unlock the phone first if so.)
    adb shell svc power stayon true
    adb shell input keyevent KEYCODE_WAKEUP
    adb shell wm dismiss-keyguard
    # Just the norway flavor: the tests are flavor-agnostic, so the sweden variant would only
    # re-run the same checks. (Per-flavor task names exist since the country flavors landed.)
    ./gradlew connectedNorwayReleaseTestAndroidTest

# Auto-format Kotlin (ktlint) + Python (ruff); run before committing manual tweaks
format:
    ./gradlew ktlintFormat
    .venv/bin/ruff format scripts/

# Check formatting/lint without changing files (what CI gates on)
lint:
    ./gradlew ktlintCheck
    .venv/bin/ruff check scripts/

# CI fails late on style slips (e.g. blank-line-before-declaration) that `just test` alone
# never checks; run this before pushing, or `just format` first to auto-fix the mechanical stuff.

# Run the full CI gate locally: ktlint + ruff, unit tests, debug APK
ci: lint test build

# Install on connected device (-d allows downgrading over a newer build)
install country="no": (build country)
    #!/usr/bin/env bash
    flavor={{ if country == "se" { "sweden" } else { "norway" } }}
    adb install -r -d "app/build/outputs/apk/$flavor/debug/app-$flavor-debug.apk"

# The activity class is io.github.mortenfyhn.feltbok.MainActivity (the .debug suffix changes the
# package id, not the code namespace), so spell out the full component rather than the
# /.MainActivity shorthand.

# Build, install, and launch the app (no=Norway [default], se=Sweden)
run country="no": (install country)
    adb shell am start -n io.github.mortenfyhn.feltbok{{ if country == "se" { ".se" } else { "" } }}.debug/io.github.mortenfyhn.feltbok.MainActivity

# Show device logs for the app
log:
    adb logcat -s Feltbok:* AndroidRuntime:* --format=brief

# Uninstall the dev build from device (NOT the release app)
uninstall:
    adb uninstall {{app_id}}

# ---- Locality / species data, rebuilt on the maintainer's machine ----

# Harvest the official site list from Artsobservasjoner's mobile API (no auth; usage in the script header)
harvest *args:
    .venv/bin/python scripts/harvest_sites_mobil.py {{args}}

# Build app/src/main/assets/localities.csv from the harvested sites
build-localities *args:
    .venv/bin/python scripts/build_sites.py {{args}}

# Build app/src/main/assets/species.csv (Norwegian bird checklist, norsk + latin)
build-species:
    .venv/bin/python scripts/build_species.py

# Merge both flavors' checklists into the unified latin,norsk,svensk,status,count schema, with
# authoritative names from the IOC World Bird List (pass --ioc for a non-default xlsx path)
build-species-names *args:
    .venv/bin/python scripts/build_species_names.py {{args}}

# Build app/src/main/assets/species_months.csv (per-species monthly report counts, for season ranking)
build-species-months:
    .venv/bin/python scripts/build_species_months.py

# Build app/src/main/assets/species_regions.csv (per-grid-cell species counts, for locality ranking)
build-species-regions:
    .venv/bin/python scripts/build_species_regions.py

# Push the built localities/species CSVs to the device (overrides the bundled assets, no rebuild)
push-data:
    adb push app/src/norway/assets/localities.csv {{data_dir}}/localities.csv
    -adb push app/src/norway/assets/species.csv {{data_dir}}/species.csv
    -adb push app/src/norway/assets/species_months.csv {{data_dir}}/species_months.csv
    -adb push app/src/norway/assets/species_regions.csv {{data_dir}}/species_regions.csv
    -adb push my-localities.csv {{data_dir}}/my-localities.csv   # maintainer's own customs (device-only)

# Seed the DEBUG app with varied sample observations (overwrites its notes!). The debug build ships
# non-debuggable (release speed), so run-as can't reach internal storage; instead push a seed file
# to the external dir, which the dev build imports on next launch (see importSeedNotes). Targets the
# .debug app only — never the release app with the maintainer's real field notes.
seed:
    .venv/bin/python scripts/dev/make_sample_notes.py > /tmp/feltbok-seed-notes.json
    adb push /tmp/feltbok-seed-notes.json {{data_dir}}/seed-notes.json
    adb shell am force-stop {{app_id}}

# Regenerate the F-Droid/Play store icon + feature graphic (fastlane/.../en-US/images) from the
# hand-drawn SVG in scripts/dev/store-assets/. Needs rsvg-convert + imagemagick.
render-store-assets:
    scripts/dev/store-assets/render.sh
