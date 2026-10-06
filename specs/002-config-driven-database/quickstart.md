# Verification Quickstart: Config-Driven Database Connection

**Feature**: [spec.md](spec.md) | **Date**: 2026-09-30

Scenarios 1–3 are agent-verifiable and do not need a device or a provisioned database. Scenarios 4–6
require the external dependency in the spec (a Realtime Database in the configured project) and a
real device or emulator.

---

## Scenario 1 — The project still builds and lints (SC-005, FR-009)

```powershell
cd C:\Users\adaaz\StudioProjects\SipSense
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:lintDebug
```

**Pass**: both report `BUILD SUCCESSFUL`. Lint reports no *errors*. The warning count does not rise
above the 41 recorded during Feature 001 verification, and no new warning category appears.

If `JAVA_HOME` is unset, prefix with Android Studio's bundled JDK as documented in README step 5.

---

## Scenario 2 — No database URL or project identifier remains in source (SC-002, FR-002)

```powershell
cd C:\Users\adaaz\StudioProjects\SipSense
Get-ChildItem -Path app\src -Recurse -Include *.kt,*.xml |
    Select-String -Pattern 'firebaseio','firebasedatabase\.app','sipsense-1'
```

**Pass**: zero matches. Any match is a failure — the `sipsense-1` term deliberately catches
`sipsense-17a90`, `sipsense-17b6c`, and any third project identifier.

> **Do not collapse this into `Select-String -Path app\src -Recurse`.** `Select-String` has no
> `-Recurse` parameter, so that form throws a parameter-binding error and produces **no matches** —
> which reads exactly like a pass. Piping from `Get-ChildItem` is what makes the check real. Confirm
> the search works by running it once for a string you know is present, such as `FirebaseDatabase`;
> it should return hits in the provider.

---

## Scenario 3 — Authentication and database resolve from the same source (FR-003, by inspection)

Confirm by reading the source that:

1. No call passes a URL argument when obtaining the database.
2. The only place a database handle is created is the provider in `com.sipsense.app.data`.
3. `FirebaseAuth` is likewise obtained without any project argument.

**Pass**: both concerns derive from the bundled configuration, so no build can point them at
different projects.

---

## Scenario 4 — A project without a Realtime Database fails understandably (SC-004, FR-004, US3)

Run this **before** creating the Realtime Database, using the current configuration whose
`firebase_url` key is absent. This scenario becomes unreproducible once the database exists, so
capture it first if you want the evidence.

1. Build and install the debug app.
2. Open the registration screen. Register or attempt to reach the Profile tab while signed in.
3. Watch logcat, filtered on the app's process.

**Pass**:

- The app does **not** terminate unexpectedly on either screen.
- Logcat contains a diagnostic naming the cause — that the configured Firebase project has no
  Realtime Database — and the corrective action.
- The Profile tab shows the existing "Failed to load profile" message rather than a blank or
  crashed screen.
- The diagnostic contains no API key, no client secret, and no configuration file contents.

---

## Scenario 5 — A registered profile is stored and read back (SC-001, US1)

**Requires the external dependency**: a Realtime Database created in the configured project, and
`app/google-services.json` re-downloaded afterwards so it carries `firebase_url`. Confirm before
starting:

```powershell
cd C:\Users\adaaz\StudioProjects\SipSense
$j = Get-Content app\google-services.json -Raw | ConvertFrom-Json
Write-Host "project_id  :" $j.project_info.project_id
Write-Host "firebase_url:" $j.project_info.firebase_url
```

`firebase_url` must be non-empty. Then rebuild — a stale build embeds the old configuration.

1. Register a brand-new account, supplying a full name and a hydration target.
2. In the Firebase console for **the project printed above**, open Realtime Database and confirm a
   record exists at `/users/{uid}` with the name and hydration target entered.
3. Sign in, open the Profile tab, and confirm the name, email, and hydration target are displayed.
4. Force-stop the app, reopen it, and confirm the Profile tab still shows the saved values.
5. Change the hydration target with the slider and confirm the new value appears in the console.

**Pass**: the record is in the project named in the configuration, and the UI shows the stored values.

> **Check the console, not just the screen.** Baseline finding R3 means a failed write on the
> registration path is swallowed, so registration can look successful while nothing was stored. Step 2
> is the real assertion, which is why it is not optional.

---

## Scenario 6 — Swapping the configuration moves the app to another project (SC-003, US2)

1. Note which project the app currently uses.
2. Replace `app/google-services.json` with a different Firebase project's file — one whose Android
   client package is `com.sipsense.app` and which has a Realtime Database.
3. Rebuild and run. Register a new account.
4. Confirm the new account and its `/users/{uid}` record appear in the **second** project, and that
   sign-in also authenticates against the second project.
5. Confirm `git status` shows no modified file under `app/src/`.

**Pass**: both sign-in and data followed the configuration file, with zero source modifications.
Restore the correct configuration afterwards.

---

## Negative check — the configuration is still not committed (Principle VI)

```powershell
cd C:\Users\adaaz\StudioProjects\SipSense
git check-ignore -v app\google-services.json
git ls-files --error-unmatch app\google-services.json
```

**Pass**: the first command confirms an ignore rule matches. The second must **fail** with
"did not match any file(s) known to git" — if it succeeds, the configuration has been committed and
must be removed from tracking.
