plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Build version shown in-app, straight from git (e.g. "5240840" or "v0.1-3-gabc-dirty").
val gitVersion: String = try {
    ProcessBuilder("git", "describe", "--always", "--dirty")
        .start().inputStream.bufferedReader().readText().trim().ifEmpty { "dev" }
} catch (e: Exception) { "dev" }

android {
    namespace = "com.appobs"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.appobs"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
        buildConfigField("String", "GIT_VERSION", "\"$gitVersion\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
