# R8 rules added to the *app* APK for the releaseTest variant ONLY (wired via proguardFiles in
# build.gradle.kts, after initWith(release)). NOT applied to the real release build, so its
# shrinking is byte-for-byte unchanged.
#
# Why this exists: instrumented tests load the app APK and the test APK in ONE process. Shared
# runtime libraries (the Kotlin stdlib, androidx) are packaged only in the app APK; the test APK's
# androidx.test classes were compiled against the full, un-renamed library API and resolve against
# whatever the app APK provides. But the app's R8 shrinks/renames those libraries (the app uses
# only a slice of them), so the runner crashes at startup with NoClassDefFoundError on classes like
# kotlin.LazyKt or androidx.tracing.Trace. Keeping the shared libraries intact for this variant
# fixes that wholesale.
#
# This does NOT weaken what the test guards: the app's own code (io.github.mortenfyhn.feltbok.**) and osmdroid still
# get the real release R8 treatment, and those — not the Kotlin/androidx libraries — are where the
# reflection-strip regressions we care about (#113/#117) actually occur.
-keep class kotlin.** { *; }
-keep class kotlinx.** { *; }
-keep class androidx.** { *; }
-dontwarn kotlin.**
-dontwarn kotlinx.**
-dontwarn androidx.**

# App symbols the instrumented tests call BY NAME from the separately-compiled test APK. R8
# obfuscates app code (Note -> e.h0, Model.kt's top-level fns -> e.g0), so the test can't resolve
# them unless we keep their names here. This does NOT weaken the R8 strip-check we care about:
# these are statically reachable from the app, so they're never the classes R8 strips — only the
# reflective ones (osmdroid) are, and the map module stays fully R8'd (the map test inlines its
# own osmdroid setup rather than calling into io.github.mortenfyhn.feltbok, precisely so MapPicker keeps no keep).
-keep class io.github.mortenfyhn.feltbok.Note { *; }
-keep class io.github.mortenfyhn.feltbok.Locality { *; }
-keep class io.github.mortenfyhn.feltbok.Species { *; }
-keep class io.github.mortenfyhn.feltbok.ModelKt { *; }

# The Compose UI-flow test (AddObservationFlowTest) hosts App(vm) and drives it by name: the App
# composable (UiKt), the MainViewModel it takes (whose Compose-generated $stable field the test's
# call site reads — without the keep, R8 drops it and setContent throws NoSuchFieldError), and the
# Strings it asserts on. All statically reachable from the app, so never R8-strip candidates anyway.
-keep class io.github.mortenfyhn.feltbok.MainActivityKt { *; }
-keep class io.github.mortenfyhn.feltbok.MainViewModel { *; }
-keep class io.github.mortenfyhn.feltbok.Strings { *; }
-keep class io.github.mortenfyhn.feltbok.Strings$* { *; }
