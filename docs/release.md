# Releasing Feltbok

Releases are **signed APKs published as GitHub releases**, built by Semaphore on a `v*` tag.
Friends install the APK by sideloading it (enable "install unknown apps" for their browser).
The bundled APK ships **public localities only** - the maintainer's own customs
(`my-localities.csv`) are gitignored and pushed to the dev phone separately.

## Cut a release

```sh
git tag v0.2 && git push origin v0.2
```

Semaphore's `Release` block (`.semaphore/semaphore.yml`) then builds the signed release APK
and runs `gh release create`. The in-app version string comes from `git describe`, so it
shows `0.2` at the tag.

`versionCode` in `app/build.gradle.kts` must increase for each release Android should treat
as an upgrade - bump it alongside the tag.

## Signing

The release is signed with `feltbok-release.keystore` (RSA, alias `feltbok`). The keystore and
its `keystore.properties` are **gitignored - never commit them** (public repo). `build.gradle.kts`
reads the keystore from `keystore.properties` locally, or from env vars in CI; with neither, the
release builds **unsigned** (so a plain checkout still compiles).

> **Back up `feltbok-release.keystore` and its password.** Losing the key means you can no
> longer ship updates that install over an existing install - everyone would have to uninstall
> first.

Build one locally:

```sh
./gradlew assembleRelease   # -> app/build/outputs/apk/release/app-release.apk
```

## Semaphore setup (one-time)

1. Add the project at https://semaphoreci.com (connect the `feltbok` GitHub repo).
2. Create the `feltbok-signing` secret with the keystore file + passwords + a GitHub token
   (a classic PAT with `repo` scope, or a fine-grained token with Contents: read/write), using
   the values from `keystore.properties`:

   ```sh
   sem create secret feltbok-signing \
     -f feltbok-release.keystore:/home/semaphore/feltbok-release.keystore \
     -e KEYSTORE_PASSWORD=<storePassword from keystore.properties> \
     -e KEY_ALIAS=feltbok \
     -e KEY_PASSWORD=<keyPassword from keystore.properties> \
     -e GITHUB_TOKEN=<a GitHub token with repo/contents write>
   ```

The pipeline also runs `./gradlew test assembleDebug` on every push.
