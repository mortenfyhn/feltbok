# Feltbok

A small Android app for jotting down bird observations in the field and exporting
them for **[Artsobservasjoner](https://www.artsobservasjoner.no)** — Norway's species
reporting site. Structured, dropdown-driven entry (like iGoTerra, greatly simplified),
GPS-nearest official locality, and a copy-paste export. **No login, no account, no
network needed in the field** — so it can be shared with friends as-is.

**Installing the released app:** see [docs/install.md](docs/install.md).

## How it works

1. **Capture in the field.** Tap ＋, search the species, fill the Artsobservasjoner
   fields (antall, alder, kjønn, aktivitet, comments). Date/time are automatic. The
   nearest official locality is pre-filled from GPS; tap to pick another by distance.
2. **Export at home.** Hit *Eksporter* to copy a tab-separated block and paste it into
   Artsobservasjoner's *Importer observasjoner* page. Locality is matched by its full
   qualified name (no coordinates) — see [docs/artsobs-import.md](docs/artsobs-import.md)
   for the reverse-engineered import behaviour.

## Layout

```
app/                      Android app (Kotlin, Jetpack Compose)
  src/main/java/com/feltbok/
    Model.kt              data classes, option lists, CSV loading, TSV export
    MainViewModel.kt      screen state + navigation (no nav library)
    MainActivity.kt       theme + GPS lifecycle + permissions
    Ui.kt                 the four screens (list, search, detail, locality)
  src/main/assets/
    localities.csv        bundled official locality table
    species.csv           bundled Norwegian bird checklist
process/                  build-time data tools (Python)
  build_localities.py     build the locality table from the GBIF dump / API
  build_species.py        build the Norwegian bird checklist
docs/                     templates, import notes, UI mockup
```

The app reads `localities.csv` / `species.csv` from its assets, or — if present —
from the device's external files dir, so the data can be refreshed without rebuilding
(`just push-data`).

## Build & run

Needs the Android SDK (`ANDROID_HOME`) and a connected device/emulator.

```sh
just build      # assemble debug APK
just install    # build + install on the connected device
just run        # build + install + launch
just log        # tail app logs
```

## Rebuilding the data

```sh
# Locality table for a region (reliable GBIF API mode; needs a bbox):
just localities --api --bbox 8.0,63.5,9.3,63.9
# Norwegian bird checklist (Bokmål names from Artsnavnebasen):
just species
# Push freshly built CSVs to the device without a rebuild:
just push-data
```

`build_localities.py` reconstructs the locality registry from the GBIF export of the
Norwegian Species Observation Service and filters it down to public localities with
heuristics (distinct observers, name-collision/route detection) — see the module
docstring and [docs/artsobs-import.md](docs/artsobs-import.md). The authoritative
public/private flag lives only in the Artsobservasjoner API and is not yet used.

## History

Earlier the project tried a voice-capture + laptop-transcription pipeline; that
approach is preserved at the git tag `voice-approach`.
