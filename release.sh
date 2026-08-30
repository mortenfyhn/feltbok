#!/usr/bin/env bash
# Cut a signed Feltbok release locally — replaces the old (slow) Semaphore Release job.
# Validates, runs the pre-flight gates, then builds + signs the APK, tags, and — only after
# a final yes — pushes and publishes the GitHub release with the changelog notes.
#
#   ./release.sh 0.8
#
# Everything up to the push is local and undoable, so this doubles as a rehearsal: Ctrl-C at
# the "build & tag" pause leaves the tree untouched, and answering no at the push prompt
# leaves a local tag the script tells you how to drop.
#
# Needs keystore.properties + the keystore present (see docs/release.md) and gh
# authenticated. Run from master's tip (a drafted CHANGELOG.md entry is fine).
set -euo pipefail

# Formatting
bold()  { printf '\e[1m%s\e[0m\n' "$*"; }

tag="${1:?usage: ./release.sh <tag>   e.g. v0.8}"
# versionName is the tag without the leading v, e.g. v1.2 -> 1.2
version="${tag#v}"
gradle="app/build.gradle.kts"
changelog="CHANGELOG.md"

if [ -t 1 ]; then green=$'\e[1;32m'; reset=$'\e[0m'; else green=; reset=; fi

# ---- Validation: everything cheap runs before the device gates, so a missing changelog
# ---- section costs a second, not a full instrumented test run.

# Drafted changelog entries are the one thing allowed to be dirty going in — CHANGELOG.md
# plus the per-version fastlane "what's new" files (the English one is hand-written; see
# below). Everything else must be committed so the Release commit is just the bump + notes.
dirty=$(git status --porcelain | grep -vE ' CHANGELOG\.md$|fastlane/metadata/android/[^/]+/changelogs/[0-9]+\.txt$' || true)
[ -z "$dirty" ] || { echo "Working tree not clean (besides changelogs) — commit or stash first."; exit 1; }

# Releases are cut from master's tip so that what you soaked is exactly what ships. Work that
# isn't ready to go out belongs on a feature branch until after the release, not on master.
head_sha=$(git rev-parse --short HEAD)
if [ "$(git rev-parse HEAD)" != "$(git rev-parse master 2>/dev/null || echo none)" ]; then
    behind=$(git rev-list --count HEAD..master)
    ahead=$(git rev-list --count master..HEAD)
    echo "HEAD ($head_sha) isn't master's tip — $behind behind it, $ahead ahead."
    echo "Releases are cut from master's tip, so what you soaked is what ships."
    if [ "$ahead" = 0 ]; then
        echo "Either soak master's tip, or move the unsoaked commits onto a branch:"
        echo "  git branch wip master && git branch -f master $head_sha"
    else
        echo "Merge this work into master and soak that, or release master's tip instead."
    fi
    exit 1
fi
# Being on master's tip isn't enough if the remote has moved: the push at the end would fail
# after everything is tagged and built.
if git rev-parse --verify -q origin/master >/dev/null; then
    behind_remote=$(git rev-list --count master..origin/master)
    [ "$behind_remote" = 0 ] || { echo "master is $behind_remote behind origin/master — git pull first."; exit 1; }
fi

git rev-parse "$tag" >/dev/null 2>&1 && { echo "Tag $tag already exists."; exit 1; }
[ -f keystore.properties ] || { echo "keystore.properties missing — release would be unsigned."; exit 1; }

# versionCode must increase for every release Android should treat as an upgrade; just +1.
# Computed here (not later) because the fastlane filename below is named after it.
old_code=$(grep -oP 'versionCode = \K\d+' "$gradle")
new_code=$((old_code + 1))

bold "Releasing $tag"
echo "Version code $old_code -> $new_code"
echo

# The release notes live in the changelog and F-Droid's per-version "what's new". Rather than
# aborting and making you re-run, ask for one file at a time and wait while you write it.
# This version's notes = lines under '## vX' up to the next '## '. A drafted section left
# uncommitted is fine; the Release commit further down picks it up.
notes() { awk -v h="## $tag" '$0 ~ "^"h {g=1; next} g && /^## / {exit} g' "$changelog"; }
# Slot the heading in under '## Neste utgivelse', so its accumulated bullets end up in this
# release and the standing section is left empty for the next one.
if [ -z "$(notes)" ]; then
    # Norwegian date to match the existing headings, e.g. '## v1.1 (28. juli 2026)'.
    today=$(LC_TIME=nb_NO.UTF-8 date +"%-d. %B %Y")
    sed -i "0,/^## Neste utgivelse$/s//&\n\n## $tag ($today)/" "$changelog"
fi
# Bullets pile up as changes land, so they read like a commit log until someone tightens them.
echo "-> Update $changelog"
read -rp "   Hit enter when done " _
while [ -z "$(notes)" ]; do
    read -rp "-> No '## $tag' section in $changelog. Add one, then hit enter " _
done

# F-Droid shows these per-version files inline as the version's "what's new". They're plain text,
# so de-wrap soft-wrapped bullets back to one line each and swap the Markdown "- " for a real "• "
# (a hyphen would just render as-is).
fdroid_text() {
    awk '
        /^- / { if (line != "") print line; line = $0; sub(/^- /, "• ", line); next }
        /^[[:space:]]*$/ { next }
        { sub(/^[[:space:]]+/, " "); line = line $0 }
        END { if (line != "") print line }
    '
}

# Both per-version files come from the same CHANGELOG.md section, and are written together so
# the English one is visibly a translation of a Norwegian file that already exists on disk.
# The English one starts as a copy of the Norwegian, so the job is translating it in place
# rather than retyping the bullets. An existing file wins — a hand-tuned entry stays hand-tuned.
fdroid_nb="fastlane/metadata/android/nb-NO/changelogs/$new_code.txt"
fdroid_en="fastlane/metadata/android/en-US/changelogs/$new_code.txt"
for f in "$fdroid_nb" "$fdroid_en"; do
    mkdir -p "$(dirname "$f")"
    [ -f "$f" ] || notes | fdroid_text > "$f"
done
echo "-> Translate $fdroid_en to English"
read -rp "   Hit enter when done " _

# ---- Pre-flight gates: both need a device or a human, so they run once validation passed.

# Run the instrumented R8 smoke tests BEFORE anything is tagged/built/pushed, so a release-only
# R8 strip (e.g. the osmdroid map) fails here instead of in users' hands — and a spurious failure
# (locked screen) costs nothing this early. Releasing without them is allowed but must be a
# deliberate choice: plug a phone in and retry, or type skip.
attached() { [ -n "$(adb devices | sed '1d' | grep -w device || true)" ]; }
while ! attached; do
    read -rp "No device for the R8 smoke tests. Plug one in + Enter to retry, or type 'skip' to release without them: " ans
    [ "$ans" = skip ] && { echo "Skipping R8 smoke tests for $tag."; break; }
done
attached && just itest

# Same deal for the seed→export→paste-import check (docs/pre-release-checklist.md): it's the only
# end-to-end test of the export format against the real site — automation stops at "TSV renders" —
# but the site can't be scripted, so the script sets the app up and you do the paste by hand.
if attached; then
    echo
    read -rp "Set the export test up now — install + seed + launch the dev app (overwrites its notes)? (yes/no): " ans
    if [ "$ans" = yes ]; then
        just install
        just seed   # force-stops the app; the seed is imported on the next launch
        just run
        echo "Now export from the app and paste into Artsobservasjoner \"Importer observasjoner\"."
    fi
fi
# Require a typed answer (not a stray Enter) so releasing without the check is as deliberate as
# skipping the R8 tests above.
while true; do
    read -rp "Has the seed→export→paste-import test passed on the live site? (yes / 'skip' to release without it): " ans
    [ "$ans" = yes ] && break
    [ "$ans" = skip ] && { echo "Skipping the paste-import check for $tag."; break; }
done

# Re-read rather than reuse what the review step saw: the device gates above take long enough
# that you may well have kept editing. What follows is all local (bump, commit, tag, build) —
# the push has its own prompt at the end.
notes_body=$(notes)
echo
read -rp "-> Hit enter to build + tag $tag locally, or Ctrl-C to abort " _

# ---- Local: notes, bump, commit, tag, build. Undoable up to the push prompt.

git add fastlane/metadata/android/*/changelogs/ 2>/dev/null || true

sed -i "s/versionCode = .*/versionCode = $new_code/" "$gradle"
sed -i "s/versionName = .*/versionName = \"$version\"/" "$gradle"
git commit -aqm "Release $tag"
git tag "$tag"

# Build AFTER tagging so `git describe` bakes the clean tag into the in-app version string.
# Both flavors ship in the one release: Norway (io.github.mortenfyhn.feltbok) and Sweden
# (io.github.mortenfyhn.feltbok.se). F-Droid is unaffected — it builds each applicationId from
# source on its own servers and never redistributes these APKs; the attachments are just for
# sideloading.
./gradlew assembleNorwayRelease assembleSwedenRelease --console=plain
# Recognisable names, not Gradle's app-<flavor>-release.apk. The -se suffix mirrors the .se appId.
apk="feltbok-$tag.apk"
apk_se="feltbok-se-$tag.apk"
cp app/build/outputs/apk/norway/release/app-norway-release.apk "$apk"
cp app/build/outputs/apk/sweden/release/app-sweden-release.apk "$apk_se"

# ---- The only outward-facing step, behind its own gate so a rehearsal stops here.

echo
echo "Built $apk + $apk_se, tagged $tag locally."
read -rp "Push and publish the GitHub release? (yes / anything else keeps it local): " ans
if [ "$ans" != yes ]; then
    cat <<EOF
Not pushed. $tag exists locally only, and the APKs are kept for sideloading. To undo:
  git tag -d $tag
  git reset HEAD~1     # mixed, so your CHANGELOG edits stay in the working tree
EOF
    exit 0
fi

# Release notes = this version's changelog, then the install steps below it.
notes=$(mktemp)
{ printf '## Nytt i %s\n%s\n\n' "$tag" "$notes_body"; cat docs/release-notes-install.md; } > "$notes"

git push origin master "$tag"
gh release create "$tag" "$apk" "$apk_se" \
    --title "Feltbok $tag" \
    --notes-file "$notes"

rm -f "$apk" "$apk_se" "$notes"
printf '\n  %s🎉  Feltbok %s er ute!  🎉%s\n' "$green" "$tag" "$reset"
printf '  https://github.com/mortenfyhn/feltbok/releases/tag/%s\n\n' "$tag"
