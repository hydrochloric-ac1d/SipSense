# Feature Specification: Config-Driven Database Connection

**Feature Branch**: `002-config-driven-database`

**Created**: 2026-09-30

**Status**: Draft

**Input**: Developer decision recorded 2026-09-30: Firebase project **`sipsense-17b6c`** is
authoritative for SipSense. A Realtime Database will be created in it, and the hardcoded
`sipsense-17a90` database URLs are to be removed so that the bundled Firebase configuration
determines which project the app uses. Scope confirmed as "remove the hardcoded database URLs so
config drives it, fixing baseline finding R2 properly."

## Context

Baseline finding **R2** recorded that the Realtime Database URL is duplicated as a string literal
in two source files. Verification of Feature 001 escalated this from a hygiene problem to a
correctness defect:

| Concern | Resolves to | Source of truth |
| --- | --- | --- |
| Firebase Authentication | `sipsense-17b6c` | `app/google-services.json` (bundled at build time) |
| Realtime Database | `sipsense-17a90` | hardcoded literal in two Kotlin files |

Because the database URL is passed explicitly to `FirebaseDatabase.getInstance(url)`, it overrides
the bundled configuration. The app therefore authenticates a user against one Firebase project and
then reads and writes that user's profile in a **different** project's database. An ID token issued
by one project does not satisfy another project's security rules, so profile access is expected to
be denied.

The `firebase_url` key is **absent entirely** from the current `app/google-services.json`, meaning
project `sipsense-17b6c` has no Realtime Database provisioned yet. This makes the ordering of work
significant and is captured in Dependencies below.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A user's profile is actually saved and shown (Priority: P1)

A person registers a SipSense account, enters their profile details including a hydration target,
and later opens the Profile tab. They see the details they entered, not an error and not empty
defaults. What they saved is what comes back.

**Why this priority**: This is the defect users experience. Registration currently appears to
succeed while the profile write is rejected, so the entered hydration target and personal details
are lost. Hydration targets are one of the five approved product purposes, so losing them defeats
core functionality.

**Independent Test**: Register a new account, complete the profile, force-close the app, reopen it,
and open the Profile tab. The saved values must be present. Confirm the record also exists under
`/users/{uid}` in the Firebase project named in `app/google-services.json`.

**Acceptance Scenarios**:

1. **Given** a correctly configured Firebase project with a Realtime Database, **When** a new user
   completes registration, **Then** their profile record is stored under `/users/{uid}` in that same
   project's database.
2. **Given** a user with a stored profile, **When** they open the Profile tab, **Then** their saved
   values are displayed rather than an error message.
3. **Given** a signed-in user, **When** a profile read or write occurs, **Then** it is not rejected
   for permission reasons caused by a project mismatch.

---

### User Story 2 - Changing Firebase projects requires no source edit (Priority: P1)

A developer sets up SipSense against a different Firebase project — a personal project, a staging
project, or a replacement for a lost one. They place that project's `google-services.json` in
`app/` and build. The app uses that project for both sign-in and data. They never edit Kotlin
source to redirect the database.

**Why this priority**: Equal to US1 because it is what prevents the defect from recurring. The
mismatch happened precisely because one concern followed the config file and the other did not. It
also removes a project-specific identifier from a source file, per Principle VI.

**Independent Test**: Note which project the app uses. Replace `app/google-services.json` with a
different project's file, rebuild, and confirm both authentication and database traffic move to the
new project with no source changes. Verified by inspection plus a source search that finds no
database URL or project identifier literal.

**Acceptance Scenarios**:

1. **Given** a `google-services.json` for any Firebase project with a Realtime Database, **When**
   the app is built and run, **Then** authentication and database access both target that project.
2. **Given** the source tree, **When** it is searched for database URL or Firebase project
   identifier literals, **Then** none are found.

---

### User Story 3 - A misconfigured build fails understandably (Priority: P2)

A developer builds with a `google-services.json` whose project has no Realtime Database — exactly
the current state of `sipsense-17b6c`. Instead of an unexplained crash when opening a screen, they
get a clear, specific diagnostic telling them a Realtime Database is missing from the configured
project and what to do about it.

**Why this priority**: P2 because it affects developers, not end users, and only in a
misconfiguration. It is in scope because Principle III requires defined behavior for missing and
invalid data rather than an undefined one, and because resolving the URL from config introduces
this failure mode where the hardcoded literal previously masked it.

**Independent Test**: Build and run with the current `google-services.json`, which has no
`firebase_url`. Open the registration and Profile screens. The app must not crash with an
unexplained error, and the log must name the cause.

**Acceptance Scenarios**:

1. **Given** a configuration with no Realtime Database URL, **When** a screen that uses the database
   is opened, **Then** the app does not crash and the logged diagnostic states that the configured
   Firebase project has no Realtime Database.
2. **Given** that same configuration, **When** the user attempts an action requiring the database,
   **Then** they see the app's existing failure message rather than a silent no-op or a crash.

---

### Edge Cases

- **Configured project has no Realtime Database** — the present state. Covered by US3.
- **Database in a non-default region.** Regional instances use a different hostname form
  (`https://<name>.<region>.firebasedatabase.app`). Resolving from config handles this
  automatically, whereas a hardcoded default-region literal would not.
- **Config file absent entirely.** Already handled: the build fails at
  `:app:processDebugGoogleServices`, documented in README step 3. Unchanged by this feature.
- **Offline or unreachable network.** Unchanged. This feature alters which database is addressed,
  not the offline behavior of database calls. Existing behavior is preserved as-is; improving it is
  out of scope.
- **User signed in against the old project.** A previously cached session belongs to a different
  project. After this change, sign-in state comes from the configured project only; a stale session
  is not silently reused across projects.
- **Existing data in `sipsense-17a90`.** See Assumptions — no migration is in scope.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The Realtime Database connection MUST be resolved from the Firebase configuration
  bundled with the app, not from a URL written in source code.
- **FR-002**: No source file MUST contain a Realtime Database URL literal or a Firebase project
  identifier. This is verifiable by searching the source tree.
- **FR-003**: Firebase Authentication and the Realtime Database MUST resolve to the same Firebase
  project in every build configuration.
- **FR-004**: When the database connection cannot be resolved because the configured project has no
  Realtime Database, the app MUST NOT terminate unexpectedly, and MUST record a diagnostic that
  names the cause and the required corrective action.
- **FR-005**: The stored profile data shape, field names, and database path MUST remain unchanged.
  No migration and no schema change is part of this feature.
- **FR-006**: Both known call sites MUST be updated — the registration screen and the profile
  screen. No other application behavior may be altered.
- **FR-007**: Authentication behavior, screen layouts, navigation, and validation rules MUST remain
  unchanged.
- **FR-008**: Setup documentation MUST state that the configured Firebase project requires a
  Realtime Database, and MUST NOT name a specific project as the source of the configuration file
  in a way that conflicts with FR-002's intent.
- **FR-009**: The project MUST continue to build and pass lint with no new errors introduced.

### Key Entities

- **Firebase configuration** — the build-time file identifying which Firebase project the app uses,
  including its project identifier and, once a database exists, its database URL. Owned by the
  developer, never committed.
- **User profile** — the existing record at `/users/{uid}`. Owner: the authenticated user it belongs
  to. Unchanged by this feature; only the database it lives in is corrected.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A newly registered user's profile is present in the configured project's database, and
  is displayed correctly on the Profile tab, in 100% of attempts.
- **SC-002**: A search of the source tree for database URL and Firebase project identifier literals
  returns zero matches.
- **SC-003**: Replacing the Firebase configuration with a different project's file moves both
  sign-in and data to that project with zero source files modified.
- **SC-004**: With a configuration lacking a Realtime Database, no unexplained termination occurs on
  the registration or profile screens, and the diagnostic identifies the cause.
- **SC-005**: Build and lint both succeed with no new errors and no new warning categories.

## Assumptions

- **The developer's project decision is authoritative.** `sipsense-17b6c` is the correct project,
  per the explicit decision recorded above. This feature does not revisit that choice.
- **No data migration is required.** Any records in `sipsense-17a90` are treated as non-production
  development data. Migrating or preserving them is not in scope; if that assumption is wrong, this
  must be raised before implementation.
- **`/users/{uid}` security rules in the configured project permit the owning user to read and
  write their own record.** Database security rules are configured in the Firebase console, not in
  this repository, so this feature cannot set them. If they are left at a default that denies
  access, US1 will still fail for a reason outside this feature's control.
- **Failure handling reuses existing paths.** Where the database cannot be resolved, the feature
  uses the screens' existing failure messaging rather than introducing new UI, per Principle X.
- **Verification of US1 must observe the database directly**, not only the UI, because baseline
  finding R3 (profile write failures are swallowed on the registration path) is out of scope and
  still masks write failures in the interface.

## Dependencies

- **External, developer-performed, blocking full verification**: a Realtime Database must be created
  in project `sipsense-17b6c`, and `app/google-services.json` must then be re-downloaded so it
  carries the resulting `firebase_url`. The current file has no `firebase_url` key at all.
  - The code change does **not** have to wait for this, because FR-004 requires the missing-database
    case to degrade understandably. But **US1 and SC-001 cannot pass until this is done.**
- **Feature 001** supplies the working command-line build and lint used to verify FR-009.

## Out of Scope

Named explicitly so this feature does not expand:

- **R1** — the hydration target range conflict that crashes the Profile tab. Separate defect.
- **R3** — profile write failures swallowed by `printStackTrace()` on the registration path. This
  feature is verified around it, not through it. It remains a recorded finding and a strong
  candidate for the next defect feature.
- **R10** — build artifacts tracked in git.
- The 18 open questions in the baseline analysis. None are answered here.
- **Automated test infrastructure.** Still absent; see the plan's Complexity Tracking for the
  Principle VII deviation. This feature does not add the test source set.
- Any change to authentication, hydration tracking, history, device/IoT behavior, or UI layout.
- Database security rules and any other Firebase console configuration.
