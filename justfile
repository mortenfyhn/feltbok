export ANDROID_HOME := env("ANDROID_HOME", home_directory() / "Android/Sdk")

apk := "app/build/outputs/apk/debug/app-debug.apk"
data_dir := "/sdcard/Android/data/com.appobs/files"

# Build debug APK
build:
    ./gradlew assembleDebug

# Run all tests: Python locality heuristics + Kotlin units
test:
    .venv/bin/python -m unittest discover -s process -p 'test_*.py'
    ./gradlew testDebugUnitTest

# Install on connected device
install: build
    adb install -r {{apk}}

# Build, install, and launch the app
run: install
    adb shell am start -n com.appobs/.MainActivity

# Show device logs for the app
log:
    adb logcat -s Feltbok:* AndroidRuntime:* --format=brief

# Uninstall from device
uninstall:
    adb uninstall com.appobs

# ---- PRIMARY locality pipeline: Artsobservasjoner's own site registry ----
# Harvest the authoritative site list (real geometry, radius, allmenn flag) from the
# Report-map endpoint. Needs a logged-in session cookie in /tmp/aspx_cookie.txt (see the
# script header). Resumable + gentle. Widen with --bbox; eventually all of Norway.
sites *args:
    .venv/bin/python process/harvest_sites.py {{args}}

# Build app/src/main/assets/localities.csv from the harvested raw GeoJSON (no GBIF needed).
build-sites *args:
    .venv/bin/python process/build_sites.py {{args}}

# ---- LEGACY GBIF pipeline (superseded by `sites`/`build-sites`; kept for reference) ----
# Build the official locality table from the Artsdatabanken/GBIF dump.
# Pass extra args, e.g.: just localities --api --bbox 8.0,63.5,9.3,63.9
localities *args:
    .venv/bin/python process/build_localities.py {{args}}

# Build the Norwegian bird checklist (norsk,latin) from GBIF -> species.csv
species:
    .venv/bin/python process/build_species.py

# Add real locality polygons (GBIF footprintWKT, reprojected) to localities.csv.
# Run after `just localities` to enrich the table with a `geometry` column.
footprints:
    .venv/bin/python process/add_footprints.py

# Flag each locality public/private by distinct reporters; add a `public` column.
# Streams the full raw harvest to localities-occurrences.jsonl. Pass --from-raw to
# re-derive from that cache in seconds (no re-harvest):  just mark-public --from-raw
mark-public *args:
    .venv/bin/python process/mark_public.py {{args}}

# Set the AUTHORITATIVE public/private flag from Artsobservasjoner's own allmenn flag
# (POST /ViewSighting/FindSitesByName, public/no-auth). Run after mark-public — it
# replaces the heuristic guess where Artsobs confirms, and keeps it where a lookup is
# capped/failed. The real source of truth; supersedes the observer/polygon heuristic.
public-flags:
    .venv/bin/python process/fetch_public_flags.py

# Push the generated locality/species CSVs to the device (overrides the bundled
# assets — no rebuild needed). Run after `just localities`.
push-data:
    adb push app/src/main/assets/localities.csv {{data_dir}}/localities.csv
    -adb push app/src/main/assets/species.csv {{data_dir}}/species.csv
    -adb push my-localities.csv {{data_dir}}/my-localities.csv   # the maintainer's own customs (device-only)
