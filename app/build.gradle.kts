import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import java.util.Properties
import javax.inject.Inject

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jlleitschuh.gradle.ktlint")
}

// Build version shown in-app, straight from git. At a tag it's "v0.1"; between tags
// "v0.1-3-gabc-dirty"; with no tag yet, the short hash. The "-dirty" suffix is kept only for the
// dev build: F-Droid's build server sanitizes tracked files before building (strips signing
// configs), so release builds there are always "dirty" through no fault of the source — and our
// own release.sh already refuses a non-clean tree.
// A ValueSource (not a config-time `git` call) so the configuration cache re-runs it each build
// and the version can't go stale when HEAD moves while the cache is otherwise reusable.
abstract class GitVersion : ValueSource<String, ValueSourceParameters.None> {
    @get:Inject abstract val exec: ExecOperations
    override fun obtain(): String = try {
        val out = ByteArrayOutputStream()
        exec.exec {
            commandLine("git", "describe", "--tags", "--always", "--dirty")
            standardOutput = out
            isIgnoreExitValue = true
        }
        out.toString().trim().ifEmpty { "dev" }
    } catch (e: Exception) { "dev" }
}
val gitVersion: String = providers.of(GitVersion::class) {}.get()
val gitVersionRelease: String = gitVersion.removeSuffix("-dirty")

// Branch name, shown only in the dev build so a working-branch build is identifiable on-device.
// Same ValueSource rationale as GitVersion: re-run each build so it tracks branch switches.
abstract class GitBranch : ValueSource<String, ValueSourceParameters.None> {
    @get:Inject abstract val exec: ExecOperations
    override fun obtain(): String = try {
        val out = ByteArrayOutputStream()
        exec.exec {
            commandLine("git", "rev-parse", "--abbrev-ref", "HEAD")
            standardOutput = out
            isIgnoreExitValue = true
        }
        out.toString().trim().ifEmpty { "?" }
    } catch (e: Exception) { "?" }
}
val gitBranch: String = providers.of(GitBranch::class) {}.get()

// Release signing: keystore.properties (local, gitignored) or env vars (Semaphore CI).
// Absent in either -> release stays unsigned, so a plain checkout still builds.
val keystoreProps = rootProject.file("keystore.properties").takeIf { it.exists() }
    ?.let { Properties().apply { load(it.inputStream()) } }
fun signingValue(prop: String, env: String): String? =
    keystoreProps?.getProperty(prop) ?: System.getenv(env)
val signStore = signingValue("storeFile", "KEYSTORE_FILE")

// The Sweden flavor's "my localities" sync needs the maintainer's Artportalen userId. Kept out of
// git (in local.properties, like the signing secrets) so it isn't published; absent -> "0", which
// just means the personal sync returns nothing useful on a fresh clone/CI. Not a secret per se, but
// it links to a profile and we'd rather not commit it.
val localProps = rootProject.file("local.properties").takeIf { it.exists() }
    ?.let { Properties().apply { load(it.inputStream()) } }
val seUserId = localProps?.getProperty("feltbok.se.userId") ?: System.getenv("SE_USER_ID") ?: "0"

android {
    namespace = "io.github.mortenfyhn.feltbok"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.mortenfyhn.feltbok"
        minSdk = 26
        targetSdk = 36
        versionCode = 15
        versionName = "1.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "GIT_VERSION", "\"$gitVersionRelease\"")
        // BuildConfig.DEBUG tracks the `debuggable` flag, which the debug build type turns OFF for
        // speed - so it's false even in the dev build. Use this DEV flag for dev-only code instead.
        buildConfigField("boolean", "DEV", "false")
        // Artportalen userId for the Sweden flavor's locality sync (from local.properties; see above).
        buildConfigField("String", "SE_USER_ID", "\"$seUserId\"")
    }

    // One flavor per country: a separate app per Artsobservasjoner-family site (issue #127), so
    // Swedish field notes never mix with Norwegian ones and each ships only its own data. Norway
    // keeps the original applicationId untouched (the maintainer's real app + field notes).
    flavorDimensions += "country"
    productFlavors {
        create("norway") {
            dimension = "country"
            // applicationId stays the defaultConfig value: io.github.mortenfyhn.feltbok
        }
        create("sweden") {
            dimension = "country"
            applicationId = "io.github.mortenfyhn.feltbok.se"
        }
    }

    signingConfigs {
        if (signStore != null) create("release") {
            storeFile = rootProject.file(signStore)
            storePassword = signingValue("storePassword", "KEYSTORE_PASSWORD")
            keyAlias = signingValue("keyAlias", "KEY_ALIAS")
            keyPassword = signingValue("keyPassword", "KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (signStore != null) signingConfig = signingConfigs.getByName("release")
        }
        // Separate applicationId so a dev build installs alongside the release build I use daily,
        // rather than overwriting it. Distinct name + version suffix to tell them apart on-device.
        debug {
            // Run at release speed: the debuggable flag makes ART skip optimizations and adds
            // instrumentation, which is the bulk of the dev build's runtime sluggishness. We drive
            // this build by hand (adb) rather than attaching a JVM/Compose debugger, so we don't
            // need it. Still a distinct .debug install alongside the daily release build.
            isDebuggable = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            // Append the branch to the in-app version string (footer + feedback) so a dev build is
            // recognisable from inside the app, not just by its launcher label. The release build
            // has no parenthetical, so the branch alone marks this as a dev build.
            buildConfigField("String", "GIT_VERSION", "\"$gitVersion ($gitBranch)\"")
            buildConfigField("boolean", "DEV", "true")
        }
        // Minified variant for the instrumented R8 smoke tests (src/androidTest). It inherits
        // release's R8 + resource shrinking so the tests run against tree-shaken code — catching
        // the reflection-strip class of bug that unit tests on a plain JVM can't see. But it
        // carries its own applicationId (.releasetest) so it can NEVER collide with the
        // maintainer's real install, and is debug-signed so it builds without the
        // release keystore (on CI or a fresh checkout). `testBuildType` below aims the
        // connected tests at it. Run via `just itest` (device-gated, not in CI).
        create("releaseTest") {
            initWith(getByName("release"))
            applicationIdSuffix = ".releasetest"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
            // App-APK rules for this variant only (NOT real release): keep the few shared-dep
            // classes the test runner reaches that the app's R8 would otherwise strip.
            proguardFiles("proguard-releasetest.pro")
            // The test APK is minified too (it instruments this variant); test-infra-only rules.
            testProguardFiles("proguard-test-rules.pro")
        }
    }
    // connectedAndroidTest targets this variant, not the default debug (which isn't minified).
    testBuildType = "releaseTest"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        allWarningsAsErrors = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

// app_name combines the flavor base ("Feltbok"/"Fältbok") with a marker so all four installs are
// tellable apart on-device. Dev builds are always "(dev)". Norway is stable from v1.0, so its
// release carries no marker; Sweden is still beta, so its release stays "(beta)". Done via the
// variant API because a resValue set per-flavor and per-build-type would override rather than combine.
androidComponents {
    onVariants { variant ->
        val base = if (variant.flavorName == "sweden") "Fältbok" else "Feltbok"
        val marker = when {
            variant.buildType == "debug" -> " (dev)"
            variant.flavorName == "sweden" -> " (beta)"
            else -> ""
        }
        variant.resValues.put(
            variant.makeResValueKey("string", "app_name"),
            com.android.build.api.variant.ResValue(base + marker),
        )
    }
}

dependencies {
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.osmdroid:osmdroid-android:6.1.20")
    testImplementation("junit:junit:4.13.2")
    // Real org.json on the unit-test classpath: android.jar's stub throws, so the Note
    // JSON round-trip test (noteToJson/noteFromJson) needs the actual implementation.
    testImplementation("org.json:json:20240303")
    // Instrumented R8 smoke tests (src/androidTest, run on a device against the minified
    // releaseTest variant). ext:junit pulls the runner + core transitively.
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    // The AndroidJUnitRunner lives here and is NOT pulled transitively by ext:junit/core; without
    // it the test APK has no runner class and instrumentation crashes on launch.
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1") // GrantPermissionRule
    // Compose UI-flow tests (src/androidTest): drive the real screens and assert on the tree. The
    // BOM versions ui-test-junit4 to match the app's Compose. ui-test-manifest supplies the empty
    // ComponentActivity that createComposeRule() hosts App(vm) in - it must land in the variant
    // under test (releaseTest, per testBuildType), not debug.
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    add("releaseTestImplementation", "androidx.compose.ui:ui-test-manifest")
}

// Stream test stdout to the console so the (opt-in) SearchBenchmark scoreboard table is readable
// in the terminal, not just the HTML report. Normal tests are silent, so this adds no noise.
tasks.withType<Test> {
    testLogging.showStandardStreams = true
}
