/**
 * settings.gradle.kts
 *
 * Gradle settings for the SipSense project. Configures:
 * - Plugin management repositories (where Gradle looks for build plugins)
 * - Dependency resolution repositories (where Gradle looks for libraries)
 * - Project module structure
 *
 * @project SipSense - Smart Bottle Ecosystem
 */

// ── Plugin Management ────────────────────────────────────────────────
// Defines which repositories Gradle searches when resolving build plugins
// (e.g., Android Gradle Plugin, Kotlin plugin).
pluginManagement {
    repositories {
        google()          // Google's Maven repository (AGP, AndroidX, etc.)
        mavenCentral()    // Maven Central (Kotlin, third-party plugins)
        gradlePluginPortal() // Gradle Plugin Portal (community plugins)
    }
}

// ── Dependency Resolution ────────────────────────────────────────────
// Defines which repositories Gradle searches when resolving library
// dependencies declared in module-level build files.
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()          // Google's Maven (AndroidX, Material Components)
        mavenCentral()    // Maven Central (Kotlin stdlib, etc.)
    }
}

// ── Project Configuration ────────────────────────────────────────────
// Root project name – displayed in IDE and build output
rootProject.name = "SipSense"

// Include the 'app' module – contains the main Android application code
include(":app")
