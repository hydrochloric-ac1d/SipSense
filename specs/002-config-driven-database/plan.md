# Implementation Plan: Config-Driven Database Connection

**Branch**: `002-config-driven-database` | **Date**: 2026-09-30 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/002-config-driven-database/spec.md`

## Summary

Remove the two hardcoded `sipsense-17a90` Realtime Database URL literals so the database instance is
resolved from the bundled `google-services.json`, making Firebase Authentication and the Realtime
Database always target the same project. Because the configured project currently has **no**
`firebase_url`, the no-argument resolution throws rather than returning a usable handle, so the
resolution is centralised behind a single small provider that returns a nullable reference and logs
an actionable diagnostic. The three existing call sites handle the null case using the failure
messages they already show.

## Technical Context

**Language/Version**: Kotlin, JVM target 17

**Primary Dependencies**: Firebase BoM 33.6.0 — `firebase-auth` 23.1.0, `firebase-database` 21.0.0;
`com.google.gms.google-services` 4.4.2. **No new dependency is added.**

**Storage**: Firebase Realtime Database, existing path `/users/{uid}`, unchanged

**Testing**: None available. No test source set and no test dependencies exist — see Complexity
Tracking. Verification is build, lint, source search, and the manual scenarios in
[quickstart.md](quickstart.md).

**Target Platform**: Android, `minSdk` 26, `compileSdk`/`targetSdk` 35

**Project Type**: Mobile app, single Gradle module `:app`, Views + XML UI

**Performance Goals**: Not applicable. No change to request volume, payload, or frequency.

**Constraints**: Must not alter authentication, UI, navigation, validation, or the stored data shape
(FR-005, FR-007). Must not introduce an unexplained termination when the database cannot be
resolved (FR-004).

**Scale/Scope**: 2 files modified, 1 file added, 3 call sites, ~25 net lines.

## Constitution Check

*GATE: evaluated before Phase 0 and re-checked after design.*

| # | Principle | Status | Notes |
| --- | --- | --- | --- |
| I | Business-Process Authority | **PASS** | Adds no feature and invents no business rule. Which project is authoritative was decided by the developer, not assumed. Three judgement calls are recorded as Assumptions in the spec rather than silently resolved. |
| II | Android/Kotlin First | **PASS** | Kotlin only. Moves database-connection concern *out* of an Activity and a Fragment into a dedicated data-layer file, which is the direction Principle II requires. See Complexity Tracking for the new package. |
| III | IoT Reliability | **PASS** | No device interaction. The analogous requirement — never assume the backend is reachable or configured — is honoured by FR-004: the missing-database case is a defined, logged, non-fatal path rather than an undefined one. |
| IV | Data Integrity | **PASS** | Directly serves it. Today profile writes go to a project the user is not authenticated against, so they are rejected; this is the fix. No duplicate records are possible — the write is a `setValue` at a uid-keyed path, which is idempotent. Data ownership is unchanged: a user owns `/users/{uid}`. |
| V | Offline-Aware Design | **PASS** | Untouched. The SDK's offline queuing behaviour is a property of the database handle, not of how the handle is obtained. Noted as an explicit non-goal in the spec's Edge Cases. |
| VI | Security and Privacy | **PASS** | Improves it. Removes a project-specific database identifier from committed source (FR-002), leaving the only project identity in the gitignored config file. No credential is read, logged, or added. The diagnostic in FR-004 must name the *problem*, never config contents. |
| VII | Testing | **DEVIATION** | No automated test is added because no test infrastructure exists. Justified in Complexity Tracking, consistent with Feature 001's precedent. Failure paths are covered by quickstart Scenario 3 and 4 rather than by tests. |
| VIII | Maintainability | **PASS** | Removes a duplicated literal, replacing two copies with one resolution point. Net simplification in behaviour terms despite one added file. No new dependency. |
| IX | Spec-Driven Development | **PASS** | spec → plan → tasks → implement → converge. This feature exists because verification of Feature 001 surfaced the defect; it was specified before any source edit. |
| X | Change Control | **PASS** | Touches only the two files holding the defect plus one new file. Reuses each call site's existing failure message instead of inventing UI. Baseline findings R1, R3, and R10 are left alone and named Out of Scope. |
| XI | Definition of Done | **PARTIAL by design** | Build, lint, and source-search criteria are agent-verifiable. SC-001 cannot pass until the developer creates the Realtime Database — an external dependency recorded in the spec, not a defect in this plan. |

**Gate result**: PASS with one justified deviation (VII) and one declared external dependency.

## Project Structure

### Documentation (this feature)

```text
specs/002-config-driven-database/
├── spec.md                     # Feature specification
├── plan.md                     # This file
├── research.md                 # Phase 0 decisions
├── quickstart.md               # Verification scenarios
├── tasks.md                    # Phase 2 output (/speckit-tasks)
└── checklists/
    └── requirements.md         # Spec quality checklist
```

No `data-model.md` and no `contracts/`: FR-005 forbids any change to the data shape, and the feature
introduces no new interface between components beyond one internal provider. Creating either file
would document a change that is deliberately not happening.

### Source Code (repository root)

```text
app/src/main/java/com/sipsense/app/
├── RegisterActivity.kt         # MODIFY - drop URL literal, handle unresolved reference
├── ProfileFragment.kt          # MODIFY - drop URL literal, handle unresolved reference
├── LoginActivity.kt            # UNCHANGED - uses Auth only, no database handle
├── MainActivity.kt             # UNCHANGED
├── DashboardFragment.kt        # UNCHANGED
├── HistoryFragment.kt          # UNCHANGED
├── DevicesFragment.kt          # UNCHANGED
├── data/
│   └── FirebaseDatabaseProvider.kt   # ADD - single resolution point
└── model/
    └── UserProfile.kt          # UNCHANGED - FR-005

README.md                       # MODIFY - database prerequisite, correct project guidance
```

**Structure Decision**: Keep the existing flat `com.sipsense.app` layout for screens, which is the
architectural baseline, and add one `data/` subpackage alongside the existing `model/` subpackage for
the provider. A source search established that only `RegisterActivity.kt` and `ProfileFragment.kt`
obtain a database handle, so the change surface is closed — no other screen needs updating.

## Phase 0 — Research

See [research.md](research.md). Four decisions: the resolution mechanism, the failure contract, where
the provider lives, and how FR-002 is verified.

## Phase 1 — Design

**The provider.** One `object` exposing a single function that returns `DatabaseReference?`:
`null` when the configured project has no database URL. It catches the SDK's resolution failure,
logs once with an actionable message, and caches nothing that would hide a later-fixed config
(the SDK already caches the instance per app).

**Why nullable rather than throwing or returning a dummy.** Nullable forces each call site to make
its failure visible, which is what FR-004 requires. Throwing would propagate to `ProfileFragment`'s
property initialiser and crash the tab before any handler could run. A dummy reference would fail
silently later, which is the exact class of bug this feature exists to remove.

**Call-site handling**, each reusing the message already present:

| Site | Current | On unresolved reference |
| --- | --- | --- |
| `ProfileFragment.loadUserProfile()` | shows "Failed to load profile" in `onCancelled` | show the same message, return early |
| `ProfileFragment.saveHydrationTarget()` | shows "Failed to update target" on failure | show the same message, return early |
| `RegisterActivity` profile write | `printStackTrace()` on failure, then navigates | log the diagnostic, then navigate as before |

`RegisterActivity.database` changes from `lateinit var DatabaseReference` to
`var DatabaseReference?`, because the value is now legitimately absent — `lateinit` would throw on
access and reintroduce the crash this design avoids.

**Deliberate non-change.** `RegisterActivity` still navigates to Login after a failed write. That is
baseline finding R3 and is Out of Scope; altering it would change registration behaviour, which
FR-007 forbids. The spec therefore requires US1 to be verified against the database directly.

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
| --- | --- | --- |
| **Principle VII** — no automated test | The project has no test source set, no test dependencies, and no CI. Adding them is a substantial change that the workflow document explicitly assigns to a dedicated feature: "The first feature that needs unit tests must add the test dependencies and source set as part of its plan, not as an unplanned side change." Bundling it here would enlarge a targeted defect fix into infrastructure work and violate Principle X. | Writing a test now would require adding the test source set, JUnit, and a Robolectric or instrumentation harness capable of initialising `FirebaseApp` — the heaviest possible way to assert a URL is absent from source. FR-002 is instead verified by a deterministic source search (quickstart Scenario 2), which is stronger than a unit test for that specific claim. Test infrastructure remains the recommended next feature. |
| **New `data/` subpackage** | Principle II requires data access to be separated from presentation and forbids business logic in Activities and Fragments. The provider is data-layer concern; putting it in either screen would recreate the duplication being removed. | A flat `com/sipsense/app/FirebaseDatabaseProvider.kt` was considered and rejected as marginally simpler but directionally wrong: it would place a data-layer file in the presentation package the constitution asks to keep separate. The addition is purely additive — no existing file is moved, so it establishes a direction without forcing a migration. |
| **Widening `RegisterActivity.database` to nullable** | The reference is genuinely absent when the configured project has no database. Modelling that honestly is what makes FR-004 satisfiable. | Keeping `lateinit` and guarding with `::database.isInitialized` was rejected: it encodes the same nullability less legibly, and any missed access path throws `UninitializedPropertyAccessException` — an unexplained termination, which FR-004 forbids. |
