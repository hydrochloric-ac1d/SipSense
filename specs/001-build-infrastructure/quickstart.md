# Quickstart Validation: Build Infrastructure Baseline

**Feature**: `001-build-infrastructure` | **Date**: 2026-09-30

How to prove this feature works. Scenarios 1–3 are automated; scenario 4 is manual and is the
only one that genuinely validates the clean-clone outcome in SC-001.

## Prerequisites

- A JDK 17 or 21. On this machine: `C:\Program Files\Android\Android Studio\jbr`.
- `app/google-services.json`, obtained per the README setup section. Scenarios 2 and 3 cannot
  pass without it.

Set `JAVA_HOME` for a shell session that has no JDK on `PATH`:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
```

## Scenario 1 — The wrapper executes without pre-installed Gradle

Proves FR-001, FR-002, and acceptance scenario 1.1.

```powershell
.\gradlew.bat --version
```

**Expected**: reports `Gradle 8.9`. It must not report any other version, and it must not fail
with a missing-file or missing-class error.

## Scenario 2 — A debug build succeeds

Proves FR-001, SC-001, SC-005, and acceptance scenario 1.3.

```powershell
.\gradlew.bat :app:assembleDebug
```

**Expected**: `BUILD SUCCESSFUL`. Specifically, `:app:processDebugGoogleServices` must pass
rather than fail as it did on 2026-09-30.

## Scenario 3 — Static analysis runs

Proves FR-001, SC-002, SC-005, and acceptance scenario 2.1.

```powershell
.\gradlew.bat :app:lintDebug
```

**Expected**: the task runs to completion and reports a result. A lint report is written under
`app/build/reports/lint-results-debug.html`. Pre-existing lint findings are **not** failures of
this feature — the requirement is that lint *runs*. Any findings belong to the baseline record,
not to this change.

## Scenario 4 — Clean-clone documentation walkthrough (MANUAL)

Proves SC-001 and SC-003, and acceptance scenarios 1.2, 3.1, and 3.2. This cannot be automated
here, because the working tree already has everything a clean clone lacks.

1. Clone the repository into a new directory.
2. Following **only** the README, without prior knowledge, attempt a build.
3. Confirm the build fails at `:app:processDebugGoogleServices` with
   "File google-services.json is missing."
4. Confirm the README names that exact failure and gives the remedy.
5. Follow the README's instructions to obtain and place `app/google-services.json`.
6. Re-run the build and confirm it succeeds.
7. Confirm `git status` does **not** list `app/google-services.json` as an untracked or staged
   file.

**Expected**: every step is completable from the README alone, with no guessing and no external
help.

## Scenario 5 — No behavioral regression (MANUAL)

Proves FR-006, FR-007, and SC-004.

1. Open the project in Android Studio and build it as before.
2. Install and run on a device or emulator.
3. Exercise registration, login, and the Profile tab.

**Expected**: identical behavior to before this feature. This feature changes no application
code, so any difference is a defect in this change.

## Negative check — configuration is still protected

Proves FR-008.

```powershell
git check-ignore -v app/google-services.json
```

**Expected**: reports the matching `.gitignore` rule, confirming the file cannot be committed
accidentally.
