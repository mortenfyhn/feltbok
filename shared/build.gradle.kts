// Platform-free code shared with the coming iOS app (#115): plain Kotlin, no Android or JVM APIs.
// The Android app consumes the JVM target like any JVM library, so this module needs no Android
// plugin, flavors or R8 setup of its own.
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jlleitschuh.gradle.ktlint")
}

kotlin {
    jvm {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }
    // iOS targets only on macOS, the only host that can build them. Keeps Linux builds (CI,
    // F-Droid's build server) to the JVM target, with no Kotlin/Native toolchain to download.
    if (System.getProperty("os.name").startsWith("Mac")) {
        iosArm64()
        iosSimulatorArm64()
    }
    compilerOptions { allWarningsAsErrors.set(true) }
    sourceSets {
        jvmTest.dependencies { implementation("junit:junit:4.13.2") }
    }
}

// `./gradlew test` (the repo's pre-PR check) runs every module's `test`; KMP names it jvmTest.
tasks.register("test") { dependsOn("jvmTest") }
