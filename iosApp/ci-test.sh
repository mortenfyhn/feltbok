#!/usr/bin/env bash
# Builds the iOS app for the simulator and runs its launch smoke test (FeltbokUITests). Used by CI
# (.github/workflows/ios.yml) and works the same locally. SIMULATOR (a device id) picks the
# simulator; by default the first available iPhone.
set -euo pipefail
cd "$(dirname "$0")"
xcodegen
# By id: a name alone means the newest runtime, which may not have that device.
sim=${SIMULATOR:-$(xcrun simctl list devices available | grep -m1 -E '^ +iPhone' | grep -oE '[0-9A-F-]{36}')}
echo "Testing on $(xcrun simctl list devices | grep "$sim" | sed 's/ *(Shutdown)//;s/ *(Booted)//;s/^ *//')"
xcodebuild -project Feltbok.xcodeproj -scheme Feltbok -configuration Debug \
  -destination "platform=iOS Simulator,id=$sim" -derivedDataPath ../build/ios-test test \
  | grep -E 'error:|Test Case|Executed|TEST (SUCCEEDED|FAILED)|\*\* (BUILD|TEST)'
