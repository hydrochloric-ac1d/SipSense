# Implementation Plan: Automated Test Infrastructure

**Branch**: `003-test-infrastructure` | **Date**: 2026-09-30 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/003-test-infrastructure/spec.md`

## Summary

Add a local unit test source set to the `:app` module with JUnit 4 and MockK, and cover three things:
the database-resolution failure path added by Feature 002, its success path, and the profile model's
documented defaults. Resolution is a static SDK call, so the tests stub it rather than reaching a real
Firebase project, which keeps the suite runnable with no device, no network, and no provisioned
database. One Android-specific obstacle drives a build setting: the code under test logs through
`android.util.Log`, which is unimplemented in local unit tests, so the module enables default return
values instead of taking on Robolectric.

## Technical Context

**Language/Version**: Kotlin, JVM target 17

**Primary Dependencies added**: `junit:junit:4.13.2`, `io.mockk:mockk:1.13.13` — both
`testImplementation` only, so neither ships in the APK.

**Storage**: Not applicable. No test touches a real database.

**Testing**: JUnit 4 local unit tests in `app/src/test/java`, run by `:app:testDebugUnitTest`.

**Target Platform**: JVM (local unit tests), for an Android library/app module with `minSdk` 26

**Project Type**: Mobile app, single Gradle module `:app`

**Performance Goals**: The suite must stay fast enough to run on every change — a few seconds. This
is why local unit tests are chosen over instrumented tests.

**Constraints**: No device, no emulator, no network, no provisioned Firebase database (FR-003). No
modification to `app/src/main/` (FR-008).

**Scale/Scope**: 1 build file modified, 1 README section, 2 test files added, 3 behaviours covered.

## Constitution Check

*GATE: evaluated before Phase 0 and re-checked after design.*

| # | Principle | Status | Notes |
| --- | --- | --- | --- |
| I | Business-Process Authority | **PASS** | Adds no product behaviour and invents no business rule. Tests assert only what the code and its existing documentation already claim. |
| II | Android/Kotlin First | **PASS** | Kotlin tests for a Kotlin codebase, using the standard Android module test source set. No new architecture. |
| III | IoT Reliability | **PASS** | No device interaction. Directly serves the spirit of the principle by making a backend-unavailable path a tested path. Device-level IoT failure testing needs instrumented tests and is deferred, not dismissed. |
| IV | Data Integrity | **PASS** | US3 pins the profile defaults that Firebase deserialization depends on, an integrity constraint currently protected by nothing but a comment. |
| V | Offline-Aware Design | **PASS** | The suite itself requires no network (FR-003), which is what makes it runnable anywhere. |
| VI | Security and Privacy | **PASS** | No credential, key, or real project is used. Tests stub the SDK rather than authenticating. Nothing sensitive enters the repository. |
| VII | Testing | **PASS — this feature exists to satisfy it** | Establishes the missing infrastructure and covers a failure path, addressing Feature 002's CRITICAL finding T021. Coverage remains partial by design; see Complexity Tracking. |
| VIII | Maintainability | **PASS with justification** | Two dependencies added, each with a stated reason in the build file per FR-007. Follows the existing commented-literal declaration style instead of introducing a version catalog. |
| IX | Spec-Driven Development | **PASS** | Specified before implementation; traceable to T021. |
| X | Change Control | **PASS** | No application source is modified (FR-008). Only the build file, the README, and new test files are touched. |
| XI | Definition of Done | **PASS** | Every success criterion is agent-verifiable. Uniquely among the three features so far, this one has no external or manual blocker. |

**Gate result**: PASS. No principle is violated; one is being repaid.

## Project Structure

### Documentation (this feature)

```text
specs/003-test-infrastructure/
├── spec.md
├── plan.md                     # This file
├── research.md                 # Phase 0 decisions
├── quickstart.md               # Verification scenarios
├── tasks.md                    # Phase 2 output
└── checklists/
    └── requirements.md
```

No `data-model.md` and no `contracts/`: this feature adds no entity and no interface.

### Source Code (repository root)

```text
app/
├── build.gradle.kts            # MODIFY - test source set deps + unit test options
└── src/
    ├── main/                   # UNCHANGED - FR-008 forbids edits here
    └── test/                   # ADD
        └── java/com/sipsense/app/
            ├── data/
            │   └── FirebaseDatabaseProviderTest.kt   # ADD - US1, US2
            └── model/
                └── UserProfileTest.kt                # ADD - US3

README.md                       # MODIFY - how to run the tests
```

**Structure Decision**: Use AGP's conventional `app/src/test/java` source set, mirroring the main
source tree's package layout so each test sits in the package of the code it covers. No custom source
set, no new module — the standard layout is what Android Studio and the Gradle task already expect,
so it needs no configuration beyond dependencies.

## Phase 0 — Research

See [research.md](research.md). Four decisions: test framework, how to stub a static SDK call, how to
survive `android.util.Log` in a JVM test, and what is worth covering first.

## Phase 1 — Design

**Build changes**, in `app/build.gradle.kts`:

1. A `testOptions` block inside `android { }` enabling `unitTests.isReturnDefaultValues = true`.
   Without it, any call into `android.util.Log` throws "not mocked" and the failure-path test cannot
   run at all — the provider logs inside the exact `catch` block under test.
2. Two `testImplementation` dependencies, each with a comment stating why, matching the file's
   existing style.

**`FirebaseDatabaseProviderTest`** — covers FR-004, FR-005, and the three US2 scenarios by stubbing
the static `FirebaseDatabase.getInstance()`:

| Test | Stub behaviour | Asserts |
| --- | --- | --- |
| failure path | throws `DatabaseException` | returns `null`, does not throw |
| success path | returns a mock whose `reference` is a known value | returns that same reference |
| unexpected failure | throws `IllegalStateException` | returns `null`, does not throw |

The third case matters because the provider catches broad `Exception`; without it, a future narrowing
to `catch (e: DatabaseException)` would pass the suite while reintroducing a crash.

**`UserProfileTest`** — covers FR-006 and US3 by constructing the model with no arguments and
asserting the documented defaults, including `hydrationTarget == 2500`.

**Static stubbing is scoped per test**, unstubbed afterwards, so one test cannot leak a stubbed static
into another and produce order-dependent results.

## Complexity Tracking

| Addition | Why Needed | Simpler Alternative Rejected Because |
| --- | --- | --- |
| **`io.mockk:mockk`** | `FirebaseDatabase.getInstance()` is a static SDK call with no injection seam, and FR-008 forbids modifying `app/src/main/` to add one. MockK's `mockkStatic` stubs it from the test side, which is the only way to reach the failure branch without changing production code. | Hand-written fakes cannot intercept a static call. Mockito requires `mockito-inline` plus more ceremony for Kotlin objects and final classes. Refactoring the provider for injection would violate FR-008 and mean changing production code to suit a test on the very feature being covered. |
| **`junit:junit:4.13.2`** | The framework AGP's unit test task expects by default; needs no extra plugin or runner configuration. | JUnit 5 requires an additional plugin and platform runner on Android and buys nothing this feature needs. |
| **`unitTests.isReturnDefaultValues = true`** | The provider calls `Log.e` inside the `catch` block under test. In local unit tests the Android framework is a stub jar that throws on every call, so without this setting the failure-path test fails on the logging call rather than on the behaviour. | Robolectric would also solve it, at the cost of a much heavier dependency and slower tests, for a project whose only current need is a no-op logger. Wrapping the logger to make it injectable would require editing `app/src/main/`, which FR-008 forbids. |
| **Coverage is narrow** — 3 behaviours, not the whole app | Principle VII is satisfied incrementally: this feature builds the capability and pays down the specific debt T021 named. Most remaining logic sits inside an Activity and a Fragment and is not reachable by a local unit test without a refactor. | Testing everything now would require extracting validation and hydration logic out of the two screens — a refactor with real regression risk, explicitly Out of Scope, and properly its own feature with its own spec. Claiming broad coverage by writing shallow tests would be worse than admitting the boundary. |
