# SipSense 💧
**ELDROID IoT Project**

SipSense is a Smart Bottle Ecosystem Android application built with Kotlin. It is designed to help users track their daily hydration, set targets, and sync with their IoT smart bottle devices.

---

## 🚀 Getting Started

Follow these steps to clone the repository, pull the latest code, and open it on your local machine using Android Studio (or Visual Studio).

### 1. Prerequisites
- **Git** installed on your machine.
- **Android Studio** (or Visual Studio 2026 with Android workload).
- **Java JDK 17** or **21** (Android Studio's bundled JBR-21 is recommended).
- **Access to the SipSense Firebase project**, so you can download the configuration file required
  in step 3. The project must have an Android app registered for package `com.sipsense.app` and a
  **Realtime Database** created in it. Gradle itself does not need to be installed — the committed
  wrapper handles it.

### 2. Clone the Repository
Open your terminal (or Git Bash) and run the following command to clone the project to your local machine:
```bash
git clone https://github.com/hydrochloric-ac1d/SipSense.git
```
*(If you have already cloned it, simply run `git pull origin main` inside the folder to get the latest updates).*

### 3. Firebase Configuration (Required)

**The project will not build until you complete this step.** SipSense uses Firebase
Authentication and Realtime Database, and the Firebase SDK needs a configuration file that is
deliberately **not** stored in this repository — it contains your project's API key and client
IDs, so committing it would expose them.

**If you skip this step, the build fails like this:**

```
> Task :app:processDebugGoogleServices FAILED

Execution failed for task ':app:processDebugGoogleServices'.
> File google-services.json is missing.
  The Google Services Plugin cannot function without it.
```

**To fix it:**

1. Open the [Firebase console](https://console.firebase.google.com/) and select the SipSense
   project. The project currently in use is **sipsense-17b6c** — if that ever changes, update this
   line, because nothing in the source code pins the project any more (see step 3b).
2. Go to **Project settings** (the gear icon, top-left) → **General**.
3. Under **Your apps**, select the Android app with package name `com.sipsense.app`.
4. Click **Download google-services.json**.
5. Place the file at exactly this path:

   ```
   SipSense/app/google-services.json
   ```

6. Re-run the build, or **File > Sync Project with Gradle Files** in Android Studio.

> **Never commit this file.** It is already listed in `.gitignore`, so Git will ignore it
> automatically. Verify with `git check-ignore -v app/google-services.json` — if that prints a
> matching rule, you are protected. If you ever need to share the file with a teammate, send it
> directly; do not put it in the repository.

**Make sure it is the right project's file.** A `google-services.json` downloaded from a
different Firebase project looks identical but fails with a different message:

```
> Task :app:processDebugGoogleServices FAILED

No matching client found for package name 'com.sipsense.app'
```

If you see that, you downloaded the file from the wrong Firebase project. Check it before
copying — the file is plain JSON, and
`client[].client_info.android_client_info.package_name` must read `com.sipsense.app`.

### 3b. The Firebase project must have a Realtime Database

The app reads its database location **from `google-services.json`**, not from a URL written into
the source code. That is deliberate: it guarantees that sign-in and data always go to the same
Firebase project. It also means the configured project must actually have a Realtime Database.

Check the file you downloaded — `project_info.firebase_url` must be present and non-empty:

```powershell
$j = Get-Content app\google-services.json -Raw | ConvertFrom-Json
Write-Host "project_id  :" $j.project_info.project_id
Write-Host "firebase_url:" $j.project_info.firebase_url
```

If `firebase_url` is missing, the project has no Realtime Database yet. The build still succeeds and
the app still starts, but profiles cannot be saved or loaded, and logcat shows:

```
E/SipSenseDatabase: No Realtime Database is configured for this build. Create a Realtime
Database in the Firebase project used by app/google-services.json, then download that file
again so it includes the database URL, and rebuild.
```

To fix it: in the Firebase console open **Build → Realtime Database → Create Database**, then
**download `google-services.json` again** — the file only gains `firebase_url` after the database
exists — replace your copy, and rebuild. A stale copy still has no database URL.

**No access to the Firebase project?** Ask the project owner to add your Google account to it, or to
send you the file directly. Do not work around this step by creating a placeholder file or by reusing
another project's file — the build would either fail as above or succeed and then fail at runtime when
Firebase cannot initialise.

### 4. Open in Android Studio
1. Open **Android Studio**.
2. Click on **File > Open...** (or "Open" on the welcome screen).
3. Navigate to the folder where you cloned the repository (e.g., `Documents/GitHub/SipSense`) and select it.
4. Click **OK**.
5. Wait for Gradle to finish syncing the project dependencies.
   - *Note: If you encounter a JVM mismatch error (e.g., "Gradle 8.9 is incompatible with Java 25"), go to **File > Settings > Build, Execution, Deployment > Build Tools > Gradle** and ensure the **Gradle JDK** is set to Android Studio's bundled `jbr-21` or JDK 17.*
6. Connect your Android device or start an Emulator.
7. Click the **Run** button (green play icon) or press `Shift + F10` to build and launch the app!

### 5. Build from the Command Line (optional)

The Gradle wrapper is committed, so you do not need Gradle installed — the wrapper downloads the
pinned version (8.9) on first use. You only need a JDK 17 or 21.

If no JDK is on your `PATH`, point `JAVA_HOME` at Android Studio's bundled runtime:

```powershell
# Windows (PowerShell)
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:lintDebug
```

```bash
# macOS / Linux
export JAVA_HOME=/path/to/jdk-17-or-21
./gradlew :app:assembleDebug
./gradlew :app:lintDebug
```

Step 3 applies here too: without `app/google-services.json`, both commands fail at
`:app:processDebugGoogleServices`.

### 6. Run the Tests

```powershell
# Windows (PowerShell)
.\gradlew.bat :app:testDebugUnitTest
```

```bash
# macOS / Linux
./gradlew :app:testDebugUnitTest
```

These are local unit tests, so they need **no emulator, no connected device, and no network**. They
also do not need a provisioned Realtime Database — the tests that cover database resolution stub the
Firebase SDK rather than contacting a real project, so they pass on a fresh checkout.

The command exits non-zero if any test fails, and the failing test is named in the output. A full
report is written to `app/build/reports/tests/testDebugUnitTest/index.html`.

You can also run tests from Android Studio: open any class under `app/src/test/java/` and use the run
gutter icon next to the class or an individual test.

---

## ✨ Recent Updates (Login & Registration Implementation)

This initial commit establishes the foundational Android structure and introduces a fully designed, interactive Authentication flow.

### What was built:
- **Project Structure**: Configured Gradle 8.9 with Android Gradle Plugin 8.6.0.
- **Theming & Resources**: Set up a comprehensive design system including color palettes (`colors.xml`), string resources, custom drawables (gradient backgrounds, rounded cards, vector icons), and styles removing the default ActionBar.
- **Login Screen (`LoginActivity.kt`)**:
  - Implemented using `ConstraintLayout`.
  - Features real-time email format validation and password length checks.
  - Interactive "Remember me" checkbox powered by Android `SharedPreferences` to save/restore the user's email across sessions.
  - Includes a visual password toggle and tab navigation.
- **Registration Screen (`RegisterActivity.kt`)**:
  - A comprehensive sign-up form with a **Real-Time Password Strength Indicator**.
  - As the user types, a 4-segment visual bar updates dynamically (Grey → Red → Orange → Teal → Green) based on 4 criteria (8+ characters, uppercase & lowercase, number, symbol).
  - Includes hydration target inputs (mL limit), password confirmation mismatch detection, and terms of service checkboxes.
  - Tab navigation allowing seamless swapping back to the Login screen.

### Technical Highlights:
- **ConstraintLayout** utilized heavily for flat, highly responsive view hierarchies.
- **Material Components** (`TextInputLayout`, `TextInputEditText`) used for beautiful, accessible input fields.
- **SpannableStrings** used to create clickable inline text (e.g., "Sign up now" and "Terms of Service" links).
