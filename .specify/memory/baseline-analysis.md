# SipSense Baseline Analysis

**Date:** 2026-09-30
**Scope:** Setup/analysis only. No features implemented, no architecture changed, no
libraries added.
**Constitution:** `.specify/memory/constitution.md` v1.0.1

## How to use this document

This is a factual record of the codebase as of the date above, graded against the
constitution. It is **not** a statement of requirements and it is **not** a work plan.

Future `/automate-process` requests should read this instead of re-deriving the baseline.
Two rules apply when using it:

- Nothing listed here may be "fixed along the way." Principle X forbids drive-by changes.
  Each item needs its own `/speckit-specify` pass before it is touched.
- Items under **Blocked by missing requirements** must not be resolved by agent assumption
  (Principle I). They need a decision from the developer or an approved requirement.

The constitution's *Current Architectural Baseline* section holds the short authoritative
summary of what exists. This document holds the graded detail behind it.

---

## Verification performed

| Check | Result |
| --- | --- |
| Source inspection | Complete — all 7 Kotlin files, manifest, both Gradle files, all layouts |
| Spec Kit artifact state | No `specs/` directory; no feature artifacts exist |
| Test source presence | `app/src/test` and `app/src/androidTest` do not exist |
| Compile / build | **PASS** (2026-09-30) — `gradlew :app:assembleDebug` → BUILD SUCCESSFUL |
| Unit tests | **Not run** — no test sources and no test dependencies exist |
| Lint / static analysis | **PASS** (2026-09-30) — `gradlew :app:lintDebug` → BUILD SUCCESSFUL; 41 warnings, 0 errors, all pre-existing |
| `/speckit-analyze` | **Not applicable** — requires `spec.md`/`plan.md`/`tasks.md`, none exist |
| `/speckit-converge` | **Not applicable** — requires a feature directory and task list |

Neither `/speckit-analyze` nor `/speckit-converge` can run on a repository with no feature
artifacts, so this analysis was performed by direct inspection. No substitute toolchain was
invented.

---

## Project structure

Single Gradle module `app`, one flat package `com.sipsense.app`, plus `com.sipsense.app.model`.

```
app/src/main/java/com/sipsense/app/
  LoginActivity.kt        RegisterActivity.kt     MainActivity.kt
  DashboardFragment.kt    HistoryFragment.kt      DevicesFragment.kt
  ProfileFragment.kt      model/UserProfile.kt
```

There are no `data/`, `domain/`, `repository/`, `viewmodel/`, `device/`, or `iot/` packages.
All behavior lives in the four Android framework classes plus one data class.

---

## Implemented

Working end-to-end in code.

1. **Email/password registration** via Firebase Auth — `RegisterActivity.kt:610`.
2. **Client-side registration validation** — non-empty name, email format via
   `Patterns.EMAIL_ADDRESS`, password ≥ 8 characters, confirm-password match, hydration
   target parsed and range-checked, terms checkbox required (`RegisterActivity.kt:526-600`).
3. **Password strength indicator** — 4 criteria (length, mixed case, digit, symbol) driving a
   4-segment bar, colors, and Weak/Fair/Good/Strong label (`RegisterActivity.kt:296-426`).
4. **Email/password login** via Firebase Auth — `LoginActivity.kt:255`.
5. **Auto-login** — `LoginActivity` skips to `MainActivity` when
   `firebaseAuth.currentUser != null` (`LoginActivity.kt:132`).
6. **"Remember me" email persistence** — `SharedPreferences` file `SipSensePrefs`, keys
   `remember_me` and `saved_email` (`LoginActivity.kt:389-424`).
7. **Logout** — `signOut()` then `LoginActivity` with `CLEAR_TASK` (`ProfileFragment.kt:151`).
8. **Profile read** — `/users/{uid}` read into `UserProfile`, name and email rendered, with
   blank-field fallbacks (`ProfileFragment.kt:170-198`).
9. **Hydration target edit** — slider writes `/users/{uid}/hydrationTarget` on touch release
   (`ProfileFragment.kt:204-211`).
10. **Bottom navigation** — 4 destinations, `replace()` with no back stack
    (`MainActivity.kt:118-149`).
11. **Design system** — `colors.xml`, `strings.xml`, `dimens.xml`, `styles.xml`, 20 vector and
    shape drawables, ConstraintLayout + Material Components throughout.

---

## Partially implemented

| # | Item | State |
| --- | --- | --- |
| P1 | `UserProfile` model | Only `fullName`, `email`, `hydrationTarget`, `createdAt`. No hydration record, device, or sync entity exists anywhere. |
| P2 | Units toggle (ml/oz) | `ProfileFragment.kt:129-133` flips the label text only. The number is not converted, and the choice is not persisted. Selecting "oz" renders a millilitre value labelled `oz`. |
| P3 | Registration profile write | `setValue()` is fire-and-forget; navigation to login happens immediately regardless of outcome (`RegisterActivity.kt:629-637`). |
| P4 | Connection status indicator | Header badge and `updateWifiStatus()` exist, but `MainActivity.kt:92` calls `updateWifiStatus(true)` unconditionally. Nothing observes real connectivity, and no caller ever passes `false`. |
| P5 | Login password validation | Checks non-empty only (`LoginActivity.kt:241`), while registration enforces ≥ 8 characters. |

---

## Missing

No implementation exists.

1. **Hydration intake tracking** — no record model, no logging UI, no persistence.
   `DashboardFragment` inflates a layout containing a single title `TextView`.
2. **Hydration history** — `HistoryFragment` is likewise a single title `TextView`. No queries,
   no aggregation, no charts.
3. **IoT / smart bottle integration** — no BLE or Bluetooth code, no device entity, no pairing
   flow, no transport, no event handling. `DevicesFragment` is a single title `TextView`. The
   merged manifest declares only `INTERNET`, `ACCESS_NETWORK_STATE`, and `READ_GSERVICES`, all
   contributed by the Firebase and Play Services SDKs — no Bluetooth, location, or
   notification permissions anywhere.
4. **Reminders / notifications** — no notification channel, no `POST_NOTIFICATIONS`, no
   scheduling (neither WorkManager nor AlarmManager is a dependency).
5. **Synchronization and reconciliation** — no sync state, no conflict handling. All data access
   is direct Firebase calls from UI classes.
6. **Offline support** — Realtime Database disk persistence is never enabled
   (`setPersistenceEnabled` appears nowhere) and there is no local database.
7. **Architecture layers** — no ViewModel, LiveData/Flow, repository, use case, or DI. No
   `lifecycle-viewmodel` dependency.
8. **Test infrastructure** — `app/src/test` and `app/src/androidTest` do not exist, and
   `app/build.gradle.kts` declares no test dependencies (no JUnit, no Espresso, no
   coroutines-test, no Robolectric).
9. **Password reset** — "Forgot Password?" shows a toast (`LoginActivity.kt:361-369`).
10. **Email verification** — never requested or checked.
11. **Placeholder-only settings rows** — Change Password, Notification settings, Connected apps
    each show "coming soon" toasts (`ProfileFragment.kt:135-148`).
12. **Terms of Service / Privacy Policy** — clickable links show "Coming soon!" toasts
    (`RegisterActivity.kt:484-504`).

---

## Incomplete build infrastructure

Status updated 2026-09-30 by feature `001-build-infrastructure`.

- **B1 — Gradle wrapper was incomplete. RESOLVED.** `gradlew`, `gradlew.bat`, and
  `gradle/wrapper/gradle-wrapper.jar` were absent, leaving only
  `gradle-wrapper.properties`, so no command-line build was possible. The wrapper was
  regenerated from the official Gradle 8.9 distribution and verified: `gradlew.bat --version`
  downloads and validates the pinned distribution and reports Gradle 8.9. Command-line builds,
  lint, and CI are now mechanically possible.
- **B2 — `app/google-services.json`. RESOLVED as a build blocker (2026-09-30).** The file is now
  present, `:app:processDebugGoogleServices` passes, and both `:app:assembleDebug` and
  `:app:lintDebug` succeed. It remains correctly untracked, so every new clone must still supply
  it — README step 3 documents how. **Earlier caveat resolved 2026-09-30 15:10**: the conflict between
  the supplied `sipsense-17b6c` config and a hardcoded `sipsense-17a90` database URL is gone — Feature
  002 removed the literals and the Realtime Database now exists in `sipsense-17b6c` with its URL
  present in the config. See R2. The original blocker description follows for history.
- **B2 (historical) — `app/google-services.json` was absent. DOCUMENTED, developer-supplied.**
  `app/build.gradle.kts` applies `com.google.gms.google-services`, which fails the build when
  the file is missing, so a fresh clone cannot build until a developer supplies it. Gitignoring
  the file is correct per Principle VI and will not change. What changed is that README step 3
  now names the requirement, its source, the exact path, the never-commit rule, and both failure
  symptoms (missing file, and a file from the wrong Firebase project). **This remains an active
  blocker for automated verification until the file is present in the working tree** — as of
  2026-09-30, after two attempts, it is not, so build and lint cannot pass here. A
  `google-services.json` for an unrelated project (`trivagoo-1c868`) exists in the user's
  `Downloads` folder; it is not usable and was not copied.
- **B3 — Environment note.** Neither `java` nor `gradle` is on `PATH` in this shell. Gradle is no
  longer needed (the wrapper supplies it), but a JDK still must be named explicitly via
  `JAVA_HOME`; Android Studio's bundled JDK 21 works and is documented in README step 5. This is
  an environment observation, not a repository defect.

---

## Potentially problematic

| # | Finding | Evidence |
| --- | --- | --- |
| R1 | **Hydration target range conflict, crashes the Profile tab.** Registration accepts any integer 500–10000. The profile slider is `valueFrom="500"`, `valueTo="5000"`, `stepSize="100"`. `ProfileFragment` assigns the stored target straight onto the slider, and Material `Slider` throws when the value exceeds `valueTo` or is not a multiple of `stepSize`. A target of 8000, or of 2550, is reachable at registration and will crash on opening Profile. | `RegisterActivity.kt:586`, `fragment_profile.xml:83-86`, `ProfileFragment.kt:186` |
| R2 | **Realtime Database URL hardcoded in two places, and it points at a different Firebase project than the app authenticates against.** The literal `https://sipsense-17a90-default-rtdb.firebaseio.com` is duplicated in source, bypassing the gitignored `google-services.json` and placing the instance identifier in a public repository. **Escalated 2026-09-30**: the `app/google-services.json` now in use is for project `sipsense-17b6c`, whose `firebase_url` is empty (no Realtime Database). So Auth resolves to `17b6c` while every database call explicitly targets `17a90`. Cross-project tokens do not satisfy another project's security rules, so `/users/{uid}` reads and writes should fail with permission denied — silently on registration because of R3. Severity was raised to **CRITICAL**. **RESOLVED IN CODE 2026-09-30** by Feature 002: the developer confirmed `sipsense-17b6c` is authoritative, both literals were removed, and the database is now resolved from `google-services.json` through `com.sipsense.app.data.FirebaseDatabaseProvider`, so Auth and Database can no longer diverge. **FULLY RESOLVED 2026-09-30 15:10.** The Realtime Database was created in `sipsense-17b6c` and a fresh config supplied. `project_info.firebase_url` is now `https://sipsense-17b6c-default-rtdb.firebaseio.com`, and force-regenerating the Google Services resources confirms the build emits `firebase_database_url` with that value — so the no-argument `getInstance()` resolves a real database in the same project Auth uses. Auth and database can no longer diverge, in code or in configuration. See `specs/002-config-driven-database/tasks.md` T016. | was `RegisterActivity.kt:155`, `ProfileFragment.kt:75`; now `data/FirebaseDatabaseProvider.kt` |
| R3 | **Orphaned auth accounts.** If the `/users/{uid}` write fails, the Firebase Auth user exists with no profile record. The failure handler only calls `printStackTrace()`, and the user is sent to login either way. | `RegisterActivity.kt:629-637` |
| R4 | **Async callbacks outlive the fragment view.** `ProfileFragment` Firebase callbacks write to `lateinit` views and pass `context` to `Toast` with no view-lifecycle guard, so navigating away during the read can crash or no-op silently. | `ProfileFragment.kt:174-198, 208-210` |
| R5 | **"Remember me" persists before authentication.** `saveCredentials(email)` runs before `signInWithEmailAndPassword`, so a failed login still stores the email. | `LoginActivity.kt:249-255` |
| R6 | **No offline or timeout handling on profile load.** `addListenerForSingleValueEvent` with persistence disabled may never fire while offline. There is no loading state, no timeout, and no retry — the Profile tab simply stays empty. | `ProfileFragment.kt:174` |
| R7 | **Business rules live in presentation code.** The 500–10000 range, the 2500 default, the 4-criteria password scoring, and the ml/oz handling are all embedded in Activities, a Fragment, and XML. This is the concrete Principle II gap. | `RegisterActivity.kt:296-426, 586`, `UserProfile.kt:40`, `fragment_profile.xml:83-86` |
| R8 | **Auth tab navigation calls `finish()` in both directions**, so toggling Login ↔ Sign Up repeatedly destroys and recreates activities rather than swapping state. | `LoginActivity.kt:190-196`, `RegisterActivity.kt:245-249` |
| R9 | **Unused import** — `MaterialButtonToggleGroup` in `ProfileFragment`. | `ProfileFragment.kt:32` |
| R10 | **Build artifacts are tracked in git.** `.gradle/`, `app/build/`, and `.idea/` are listed in `.gitignore` but were committed before the ignore rules existed, so they appear as modified in every diff. This makes Principle X review harder because real changes are buried in generated noise. **RESOLVED for `app/build/` and `.gradle/` 2026-09-30 by Feature 004** — 496 index entries removed via `git rm -r --cached`, dropping the tracked set from 577 files to 81 with zero deletions from disk. The diagnosis in this row was half right: the ignore rules were correct all along, and the actual cause is that Git gives the index precedence over `.gitignore`, so no rule change was needed. **Still open**: 19 files under `.idea/` (11), `.vs/` (7), and `local.properties` (1) remain tracked-but-ignored and need a separate decision. The removals are staged and **not committed**. | `git status` |

---

## Blocked by missing requirements

Open questions that materially affect implementation and that Principle I forbids resolving
by assumption. Each must be answered by an approved requirement before the related feature is
specified.

**Hydration targets and units**

1. What are the authoritative minimum, maximum, step, and default for a hydration target?
   Registration says 500–10000 with any integer; the profile slider says 500–5000 in steps of
   100; `UserProfile` defaults to 2500. Three different rules, no source. This is the direct
   cause of R1.
2. Is the ounces unit an approved feature? If so: the conversion rule, rounding behavior,
   which unit is stored canonically, and whether the preference is per-user and persisted.

**Hydration records**

3. What constitutes a hydration record — which fields, and what identifies one uniquely?
4. What is the day boundary for "daily" intake, and in whose timezone?
5. Can a user edit or delete a record, and may a record be logged manually as well as by the
   bottle?
6. Who owns the record — is the server authoritative, or is the device local-first? What is the
   retention period?

**Device and IoT**

7. What transport does the smart bottle use? The header badge and its drawables say WiFi, while
   `DevicesFragment`'s own documentation says "Bluetooth/WiFi connection status." Undecided.
8. What does the bottle emit, and at what cadence? What identifies a device event so replays can
   be recognized (Principle IV)?
9. What is the pairing model, and may a user have more than one bottle?
10. What does the header connection badge actually represent — network reachability, or bottle
    connectivity? It is currently hardcoded to connected (P4).

**Offline and sync**

11. Which features must keep working offline, and which may degrade?
12. What is the conflict-resolution rule when local and remote data disagree on reconcile?

**Reminders**

13. Are hydration reminders in scope? If so, what schedules them, what triggers them, and what
    happens when notification permission is denied?

**Authentication and account**

14. Is the 4-criteria password indicator advisory only, or must those criteria be enforced?
    Registration currently enforces just the 8-character minimum.
15. Is email verification required before use?
16. Are "Forgot password", "Change password", "Connected apps", "Notification settings", and the
    Terms/Privacy documents approved requirements, or speculative UI?
17. What is the expected behavior across multiple devices or concurrent sessions?

**Security**

18. Realtime Database security rules are not present in this repository. Where are they defined
    and reviewed? Without them, the ownership guarantees in Principle IV are unverifiable.

---

## Constitution compliance

Pre-existing conditions, not violations by any contributor — the code predates the constitution,
which was ratified 2026-09-30. Recorded so future work can close gaps deliberately.

| Principle | State | Basis |
| --- | --- | --- |
| I — Business-Process Authority | **At risk** | Undocumented business rules in code with no requirement source (R7), and three conflicting target ranges (open question 1). |
| II — Android/Kotlin First | **Not met** | Kotlin: yes. Layer separation: absent. All logic sits in Activities and Fragments; the only non-UI class is `UserProfile`. |
| III — IoT Reliability | **Not met** | No IoT integration exists, and the single connection indicator is hardcoded `true` (P4) — the app currently asserts a connection it never measures. |
| IV — Data Integrity | **Partial** | `UserProfile` ownership is clear (`/users/{uid}`). No hydration records exist, so duplication is not yet possible. Device-data validation is absent, write-failure handling is weak (R3), and DB security rules are not in the repo. |
| V — Offline-Aware Design | **Not met** | RTDB persistence disabled, no local store, no reconciliation, no offline handling on read (R6). |
| VI — Security and Privacy | **Mostly met** | No credentials in hand-written source; `google-services.json` correctly gitignored. R2's hardcoded DB URL was removed by Feature 002. Least privilege is trivially satisfied — no permissions are requested. **Finding 2026-09-30 (Feature 004)**: tracked build output contained the Firebase API key, and it remains in **committed history** (2 key-shaped values in `app/build/intermediates/packaged_res/.../values.xml` at `HEAD`). Untracking stops future exposure but cannot undo past exposure. A Firebase Android key ships in every APK by design and is guarded by database rules and App Check rather than secrecy, so this is not an emergency — but rotation and history rewriting are open developer decisions. Remaining gap: security rules are still absent from the repo. |
| VII — Testing | **Partially met (was: not met)** | The original finding — zero test sources, zero test dependencies, build verification blocked — was resolved by Features 001–003 and extended by Feature 005. As of 2026-09-30: **12 passing local unit tests** across 3 classes, JUnit + MockK + **Robolectric**, and build/lint/test all runnable and passing. The UI layer is now testable without a device, which previously blocked any Activity/Fragment work from satisfying this principle. Still outstanding: no tests exist for the remaining untouched screens (Login, Dashboard, History, Devices), and `RegisterActivity`'s unresolved-database guard cannot be covered until open questions 1–2 are answered (Feature 005 T022). |
| VIII — Maintainability | **Partial** | Consistent style and unusually thorough documentation. Gaps: duplicated DB URL literal (R2), validation logic duplicated between Login and Register, one unused import (R9). |
| IX — Spec-Driven Development | **Baseline** | No specifications exist. This document is the first Spec Kit artifact; all existing code predates the process. |
| X — Change Control | **Enforceable from now** | Nothing to assess retroactively. Tracked build artifacts (R10) actively hampered review; cleaned up as its own change by Feature 004 on 2026-09-30, so diffs now show only real source changes. `.idea/`, `.vs/`, and `local.properties` remain tracked pending a separate decision. |
| XI — Definition of Done | **Unreachable today** | Cannot be satisfied while the build and tests cannot run. B1 and B2 are prerequisites for any feature to be declared done. |

---

## Suggested sequencing

Not a plan and not approved work — only the dependency order implied by the findings above.

1. ~~**B1 and B2 first.**~~ **DONE** — Features 001 and 002. Wrapper restored, Firebase
   configuration documented and now config-driven; build, lint, and tests all run.
2. ~~**Test infrastructure next**~~ **DONE** — Features 003 and 005. 12 passing unit tests, and as
   of Feature 005 the UI layer is testable locally via Robolectric, so Principle VII no longer
   blocks Activity/Fragment work.
3. **← CURRENT BLOCKER. Resolve open questions 1 and 2**, which unblocks R1 (a reachable crash)
   and Feature 005 T022 (the last uncovered unresolved-database guard). **This now requires a
   developer decision, not agent work**: everything downstream of it is blocked, and Principle I
   forbids resolving it by assumption. Answering it is the single highest-value action available.
4. **Then hydration records** (open questions 3–6), since tracking, history, and any device
   integration all depend on that model.
5. **Then device/IoT integration** (open questions 7–10), which depends on the record model and
   on a transport decision.
