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
// "v0.1-3-gabc-dirty"; with no tag yet, the short hash.
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

android {
    namespace = "io.github.mortenfyhn.feltbok"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.github.mortenfyhn.feltbok"
        minSdk = 26
        targetSdk = 34
        versionCode = 12
        versionName = "0.12"
        buildConfigField("String", "GIT_VERSION", "\"$gitVersion\"")
        // BuildConfig.DEBUG tracks the `debuggable` flag, which the debug build type turns OFF for
        // speed - so it's false even in the dev build. Use this DEV flag for dev-only code instead.
        buildConfigField("boolean", "DEV", "false")
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
    }

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

// app_name combines the flavor base ("Feltbok"/"Feltbok SE") with a build-type marker
// ("(dev)"/"(beta)") so all four installs are tellable apart on-device. Done via the variant API
// because a resValue set per-flavor and per-build-type would override rather than combine.
androidComponents {
    onVariants { variant ->
        val base = if (variant.flavorName == "sweden") "Fältbok" else "Feltbok"
        val marker = if (variant.buildType == "debug") " (dev)" else " (beta)"
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
}

// Stream test stdout to the console so the (opt-in) SearchBenchmark scoreboard table is readable
// in the terminal, not just the HTML report. Normal tests are silent, so this adds no noise.
tasks.withType<Test> {
    testLogging.showStandardStreams = true
}
