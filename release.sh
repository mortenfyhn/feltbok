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

# Drafted changelog entries are the one thing allowed to be dirty going in — CHANGELOG.md
# plus the per-version fastlane "what's new" files (the English one is hand-written; see
# below). Everything else must be committed so the Release commit is just the bump + notes.
dirty=$(git status --porcelain | grep -vE ' CHANGELOG\.md$|fastlane/metadata/android/[^/]+/changelogs/[0-9]+\.txt$' || true)
[ -z "$dirty" ] || { echo "Working tree not clean (besides changelogs) — commit or stash first."; exit 1; }
[ "$(git rev-parse --abbrev-ref HEAD)" = master ] || { echo "Not on master."; exit 1; }
git rev-parse "$tag" >/dev/null 2>&1 && { echo "Tag $tag already exists."; exit 1; }
[ -f keystore.properties ] || { echo "keystore.properties missing — release would be unsigned."; exit 1; }

# versionCode must increase for every release Android should treat as an upgrade; just +1.
old_code=$(grep -oP 'versionCode = \K\d+' "$gradle")
new_code=$((old_code + 1))

echo "Releasing $tag  (versionCode $old_code → $new_code)"
echo "Finalize the '## $tag' section in $changelog now — it becomes the release notes,"
echo "and the Norwegian F-Droid 'what's new' is generated from it."
echo "Write fastlane/metadata/android/en-US/changelogs/$new_code.txt for the English one."
echo "Nothing below the next prompt is reversible without force-pushing."
read -rp "Press Enter to finalize (tag, build, push, publish) — Ctrl-C to abort " _

# Pull this version's notes out of the changelog (lines under '## vX' up to the next '## ').
notes_body=$(awk -v h="## $tag" '$0 ~ "^"h {g=1; next} g && /^## / {exit} g' "$changelog")
[ -n "$notes_body" ] || { echo "No '## $tag' section in $changelog — aborting."; exit 1; }

# Mirror the Norwegian notes into F-Droid's per-version changelog (shown inline as the
# version's "what's new"). Skip if a file already exists, so a hand-tuned entry — e.g. one
# omitting an installer-only note — wins. De-wrap soft-wrapped bullets back to one line each.
fdroid_nb="fastlane/metadata/android/nb-NO/changelogs/$new_code.txt"
if [ ! -f "$fdroid_nb" ]; then
    printf '%s\n' "$notes_body" | awk '
        /^- / { if (line != "") print line; line = $0; next }
        /^[[:space:]]*$/ { next }
        { sub(/^[[:space:]]+/, " "); line = line $0 }
        END { if (line != "") print line }
    ' > "$fdroid_nb"
fi
# English is maintained by hand (Norway-only app — non-nb users are rare). Warn, don't block.
[ -f "fastlane/metadata/android/en-US/changelogs/$new_code.txt" ] || \
    echo "Note: en-US changelogs/$new_code.txt missing — non-Norwegian users fall back to the Changelog link."
git add fastlane/metadata/android/*/changelogs/ 2>/dev/null || true

sed -i "s/versionCode = .*/versionCode = $new_code/" "$gradle"
sed -i "s/versionName = .*/versionName = \"$version\"/" "$gradle"
git commit -aqm "Release $tag"
git tag "$tag"

# Build AFTER tagging so `git describe` bakes the clean tag into the in-app version string.
# This release pipeline cuts the Norway app (io.github.mortenfyhn.feltbok); the Sweden flavor
# is released separately if/when it goes public.
./gradlew assembleNorwayRelease --console=plain
apk="feltbok-$tag.apk"   # a recognisable name, not Gradle's app-norway-release.apk
cp app/build/outputs/apk/norway/release/app-norway-release.apk "$apk"

# Release notes = this version's changelog, then the install steps below it.
notes=$(mktemp)
{ printf '## Nytt i %s\n%s\n\n' "$tag" "$notes_body"; cat docs/release-notes-install.md; } > "$notes"

git push origin master "$tag"
gh release create "$tag" "$apk" \
    --title "Feltbok $tag" \
    --notes-file "$notes"

rm -f "$apk" "$notes"
echo "Released $tag → https://github.com/mortenfyhn/feltbok/releases/tag/$tag"
