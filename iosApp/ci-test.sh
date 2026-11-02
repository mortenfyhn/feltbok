#!/usr/bin/env bash
# Runs the shared tests on the iOS simulator, then builds the iOS app and runs its launch smoke test
# (FeltbokUITests). Used by CI (.github/workflows/ios.yml) and works the same locally. SIMULATOR (a
# device id) picks the simulator; by default the first available iPhone.
set -euo pipefail
cd "$(dirname "$0")"
# The shared logic's tests on the simulator: Kotlin/Native runs them differently from the JVM.
(cd .. && ./gradlew :shared:iosSimulatorArm64Test --console=plain -q)
xcodegen
# By id: a name alone means the newest runtime, which may not have that device.
sim=${SIMULATOR:-$(xcrun simctl list devices available | grep -m1 -E '^ +iPhone' | grep -oE '[0-9A-F-]{36}')}
echo "Testing on $(xcrun simctl list devices | grep "$sim" | sed 's/ *(Shutdown)//;s/ *(Booted)//;s/^ *//')"
xcodebuild -project Feltbok.xcodeproj -scheme Feltbok -configuration Debug \
  -destination "platform=iOS Simulator,id=$sim" -derivedDataPath ../build/ios-test test \
  | grep -E 'error:|Test Case|Executed|TEST (SUCCEEDED|FAILED)|\*\* (BUILD|TEST)'
