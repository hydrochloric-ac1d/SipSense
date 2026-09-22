/**
 * build.gradle.kts (Project-level)
 *
 * Top-level build configuration for the SipSense Android project.
 * Declares the Android Gradle Plugin (AGP) and Kotlin plugin versions
 * used across all modules in the project.
 *
 * @project SipSense - Smart Bottle Ecosystem
 * @version 1.0
 */

// ── Plugin declarations ──────────────────────────────────────────────
// These plugins are declared here but applied in individual module
// build files (e.g., app/build.gradle.kts) using the "apply false" flag.
plugins {
    // Android Gradle Plugin – builds and packages the Android application
    id("com.android.application") version "8.6.0" apply false

    // Kotlin Android Plugin – enables Kotlin language support for Android
    id("org.jetbrains.kotlin.android") version "2.0.20" apply false
}
