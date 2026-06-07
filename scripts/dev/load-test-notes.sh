#!/usr/bin/env bash
# Load dev/test-notes.json into the (debug-installed) app on a connected device, then restart it.
# Requires a *debuggable* build (./gradlew installDebug) so `run-as` can write the app's files dir.
# WARNING: overwrites the device's notes.json — only use on a throwaway/test install.
set -euo pipefail
PKG=com.feltbok
SRC="$(dirname "$0")/test-notes.json"

adb push "$SRC" /data/local/tmp/notes.json >/dev/null
adb shell "run-as $PKG sh -c 'mkdir -p files && cat /data/local/tmp/notes.json > files/notes.json'"
adb shell rm /data/local/tmp/notes.json
adb shell am force-stop "$PKG"
adb shell am start -n "$PKG/.MainActivity" >/dev/null
echo "Loaded $(grep -c '"id"' "$SRC") test observations and restarted $PKG."
