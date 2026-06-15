# R8 rules for the *instrumented test* APK only (androidTest), wired via testProguardFiles in
# build.gradle.kts. Kept separate from proguard-rules.pro so this never touches the production
# keep rules.
#
# Because testBuildType = releaseTest (a minified build type), AGP also minifies the TEST APK.
# That gives us nothing: the R8 coverage we care about runs on the *app* APK (a separate R8 task,
# minifyReleaseTestWithR8), which stays fully minified. Shrinking the test APK only strips its
# reflectively-loaded infrastructure — the AndroidJUnitRunner (named in the manifest) and its
# transitive deps like androidx.tracing — crashing instrumentation on launch. So turn shrinking
# off for the test APK only; R8 stays in the pipeline as a pass-through.
-dontshrink
-dontoptimize
-dontobfuscate

# errorprone's compile-only annotations are referenced by androidx.test but absent at runtime;
# harmless to omit, so silence the warnings R8 still emits with shrinking off.
-dontwarn com.google.errorprone.annotations.**
