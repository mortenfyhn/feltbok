#!/usr/bin/env bash
# Compose + render Feltbok's store images (launcher/listing icon + F-Droid/Play feature graphic)
# from art.svg, writing the PNGs straight into the fastlane metadata tree. Beta-free — Norway is
# stable from v1.0. The wordmark is dropped below where F-Droid overlays the share/menu buttons on
# the banner. Regenerate after editing art.svg or the layout numbers below.
#
#   scripts/dev/store-assets/render.sh        (or: just render-store-assets)
#
# Needs rsvg-convert (librsvg) + imagemagick; Liberation Sans supplies the wordmark. The composed
# icon.svg/banner.svg are throwaway intermediates (gitignored); art.svg + this script are the source.
set -euo pipefail
cd "$(dirname "$0")"
OUT="$(cd ../../.. && pwd)/fastlane/metadata/android/en-US/images"

BG="#5B7A2B"        # Norway brand green (ic_launcher_background)
ART="$(cat art.svg)"

# 512x512 store icon: green bg + artwork, lightly rounded corners (transparent outside the radius).
cat > icon.svg <<SVG
<svg xmlns="http://www.w3.org/2000/svg" width="512" height="512" viewBox="0 0 108 108">
  <defs><clipPath id="round"><rect width="108" height="108" rx="16" ry="16"/></clipPath></defs>
  <g clip-path="url(#round)">
    <rect width="108" height="108" fill="$BG"/>
    $ART
  </g>
</svg>
SVG

# 1024x500 feature graphic: artwork on the left (scaled ~3.1x), "Feltbok" wordmark on the right.
# The wordmark sits at y=345 (baseline ~399) to clear F-Droid's top-overlaid back/share/menu
# buttons; the icon's translate.y is tuned so its lower edge lines up with the wordmark's.
cat > banner.svg <<SVG
<svg xmlns="http://www.w3.org/2000/svg" width="1024" height="500" viewBox="0 0 1024 500">
  <rect width="1024" height="500" fill="$BG"/>
  <g transform="translate(120,109) scale(3.10)">
    $ART
  </g>
  <text x="740" y="345" font-family="Liberation Sans, DejaVu Sans, sans-serif"
        font-weight="bold" font-size="150" fill="#FFFFFF"
        text-anchor="middle" dominant-baseline="central">Feltbok</text>
</svg>
SVG

rsvg-convert -w 512  -h 512 icon.svg   -o "$OUT/icon.png"
rsvg-convert -w 1024 -h 500 banner.svg -o "$OUT/featureGraphic.png"
echo "wrote $OUT/icon.png (512x512) and $OUT/featureGraphic.png (1024x500)"
