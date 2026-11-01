package io.github.mortenfyhn.feltbok

/** Build facts the platform app knows (Android: its generated BuildConfig) and the shared UI
 *  needs. Set once at startup, before any screen or view model reads them. */
object AppInfo {
    var gitVersion = "dev" // the in-app version string, e.g. "v1.3-12-gabc123"
    var versionName = "dev"
    var versionCode = 0 // names the live-locality file, so a release drops the old one
    var dev = false // the dev build (dev-only diagnostics)
}
