[![Build Status](https://fyhn.semaphoreci.com/badges/feltbok/branches/master.svg?key=ebed8946-d053-4da8-ba73-36874709de64)](https://fyhn.semaphoreci.com/projects/feltbok)

# Feltbok beta

En app for å kvikt taste inn fugleobservasjoner i felt. Deretter kan du enkelt lastes opp til artsobservasjoner.no. Behøver ingen innlogging og ingen nettilgang.

https://github.com/user-attachments/assets/463080c3-b6be-46ea-bfd6-91535ed26116

## Installer

Last ned `.apk`-fila fra [siste utgave](https://github.com/mortenfyhn/feltbok/releases/latest)
og åpne den på telefonen. Android viser en Play Protect-advarsel siden appen ikke kommer fra
Google Play – det er normalt. [Full veiledning med skjermbilder](docs/install.md).

## Bygg

Behøver Android SDK (`ANDROID_HOME`) og [`just`](https://github.com/casey/just).

```sh
just run     # bygg, installer, og kjør
```

Kjør `just` alene for å ramse opp alle oppskriftene (bygg, test, installer, etc).
