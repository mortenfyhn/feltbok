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
}
