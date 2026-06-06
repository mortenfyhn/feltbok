export ANDROID_HOME := env("ANDROID_HOME", home_directory() / "Android/Sdk")

# Dev build carries the .debug applicationId suffix so it installs alongside the release app
# I use daily. Every device-facing recipe targets this id, not the release "com.feltbok".
app_id := "com.feltbok.debug"
apk := "app/build/outputs/apk/debug/app-debug.apk"
data_dir := "/sdcard/Android/data/" + app_id + "/files"

# List all recipes
default:
    @just --list

# Build debug APK
build:
    ./gradlew assembleDebug

# Run all tests: Python locality heuristics + Kotlin units
test:
    .venv/bin/python -m unittest discover -s process -p 'test_*.py'
    ./gradlew testDebugUnitTest

# Auto-format Kotlin (ktlint) + Python (ruff); run before committing manual tweaks
format:
    ./gradlew ktlintFormat
    .venv/bin/ruff format process/

# Check formatting/lint without changing files (what CI gates on)
lint:
    ./gradlew ktlintCheck
    .venv/bin/ruff check process/

# Install on connected device (-d allows downgrading over a newer build)
install: build
    adb install -r -d {{apk}}

# The activity class stays com.feltbok.MainActivity (the suffix changes the package id, not the
# code namespace), so spell out the full component rather than the /.MainActivity shorthand.

# Build, install, and launch the app
run: install
    adb shell am start -n {{app_id}}/com.feltbok.MainActivity

# Show device logs for the app
log:
    adb logcat -s Feltbok:* AndroidRuntime:* --format=brief

# Cut a signed release: bump version, build+sign, publish the GitHub release, push (see docs/release.md)
release version:
    ./release.sh {{version}}

# Uninstall the dev build from device (NOT the release app)
uninstall:
    adb uninstall {{app_id}}

# ---- Locality / species data, rebuilt on the maintainer's machine ----

# Harvest the official site list from Artsobservasjoner's mobile API (auth + usage in the script header)
sites *args:
    .venv/bin/python process/harvest_sites_mobil.py {{args}}

# Build app/src/main/assets/localities.csv from the harvested sites
build-sites *args:
    .venv/bin/python process/build_sites.py {{args}}

# Build app/src/main/assets/species.csv (Norwegian bird checklist, norsk + latin)
species:
    .venv/bin/python process/build_species.py

# Push the built localities/species CSVs to the device (overrides the bundled assets, no rebuild)
push-data:
    adb push app/src/main/assets/localities.csv {{data_dir}}/localities.csv
    -adb push app/src/main/assets/species.csv {{data_dir}}/species.csv
    -adb push my-localities.csv {{data_dir}}/my-localities.csv   # maintainer's own customs (device-only)
