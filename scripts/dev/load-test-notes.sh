#!/usr/bin/env bash
# Load dev/test-notes.json into the debug app on a connected device, then restart it.
# Targets the .debug build (`just install`) - never the maintainer's real com.feltbok, whose
# notes.json is real field data. run-as needs a debuggable build, which only the debug one is.
# WARNING: overwrites the debug app's notes.json — fine, it's a throwaway test install.
set -euo pipefail
PKG=com.feltbok.debug
SRC="$(dirname "$0")/test-notes.json"

adb push "$SRC" /data/local/tmp/notes.json >/dev/null
adb shell "run-as $PKG sh -c 'mkdir -p files && cat /data/local/tmp/notes.json > files/notes.json'"
adb shell rm /data/local/tmp/notes.json
adb shell am force-stop "$PKG"
# The activity class stays com.feltbok.MainActivity; the .debug suffix only changes the package id.
adb shell am start -n "$PKG/com.feltbok.MainActivity" >/dev/null
echo "Loaded $(grep -c '"id"' "$SRC") test observations and restarted $PKG."
