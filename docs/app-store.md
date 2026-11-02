# App Store listing

Entered by hand in App Store Connect. The **description is shared with F-Droid and Play**: there is
one text, `fastlane/metadata/android/nb-NO/full_description.txt`, so change it there and paste it
into every store. Only what App Store asks for and the others don't is written here. The listing is
Norwegian only, like the app (`iosApp/project.yml` declares it so).

## Text

| Field | Limit | Value |
|---|---|---|
| Name | 30 | Feltbok for fugleobservasjoner (plain "Feltbok" is taken) |
| Subtitle | 30 | Noter fugler kjapt i felt |
| Promotional text | 170 | `fastlane/metadata/android/nb-NO/short_description.txt` |
| Description | 4000 | `fastlane/metadata/android/nb-NO/full_description.txt` |
| Keywords | 100 | `fugl,fugler,fuglekikking,artsobservasjoner,artsobs,ornitologi,rødliste,artsdatabanken,notatbok` |
| Support URL | | https://github.com/mortenfyhn/feltbok |
| Privacy policy URL | | https://github.com/mortenfyhn/feltbok/blob/master/PRIVACY.md |
| Copyright | | 2026 Morten F. Amundsen |

Keywords skip the words already in the name, which App Store indexes anyway.

## Screenshots

`fastlane/screenshots/no/`, from `just screenshots-ios` (see `docs/release.md`). They're the 6.9"
size; App Store Connect scales them down for smaller iPhones.

## Questionnaires

- **App Privacy:** Data Not Collected. The developer receives nothing, and what goes to
  Artsobservasjoner and OpenStreetMap (`PRIVACY.md`) only answers the request at hand.
- **Category:** Reference (secondary: Education).
- **Age rating:** none of the content applies, so 4+.
- **Price:** free.

## Notes for the reviewer

> Feltbok is a field notebook for birders in Norway. No account is needed: tap + to add an
> observation (species, locality, count), and "Eksporter" copies the observations as text to paste
> into Artsobservasjoner.no, the Norwegian species-observation database, which the export screen
> opens. "Hent mine lokaliteter" shows Artsobservasjoner's own login page so users can fetch their
> private localities; the app never sees the password, and the rest of the app works without it.
> Location is only used to suggest the nearest locality. The app is in Norwegian only.
