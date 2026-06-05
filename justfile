export ANDROID_HOME := env("ANDROID_HOME", home_directory() / "Android/Sdk")

apk := "app/build/outputs/apk/debug/app-debug.apk"
data_dir := "/sdcard/Android/data/com.feltbok/files"

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

# Install on connected device (-d allows downgrading over a newer build)
install: build
    adb install -r -d {{apk}}

# Build, install, and launch the app
run: install
    adb shell am start -n com.feltbok/.MainActivity

# Show device logs for the app
log:
    adb logcat -s Feltbok:* AndroidRuntime:* --format=brief

# Uninstall from device
uninstall:
    adb uninstall com.feltbok

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
