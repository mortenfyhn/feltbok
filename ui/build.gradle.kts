// The app's Compose UI, shared with the coming iOS app (#115) through Compose Multiplatform. The
// logic it drives lives in :shared, which stays Compose-free. iOS targets only on macOS hosts, as in
// :shared, so Linux builds (CI, F-Droid) never need Kotlin/Native.
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
    // Android Lint, which the KMP library plugin alone doesn't set up for androidMain.
    id("com.android.lint")
    id("org.jetbrains.kotlin.plugin.compose")
    // Copies libraries' Compose resources (MapLibre's attribution strings) into the iOS app bundle.
    id("org.jetbrains.compose")
    id("org.jlleitschuh.gradle.ktlint")
}

// Compose Multiplatform 1.11 (material3 1.9) maps to the androidx Compose 1.11 (material3 1.4) the
// app's BOM pins, so Android keeps the same Compose underneath.
val composeVersion = "1.11.1"

// The in-app version string on iOS, from `git describe` like the Android build's BuildConfig.
// -Pscreenshots (or ORG_GRADLE_PROJECT_screenshots, through Xcode) shows just the last tag (v1.4),
// for the store screenshots.
val gitVersion = run {
    val screenshots = providers.gradleProperty("screenshots").isPresent // a local: config-cache safe
    providers.exec {
        commandLine("git", "describe", "--tags", "--always", "--dirty")
        isIgnoreExitValue = true
    }.standardOutput.asText.map { v -> v.trim().ifEmpty { "dev" }.let { if (screenshots) it.substringBefore("-") else it } }
}
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
        lint {
            warningsAsErrors = true
            // Dependency updates are picked deliberately, not on lint's nudge.
            disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion")
        }
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            // Compile lambdas to classes, not invokedynamic. The R8 pinned in the root build file
            // desugars those into MainViewModel$1, $2, ... When AGP 8.13 re-dexes one changed class,
            // those names collide with the Kotlin compiler's own anonymous classes, and the
            // incremental build fails ("defined multiple times"). Drop this with the move to AGP 9.
            freeCompilerArgs.addAll("-Xlambdas=class", "-Xsam-conversions=class")
        }
        withHostTestBuilder {}
    }
    if (System.getProperty("os.name").startsWith("Mac")) {
        // The iOS app (iosApp/) links this as a static framework.
        listOf(iosArm64(), iosSimulatorArm64()).forEach { it.binaries.framework { baseName = "FeltbokUi"; isStatic = true } }
    }
    if (System.getProperty("os.name").startsWith("Mac")) {
        sourceSets.iosMain {
            kotlin.srcDir(generateIosBuildInfo)
            dependencies {
                // iOS draws the picker map with MapLibre (Android uses osmdroid). Its iOS build brings
                // Compose Multiplatform 1.12 with it on iOS only; Android stays on 1.11.
                implementation("org.maplibre.compose:maplibre-compose:0.20.0")
                // The edge-swipe back gesture's progress, which Compose's iOS host reports through this.
                implementation("org.jetbrains.androidx.navigationevent:navigationevent-compose:1.1.0")
            }
        }
    }
    compilerOptions {
        allWarningsAsErrors.set(true)
        // Opt-in checks such as redundant modifiers and a var that could be a val.
        extraWarnings.set(true)
    }
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
            // Dispatchers.setMain, so a MainViewModel runs on the JVM.
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
        }
    }
}

// `./gradlew test` (the repo's pre-PR check) runs every module's `test`; the KMP Android library
// names its unit tests testAndroidHostTest.
tasks.register("test") { dependsOn("testAndroidHostTest") }
