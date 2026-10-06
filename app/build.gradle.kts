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

    // Google Services – processes google-services.json for Firebase SDK configuration
    id("com.google.gms.google-services")
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

    // ── Unit Test Options ────────────────────────────────────────────
    testOptions {
        unitTests {
            // Local unit tests run against a stub Android framework whose methods
            // throw ("Method e in android.util.Log not mocked"). Code under test
            // logs via android.util.Log inside the branches being verified, so
            // returning defaults instead of throwing is required for those tests
            // to reach their assertions. Revisit if a test ever needs real
            // Android behaviour - that is the point to consider Robolectric.
            //
            // Feature 005 reached that point and added Robolectric. This flag is
            // KEPT deliberately: it applies to tests that do NOT use Robolectric
            // (FirebaseDatabaseProviderTest, UserProfileTest), which still call
            // android.util.Log against the stub framework. Robolectric-backed
            // tests supply real Android behaviour and are unaffected by it, so
            // the two settings coexist rather than conflict.
            isReturnDefaultValues = true

            // Robolectric needs the merged resources, assets, and manifest to
            // inflate layouts and resolve themes. Without this, inflating
            // fragment_profile fails to find its Material theme.
            isIncludeAndroidResources = true
        }
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

    // ── Firebase ─────────────────────────────────────────────────────
    // Firebase BoM – manages all Firebase library versions automatically.
    // Only the BoM version is specified; individual library versions are inherited.
    implementation(platform("com.google.firebase:firebase-bom:33.6.0"))

    // Firebase Authentication – Email/Password user sign-in and registration
    implementation("com.google.firebase:firebase-auth-ktx")

    // Firebase Realtime Database – Store and sync user profile data
    implementation("com.google.firebase:firebase-database-ktx")

    // ── Local Unit Tests ─────────────────────────────────────────────
    // These are testImplementation only and are never packaged into the APK.

    // JUnit 4 – the framework the Android Gradle Plugin's unit test task expects
    // by default, so no extra plugin or runner configuration is needed.
    testImplementation("junit:junit:4.13.2")

    // MockK – required to stub the static FirebaseDatabase.getInstance() call so
    // the database-resolution failure path can be exercised. The production code
    // has no injection seam for it, and adding one purely for tests is out of
    // scope for the feature that introduced the behaviour.
    testImplementation("io.mockk:mockk:1.13.13")

    // Robolectric - runs real Android framework behaviour inside a local JVM test,
    // so code in an Activity or Fragment can be exercised without a device or
    // emulator. Added by Feature 005 because the unresolved-database guards live in
    // ProfileFragment and report failure through a Toast; neither is reachable from
    // a plain JUnit test. The alternative was instrumented (androidTest) tests,
    // rejected because they need a device and so cannot be part of automated
    // verification. Test-scoped only - never packaged into the APK.
    testImplementation("org.robolectric:robolectric:4.14.1")
}
