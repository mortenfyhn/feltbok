# R8 keep rules for the release build (isMinifyEnabled). Most of the app is plain
# Compose/Kotlin that R8 shrinks safely; these rules cover the spots that resolve
# classes by reflection/name, which R8's static analysis can't see.

# osmdroid loads tile sources, the config provider, and SQLite tile cache classes
# reflectively, so tree-shaking them breaks the map at runtime (not at build time).
# Mirrors osmdroid's own consumer rules.
-keep class org.osmdroid.** { *; }
-keep interface org.osmdroid.** { *; }
-dontwarn org.osmdroid.**

# @JavascriptInterface methods (SyncScreen's WebView bridge) are kept by the default
# proguard-android-optimize.txt rule; no extra rule needed here.
