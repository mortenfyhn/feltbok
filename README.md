[![Build Status](https://fyhn.semaphoreci.com/badges/feltbok/branches/master.svg?key=ebed8946-d053-4da8-ba73-36874709de64)](https://fyhn.semaphoreci.com/projects/feltbok)

# Feltbok

A small Android app for entering bird observations in the field and exporting them for
**[Artsobservasjoner](https://www.artsobservasjoner.no)**. Entry uses the same fields as
an Artsobs sighting (antall, alder, kjønn, aktivitet, kommentar), the locality is matched
against the official site list by GPS distance, and *Eksporter* produces a tab-separated
block you paste into the *Importer observasjoner* page. No login, no account, and no
network needed in the field.



https://github.com/user-attachments/assets/463080c3-b6be-46ea-bfd6-91535ed26116



To install, download the latest `.apk` from
[Releases](https://github.com/mortenfyhn/feltbok/releases/latest).

## Build

Needs the Android SDK (`ANDROID_HOME`), [`just`](https://github.com/casey/just), and a
connected device or emulator.

```sh
just run     # build, install, and launch
```

Run `just` on its own to list every recipe (build, test, install, logs, and the
data-build pipeline).
