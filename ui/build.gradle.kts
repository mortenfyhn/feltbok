// The app's Compose UI, shared with the coming iOS app (#115) through Compose Multiplatform. The
// logic it drives lives in :shared, which stays Compose-free. iOS targets only on macOS hosts, as in
// :shared, so Linux builds (CI, F-Droid) never need Kotlin/Native.
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jlleitschuh.gradle.ktlint")
}

// Compose Multiplatform 1.11 (material3 1.9) maps to the androidx Compose 1.11 (material3 1.4) the
// app's BOM pins, so Android keeps the same Compose underneath.
val composeVersion = "1.11.1"

kotlin {
    androidLibrary {
        namespace = "io.github.mortenfyhn.feltbok.ui"
        compileSdk = 36
        minSdk = 26
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
        withHostTestBuilder {}
    }
    if (System.getProperty("os.name").startsWith("Mac")) {
        iosArm64()
        iosSimulatorArm64()
    }
    compilerOptions { allWarningsAsErrors.set(true) }
    sourceSets {
        commonMain.dependencies {
            api(project(":shared"))
            implementation("org.jetbrains.compose.runtime:runtime:$composeVersion")
            implementation("org.jetbrains.compose.foundation:foundation:$composeVersion")
            implementation("org.jetbrains.compose.ui:ui:$composeVersion")
            implementation("org.jetbrains.compose.material3:material3:1.9.0")
            // Maps to androidx lifecycle 2.9.4 on Android, the app's own version.
            implementation("org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose:2.9.6")
        }
        androidMain.dependencies {
            implementation("androidx.activity:activity-compose:1.13.0")
            implementation("androidx.core:core-ktx:1.18.0")
            implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
            implementation("org.osmdroid:osmdroid-android:6.1.20")
        }
        getByName("androidHostTest").dependencies {
            implementation("junit:junit:4.13.2")
        }
    }
}

// `./gradlew test` (the repo's pre-PR check) runs every module's `test`; the KMP Android library
// names its unit tests testAndroidHostTest.
tasks.register("test") { dependsOn("testAndroidHostTest") }
