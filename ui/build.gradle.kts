// The app's Compose UI, shared with the coming iOS app (#115) through Compose Multiplatform. The
// logic it drives lives in :shared, which stays Compose-free. iOS targets only on macOS hosts, as in
// :shared, so Linux builds (CI, F-Droid) never need Kotlin/Native.
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.kotlin.plugin.compose")
    // Copies libraries' Compose resources (MapLibre's attribution strings) into the iOS app bundle.
    id("org.jetbrains.compose")
    id("org.jlleitschuh.gradle.ktlint")
}

// Compose Multiplatform 1.11 (material3 1.9) maps to the androidx Compose 1.11 (material3 1.4) the
// app's BOM pins, so Android keeps the same Compose underneath.
val composeVersion = "1.11.1"

// The in-app version string on iOS, from `git describe` like the Android build's BuildConfig.
val gitVersion = providers.exec {
    commandLine("git", "describe", "--tags", "--always", "--dirty")
    isIgnoreExitValue = true
}.standardOutput.asText.map { it.trim().ifEmpty { "dev" } }
val generateIosBuildInfo by tasks.registering {
    val out = layout.buildDirectory.dir("generated/iosBuildInfo")
    val version = gitVersion
    inputs.property("version", version)
    outputs.dir(out)
    doLast {
        val f = out.get().file("io/github/mortenfyhn/feltbok/IosBuildInfo.kt").asFile
        f.parentFile.mkdirs()
        f.writeText("package io.github.mortenfyhn.feltbok\n\ninternal const val GIT_VERSION = \"${version.get()}\"\n")
    }
}

kotlin {
    androidLibrary {
        namespace = "io.github.mortenfyhn.feltbok.ui"
        compileSdk = 36
        minSdk = 26
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
        withHostTestBuilder {}
    }
    if (System.getProperty("os.name").startsWith("Mac")) {
        // The iOS app (iosApp/) links this as a static framework.
        listOf(iosArm64(), iosSimulatorArm64()).forEach { it.binaries.framework { baseName = "FeltbokUi"; isStatic = true } }
    }
    if (System.getProperty("os.name").startsWith("Mac")) {
        sourceSets.iosMain { kotlin.srcDir(generateIosBuildInfo) }
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
        // iOS draws the picker map with MapLibre (Android uses osmdroid). Its iOS build brings
        // Compose Multiplatform 1.12 with it on iOS only; Android stays on 1.11.
        iosMain.dependencies {
            implementation("org.maplibre.compose:maplibre-compose:0.20.0")
            // The edge-swipe back gesture's progress, which Compose's iOS host reports through this.
            implementation("org.jetbrains.androidx.navigationevent:navigationevent-compose:1.1.0")
        }
        getByName("androidHostTest").dependencies {
            implementation("junit:junit:4.13.2")
        }
    }
}

// `./gradlew test` (the repo's pre-PR check) runs every module's `test`; the KMP Android library
// names its unit tests testAndroidHostTest.
tasks.register("test") { dependsOn("testAndroidHostTest") }
