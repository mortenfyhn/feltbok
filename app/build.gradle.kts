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

// Release signing: keystore.properties (local, gitignored) or env vars (Semaphore CI).
// Absent in either -> release stays unsigned, so a plain checkout still builds.
val keystoreProps = rootProject.file("keystore.properties").takeIf { it.exists() }
    ?.let { Properties().apply { load(it.inputStream()) } }
fun signingValue(prop: String, env: String): String? =
    keystoreProps?.getProperty(prop) ?: System.getenv(env)
val signStore = signingValue("storeFile", "KEYSTORE_FILE")

android {
    namespace = "com.feltbok"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.feltbok"
        minSdk = 26
        targetSdk = 34
        versionCode = 8
        versionName = "0.8"
        buildConfigField("String", "GIT_VERSION", "\"$gitVersion\"")
        // app_name lives here (not strings.xml) so the debug build type can override it.
        resValue("string", "app_name", "Feltbok beta")
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
            isMinifyEnabled = false
            if (signStore != null) signingConfig = signingConfigs.getByName("release")
        }
        // Separate applicationId so a dev build installs alongside the release build I use daily,
        // rather than overwriting it. Distinct name + version suffix to tell them apart on-device.
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            resValue("string", "app_name", "Feltbok dev")
            // Mark the in-app version string too (footer + feedback), so a dev build is
            // recognisable from inside the app, not just by its launcher label.
            buildConfigField("String", "GIT_VERSION", "\"$gitVersion-dev\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
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
}
