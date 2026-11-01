#!/usr/bin/env bash
# Builds a signed Release archive of the iOS app and uploads it to App Store Connect, where it shows
# up in TestFlight after Apple's processing (usually 10-30 minutes). Needs the paid developer team
# signed in to Xcode 26 (Settings > Accounts) and the app's record created in App Store Connect.
#   TEAM=XXXXXXXXXX ./upload-testflight.sh
# The version is the Android versionName; the build number is the commit count, so every upload
# gets a new, increasing one. Xcode 26: App Store builds need the iOS 26 SDK, and it is the last
# Xcode that still targets iOS 15 (the iPhone 7).
set -euo pipefail
cd "$(dirname "$0")"
: "${TEAM:?set TEAM to your developer team id}"
version=$(sed -n 's/.*versionName = "\(.*\)"/\1/p' ../app/build.gradle.kts)
build=$(git rev-list --count HEAD)
export DEVELOPER_DIR=${DEVELOPER_DIR:-/Applications/Xcode-26.app/Contents/Developer}
xcodegen
archive=../build/ios-archive/Feltbok.xcarchive
rm -rf "$archive"
echo "Archiving Feltbok $version ($build)..."
xcodebuild -project Feltbok.xcodeproj -scheme Feltbok -configuration Release \
  -destination 'generic/platform=iOS' -archivePath "$archive" -allowProvisioningUpdates \
  DEVELOPMENT_TEAM="$TEAM" MARKETING_VERSION="$version" CURRENT_PROJECT_VERSION="$build" \
  archive | grep -E 'error:|ARCHIVE (SUCCEEDED|FAILED)'
opts="$(mktemp -d)/ExportOptions.plist"
cat > "$opts" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>method</key><string>app-store-connect</string>
  <key>destination</key><string>upload</string>
  <key>teamID</key><string>$TEAM</string>
  <key>signingStyle</key><string>automatic</string>
</dict>
</plist>
PLIST
echo "Uploading to App Store Connect..."
xcodebuild -exportArchive -archivePath "$archive" -exportOptionsPlist "$opts" \
  -exportPath ../build/ios-export -allowProvisioningUpdates | grep -E 'error:|EXPORT (SUCCEEDED|FAILED)|Upload'
echo "Uploaded Feltbok $version ($build). It appears in TestFlight once Apple has processed it."
