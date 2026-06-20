export ANDROID_HOME := env("ANDROID_HOME", home_directory() / "Android/Sdk")

# Dev build carries the .debug applicationId suffix so it installs alongside the release app
# I use daily. Every device-facing recipe targets this id, not the release "io.github.mortenfyhn.feltbok".
app_id := "io.github.mortenfyhn.feltbok.debug"
apk := "app/build/outputs/apk/debug/app-debug.apk"
data_dir := "/sdcard/Android/data/" + app_id + "/files"

# List all recipes
default:
    @just --list

# Build debug APK
build:
    ./gradlew assembleDebug

# Run all tests: Python units (if any) + Kotlin units
test:
    .venv/bin/python -m unittest discover -s scripts -p 'test_*.py'
    ./gradlew testDebugUnitTest

# Wire up the version-controlled git hooks (run once per fresh clone)
hooks:
    git config core.hooksPath .githooks

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
install: build
    adb install -r -d {{apk}}

# The activity class is io.github.mortenfyhn.feltbok.MainActivity (the .debug suffix changes the
# package id, not the code namespace), so spell out the full component rather than the
# /.MainActivity shorthand.

# Build, install, and launch the app
run: install
    adb shell am start -n {{app_id}}/io.github.mortenfyhn.feltbok.MainActivity

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

# Build app/src/main/assets/species_months.csv (per-species monthly report counts, for season ranking)
build-species-months:
    .venv/bin/python scripts/build_species_months.py

# Build app/src/main/assets/species_regions.csv (per-grid-cell species counts, for locality ranking)
build-species-regions:
    .venv/bin/python scripts/build_species_regions.py

# Push the built localities/species CSVs to the device (overrides the bundled assets, no rebuild)
push-data:
    adb push app/src/main/assets/localities.csv {{data_dir}}/localities.csv
    -adb push app/src/main/assets/species.csv {{data_dir}}/species.csv
    -adb push app/src/main/assets/species_months.csv {{data_dir}}/species_months.csv
    -adb push app/src/main/assets/species_regions.csv {{data_dir}}/species_regions.csv
    -adb push my-localities.csv {{data_dir}}/my-localities.csv   # maintainer's own customs (device-only)
