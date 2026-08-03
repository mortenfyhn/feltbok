# Play Store listing assets

Uploaded by hand in Play Console (#74). These exist **separately from
`fastlane/metadata/android/`** because Play's asset rules differ from F-Droid's, and
satisfying Play in the fastlane tree would degrade the F-Droid listing. The store *text*
is still shared — Play reuses the fastlane `title`/`short_description`/`full_description`
as-is (they fit Play's 30/80/4000 limits).

Regenerate with the snippet at the bottom if the source assets change.

| File | Why it differs from the fastlane original |
|---|---|
| `icon-512.png` | The fastlane `icon.png` is a pre-rounded square with transparent corners. Play applies its own 30% corner radius (mandatory from 31 Mar 2026) and wants an opaque square, so the corners are filled with the icon's own green (`#5B7A2B`). Uploading the transparent one double-rounds or renders black corners. |
| `feature-graphic-1024x500.png` | Unchanged — the fastlane one is already exactly 1024x500. Play has no equivalent on F-Droid, so it only matters here. |

**Screenshots are shared, not duplicated here.** They live only in
`fastlane/metadata/android/en-US/images/phoneScreenshots/` and are uploaded to Play from
there. Play requires a ratio within 16:9–9:16, so they're captured at exactly 1080x1920
rather than the phone's native 1080x2400 (9:20, which Play rejects). Get that by
overriding the display before capturing:

```sh
adb shell wm size 1080x1920
# capture on the phone as usual, then
adb shell wm size reset
```

Note the override makes the platform draw its own status bar colour (`#757575`) instead of
the app's near-white one, so shots taken this way have a grey status bar. Cosmetic only.

```python
# from the repo root, with Pillow available
import glob, os
from PIL import Image
src = "fastlane/metadata/android/en-US/images"
ic = Image.open(f"{src}/icon.png").convert("RGBA")
green = ic.getpixel((256, 8))[:3]
flat = Image.new("RGB", ic.size, green); flat.paste(ic, (0, 0), ic)
flat.save("docs/play/icon-512.png")
Image.open(f"{src}/featureGraphic.png").convert("RGB").save("docs/play/feature-graphic-1024x500.png")
for p in sorted(glob.glob(f"{src}/phoneScreenshots/*.jpg")):
    im = Image.open(p).convert("RGB"); w, h = im.size
    nw = round(h * 9 / 16)
    c = Image.new("RGB", (nw, h), (0xEE, 0xF1, 0xF0)); c.paste(im, ((nw - w) // 2, 0))
    c.save(f"docs/play/screenshot-{os.path.basename(p).split('.')[0]}-1350x2400.jpg",
           quality=92, optimize=True)
```
