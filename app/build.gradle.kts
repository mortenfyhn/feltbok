import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Build version shown in-app, straight from git. At a tag it's "v0.1"; between tags
// "v0.1-3-gabc-dirty"; with no tag yet, the short hash.
val gitVersion: String = try {
    ProcessBuilder("git", "describe", "--always", "--dirty")
        .start().inputStream.bufferedReader().readText().trim().ifEmpty { "dev" }
} catch (e: Exception) { "dev" }

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
        versionCode = 3
        versionName = "0.3"
        buildConfigField("String", "GIT_VERSION", "\"$gitVersion\"")
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
