# For the agent on the Mac: verify the iOS export

Remove this file before merging. (Drop the commit that adds it.)

Branch `t3/artsobs-api-upload-prototype` adds a direct export to Artsobservasjoner (#149): the
shared export screen POSTs each note to `/core/Sightings`. The iOS side is written but was never
compiled: `IosPlatform.send` (GET and POST through `NSMutableURLRequest`) in
`ui/src/iosMain/kotlin/io/github/mortenfyhn/feltbok/IosPlatform.kt`.

Read `AGENTS.md` first. Then:

1. `iosApp/ci-test.sh`. Fix any compile error in `IosPlatform.send` (likely spots: `setHTTPMethod`,
   `setHTTPBody`, `NSString.create(string = …).dataUsingEncoding(…)` and their imports). Also run
   `./gradlew detektAll`.
2. Install on the connected iPhone with `iosApp/install-device.sh`. Ask the maintainer before you
   install, as AGENTS.md says for phones.
3. With the maintainer: log in from the export screen ("Logg inn"), mark 1-2 notes, export them.
   Expect the bar to fill and "✓ 2 av 2 eksportert". Open «Kontroller funn» and check the notes
   are there, then delete them on the site.
4. Turn on flight mode halfway through an export of several notes: the bar stops, the reason shows
   under it, and "Eksporter resten (N)" sends the rest once you're back online.
5. With the largest text size, the export button wraps into two balanced, centred lines.

Commit fixes as separate commits on this branch with English messages explaining why, no trailers,
and push the branch. Don't merge to master. Report what you changed and what you saw in steps 3-5.
