#!/usr/bin/env bash
# Renders the iOS app icon from the Android launcher artwork (scripts/dev/store-assets/art.svg) on the
# brand green. iOS masks the corners itself, so it is a full-bleed square, framed like the store
# listing icon - the whole 108x108 canvas, trimmed slightly since iOS adds no padding of its own.
# Needs rsvg-convert (brew install librsvg).
set -euo pipefail
cd "$(dirname "$0")"
ART="$(cat ../scripts/dev/store-assets/art.svg)"
svg=$(mktemp)
cat > "$svg" <<SVG
<svg xmlns="http://www.w3.org/2000/svg" width="1024" height="1024" viewBox="4 4 100 100">
  <rect x="0" y="0" width="108" height="108" fill="#5B7A2B"/>
  $ART
</svg>
SVG
rsvg-convert -w 1024 -h 1024 --background-color="#5B7A2B" "$svg" -o Feltbok/Assets.xcassets/AppIcon.appiconset/icon-1024.png
rm "$svg"
