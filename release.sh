#!/usr/bin/env bash
# Cut a signed Feltbok release locally — replaces the old (slow) Semaphore Release job.
# Bumps the version, builds + signs the APK, publishes the GitHub release, and pushes.
#
#   ./release.sh 0.8
#
# Needs keystore.properties + the keystore present (see docs/release.md) and gh
# authenticated. Run from a clean master.
set -euo pipefail

version="${1:?usage: ./release.sh <version>   e.g. 0.8}"
tag="v$version"
gradle="app/build.gradle.kts"

[ -z "$(git status --porcelain)" ] || { echo "Working tree not clean — commit or stash first."; exit 1; }
[ "$(git rev-parse --abbrev-ref HEAD)" = master ] || { echo "Not on master."; exit 1; }
git rev-parse "$tag" >/dev/null 2>&1 && { echo "Tag $tag already exists."; exit 1; }
[ -f keystore.properties ] || { echo "keystore.properties missing — release would be unsigned."; exit 1; }

# versionCode must increase for every release Android should treat as an upgrade; just +1.
old_code=$(grep -oP 'versionCode = \K\d+' "$gradle")
new_code=$((old_code + 1))

echo "Releasing $tag  (versionCode $old_code → $new_code)"
read -rp "Continue? [y/N] " ok
[ "$ok" = y ] || { echo "Aborted."; exit 1; }

sed -i "s/versionCode = .*/versionCode = $new_code/" "$gradle"
sed -i "s/versionName = .*/versionName = \"$version\"/" "$gradle"
git commit -aqm "Release $tag"
git tag "$tag"

# Build AFTER tagging so `git describe` bakes the clean tag into the in-app version string.
./gradlew assembleRelease --console=plain
apk="feltbok-$tag.apk"   # a recognisable name, not Gradle's app-release.apk
cp app/build/outputs/apk/release/app-release.apk "$apk"

# Push before creating the release so its auto-generated notes can resolve the tag + commits.
git push origin master "$tag"
gh release create "$tag" "$apk" \
    --title "Feltbok $tag" \
    --notes-file docs/release-notes-install.md \
    --generate-notes

rm -f "$apk"
echo "Released $tag → https://github.com/mortenfyhn/feltbok/releases/tag/$tag"
