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
        }
    }
}
