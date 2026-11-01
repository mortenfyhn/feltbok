#!/usr/bin/env bash
# Builds the iOS app in Release and installs it on the connected iPhone. Uses Xcode 26, since Xcode 27
# can't deploy to iOS 15 (the iPhone 7). TEAM is your Personal Team id (Xcode > Settings > Accounts),
# kept out of git. A free team can only sign bundle ids it already has a profile for on the device,
# so BUNDLE_ID can override the real one.
#   TEAM=XXXXXXXXXX [BUNDLE_ID=...] ./install-device.sh
set -euo pipefail
cd "$(dirname "$0")"
: "${TEAM:?set TEAM to your Personal Team id}"
[ -d Feltbok.xcodeproj ] || xcodegen
DEVELOPER_DIR=${DEVELOPER_DIR:-/Applications/Xcode-26.app/Contents/Developer} xcodebuild \
  -project Feltbok.xcodeproj -scheme Feltbok -configuration Release -destination 'generic/platform=iOS' \
  -derivedDataPath ../build/ios-device DEVELOPMENT_TEAM="$TEAM" \
  PRODUCT_BUNDLE_IDENTIFIER="${BUNDLE_ID:-io.github.mortenfyhn.feltbok}" build | grep -E 'error:|BUILD (SUCCEEDED|FAILED)'
ideviceinstaller install ../build/ios-device/Build/Products/Release-iphoneos/Feltbok.app
