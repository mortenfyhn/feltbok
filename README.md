[![Build Status](https://fyhn.semaphoreci.com/badges/feltbok/branches/master.svg?key=ebed8946-d053-4da8-ba73-36874709de64)](https://fyhn.semaphoreci.com/projects/feltbok)

# Feltbok (beta)

En Android-app for å kjapt taste inn fugleobservasjoner i felt. Deretter kan de enkelt lastes opp til artsobservasjoner.no. Fungerer uten innlogging og uten nett.

https://github.com/user-attachments/assets/0a44913d-b87a-47dd-a254-30bc7e5fab57

## Egenskaper

- Kjapp inntasting av observasjoner i felt
- Enkel eksport til Artsobservasjoner
- Velg lokalitet i kart
- Hent private lokaliteter fra Artsobservasjoner
- Lag nye private lokaliteter i kartet
- Kan brukes uten nett

## Installer

[<img src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png" alt="Få den på F-Droid" height="80">](https://f-droid.org/packages/io.github.mortenfyhn.feltbok/)

Eller gå til [siste utgave](https://github.com/mortenfyhn/feltbok/releases/latest) på GitHub.

## Utvikling

Behøver Android SDK (`ANDROID_HOME`) og [`just`](https://github.com/casey/just).

```sh
just run     # bygg, installer og kjør
```

Kjør `just` alene for å ramse opp alle oppskriftene (bygg, test, installer, etc).
