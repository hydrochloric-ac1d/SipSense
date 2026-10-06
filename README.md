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

### 2. Clone the Repository
Open your terminal (or Git Bash) and run the following command to clone the project to your local machine:
```bash
git clone https://github.com/hydrochloric-ac1d/SipSense.git
```
*(If you have already cloned it, simply run `git pull origin main` inside the folder to get the latest updates).*

### 3. Open in Android Studio
1. Open **Android Studio**.
2. Click on **File > Open...** (or "Open" on the welcome screen).
3. Navigate to the folder where you cloned the repository (e.g., `Documents/GitHub/SipSense`) and select it.
4. Click **OK**.
5. Wait for Gradle to finish syncing the project dependencies.
   - *Note: If you encounter a JVM mismatch error (e.g., "Gradle 8.9 is incompatible with Java 25"), go to **File > Settings > Build, Execution, Deployment > Build Tools > Gradle** and ensure the **Gradle JDK** is set to Android Studio's bundled `jbr-21` or JDK 17.*
6. Connect your Android device or start an Emulator.
7. Click the **Run** button (green play icon) or press `Shift + F10` to build and launch the app!

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
