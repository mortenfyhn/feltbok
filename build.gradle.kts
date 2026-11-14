// AGP 8.13's bundled R8 can't read Kotlin 2.4 metadata; a newer R8 can.
buildscript {
    repositories { google() }
    dependencies {
        classpath("com.android.tools:r8:9.5.28")
    }
}

plugins {
    id("com.android.application") version "8.13.2" apply false
    id("com.android.kotlin.multiplatform.library") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.4.21" apply false
    id("org.jetbrains.kotlin.multiplatform") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
    id("org.jetbrains.compose") version "1.11.1" apply false
    id("org.jlleitschuh.gradle.ktlint") version "12.1.2" apply false
    id("dev.detekt") version "2.0.0-alpha.6" apply false
    id("org.jetbrains.kotlinx.kover") version "0.9.11"
}

// Static analysis for every module. Run the type-resolved tasks (detektAll below), not plain
// `detekt`: only they run the rules that need the compiler's types, such as the one that bans `!!`.
subprojects {
    apply(plugin = "dev.detekt")
    extensions.configure<dev.detekt.gradle.extensions.DetektExtension> {
        buildUponDefaultConfig.set(true)
        config.setFrom(rootProject.file("detekt.yml"))
    }
    dependencies { "detektPlugins"("io.nlopez.compose.rules:detekt:0.6.8") }
}

// Every type-resolved detekt task that runs on Linux. iosMain is only analysed on macOS, where the
// iOS targets exist: the iOS workflow runs those tasks.
tasks.register("detektAll") {
    dependsOn(
        ":shared:detektMainJvm", ":shared:detektTestJvm",
        ":ui:detektMainAndroid", ":ui:detektHostTestAndroid",
        ":app:detektDebug", ":app:detektReleaseTestAndroidTest",
    )
}

// Line coverage of the unit tests, merged over :shared and :ui (`just coverage`). Report only, no
// gate. Composables are left out: the unit tests don't render them, so they'd drown the number.
dependencies {
    kover(project(":shared"))
    kover(project(":ui"))
}
kover {
    reports {
        filters { excludes { annotatedBy("androidx.compose.runtime.Composable") } }
    }
}
