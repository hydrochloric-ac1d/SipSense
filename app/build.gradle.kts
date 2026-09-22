/**
 * build.gradle.kts (Module: app)
 *
 * Build configuration for the SipSense Android application module.
 * Defines compile/target SDK versions, application ID, dependencies,
 * and build features required for the Login & Registration screens.
 *
 * Key Dependencies:
 * - Material Components: TextInputLayout, Material themes
 * - ConstraintLayout: Primary layout system (as per project requirements)
 * - AppCompat: Backward-compatible Activity and theme support
 *
 * @project SipSense - Smart Bottle Ecosystem
 * @version 1.0
 */

// ── Plugins ──────────────────────────────────────────────────────────
plugins {
    // Apply the Android Application plugin for building an APK
    id("com.android.application")

    // Apply the Kotlin Android plugin for Kotlin language support
    id("org.jetbrains.kotlin.android")
}

// ── Android Configuration ────────────────────────────────────────────
android {
    // Namespace for generated R class and BuildConfig
    namespace = "com.sipsense.app"

    // SDK versions
    compileSdk = 35  // Compile against API 35 for access to latest APIs

    defaultConfig {
        // Unique application identifier on Google Play
        applicationId = "com.sipsense.app"

        // Minimum Android version supported (Android 8.0 / Oreo)
        minSdk = 26

        // Target SDK level for runtime behavior optimization
        targetSdk = 35

        // Internal version tracking
        versionCode = 1
        versionName = "1.0.0"
    }

    // ── Build Types ──────────────────────────────────────────────────
    buildTypes {
        release {
            // Disable code shrinking for initial development
            isMinifyEnabled = false

            // ProGuard rules for release builds (when minification is enabled)
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // ── Kotlin / Java Compatibility ──────────────────────────────────
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

// ── Dependencies ─────────────────────────────────────────────────────
dependencies {
    // AndroidX Core KTX – Kotlin extensions for Android framework APIs
    implementation("androidx.core:core-ktx:1.13.1")

    // AppCompat – Provides backward-compatible Activity, themes, and UI components
    implementation("androidx.appcompat:appcompat:1.7.0")

    // Material Components – TextInputLayout, Material themes, ripple effects
    // Required for the styled input fields with start icons and password toggle
    implementation("com.google.android.material:material:1.12.0")

    // ConstraintLayout – Primary layout manager for Login & Registration screens
    // Enables flat view hierarchies with flexible constraint-based positioning
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
}
