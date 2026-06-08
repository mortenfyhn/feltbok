#!/usr/bin/env bash
# Cut a signed Feltbok release locally — replaces the old (slow) Semaphore Release job.
# Bumps the version, pauses so you can finalize the CHANGELOG.md entry, then builds +
# signs the APK, tags, pushes, and publishes the GitHub release with those notes.
#
#   ./release.sh 0.8
#
# Needs keystore.properties + the keystore present (see docs/release.md) and gh
# authenticated. Run from a clean master (a drafted CHANGELOG.md entry is fine).
set -euo pipefail

version="${1:?usage: ./release.sh <version>   e.g. 0.8}"
tag="v$version"
gradle="app/build.gradle.kts"
changelog="CHANGELOG.md"

# A drafted changelog entry is the one thing allowed to be dirty going in — everything
# else must be committed so the Release commit is just the version bump + changelog.
dirty=$(git status --porcelain | grep -v ' CHANGELOG.md$' || true)
[ -z "$dirty" ] || { echo "Working tree not clean (besides $changelog) — commit or stash first."; exit 1; }
[ "$(git rev-parse --abbrev-ref HEAD)" = master ] || { echo "Not on master."; exit 1; }
git rev-parse "$tag" >/dev/null 2>&1 && { echo "Tag $tag already exists."; exit 1; }
[ -f keystore.properties ] || { echo "keystore.properties missing — release would be unsigned."; exit 1; }

# versionCode must increase for every release Android should treat as an upgrade; just +1.
old_code=$(grep -oP 'versionCode = \K\d+' "$gradle")
new_code=$((old_code + 1))

echo "Releasing $tag  (versionCode $old_code → $new_code)"
echo "Finalize the '## $tag' section in $changelog now — it becomes the release notes."
echo "Nothing below the next prompt is reversible without force-pushing."
read -rp "Press Enter to finalize (tag, build, push, publish) — Ctrl-C to abort " _

# Pull this version's notes out of the changelog (lines under '## vX' up to the next '## ').
notes_body=$(awk -v h="## $tag" '$0 ~ "^"h {g=1; next} g && /^## / {exit} g' "$changelog")
[ -n "$notes_body" ] || { echo "No '## $tag' section in $changelog — aborting."; exit 1; }

sed -i "s/versionCode = .*/versionCode = $new_code/" "$gradle"
sed -i "s/versionName = .*/versionName = \"$version\"/" "$gradle"
git commit -aqm "Release $tag"
git tag "$tag"

# Build AFTER tagging so `git describe` bakes the clean tag into the in-app version string.
./gradlew assembleRelease --console=plain
apk="feltbok-$tag.apk"   # a recognisable name, not Gradle's app-release.apk
cp app/build/outputs/apk/release/app-release.apk "$apk"

# Release notes = install steps + this version's changelog; --generate-notes appends
# the auto "What's Changed" list below.
notes=$(mktemp)
{ cat docs/release-notes-install.md; printf '\n## Nytt i %s\n%s\n' "$tag" "$notes_body"; } > "$notes"

# Push before creating the release so its auto-generated notes can resolve the tag + commits.
git push origin master "$tag"
gh release create "$tag" "$apk" \
    --title "Feltbok $tag" \
    --notes-file "$notes" \
    --generate-notes

rm -f "$apk" "$notes"
echo "Released $tag → https://github.com/mortenfyhn/feltbok/releases/tag/$tag"
