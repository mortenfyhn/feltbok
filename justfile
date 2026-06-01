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

# Build the official locality table from the Artsdatabanken/GBIF dump.
# Pass extra args, e.g.: just localities --api --bbox 8.0,63.5,9.3,63.9
localities *args:
    .venv/bin/python process/build_localities.py {{args}}

# Build the Norwegian bird checklist (norsk,latin) from GBIF -> species.csv
species:
    .venv/bin/python process/build_species.py

# Push the generated locality/species CSVs to the device (overrides the bundled
# assets — no rebuild needed). Run after `just localities`.
push-data:
    adb push localities.csv {{data_dir}}/localities.csv
    -adb push species.csv {{data_dir}}/species.csv
