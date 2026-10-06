# Feature Specification: Automated Test Infrastructure

**Feature Branch**: `003-test-infrastructure`

**Created**: 2026-09-30

**Status**: Draft

**Input**: Developer request via `/automate-process`: establish test infrastructure and cover the
database-resolution failure path introduced by Feature 002. Raised as CRITICAL finding T021 by the
Feature 002 convergence assessment.

## Context

SipSense has no automated tests. `app/src/test` and `app/src/androidTest` do not exist, and
`app/build.gradle.kts` declares no test dependency of any kind. Every feature to date has been
verified by build, lint, source inspection, and manual testing.

Constitution Principle VII states that every implemented feature "MUST have appropriate validation
and testing", that "Failure-path tests are part of the feature, not a follow-up", and that a feature
"MUST NOT be considered complete merely because the application compiles." The project is therefore
in standing violation of a MUST principle, and each feature that adds a failure path deepens it.

Feature 002 added a concrete, currently untested failure path: the Realtime Database reference is
resolved from configuration and is `null` when the configured Firebase project has no database, with
three call sites that must each degrade rather than crash. That path is the first thing this feature
covers, because it is the specific gap T021 recorded.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A developer can run the test suite (Priority: P1)

A developer runs one command and sees the project's tests execute and report pass or fail. They can
do this from the command line and from Android Studio, without installing anything beyond the JDK
the project already requires.

**Why this priority**: Nothing else in this feature is verifiable until a suite exists and runs. It
is the foundation every later test depends on, and it is what lifts the project out of "compiling is
not done."

**Independent Test**: Run the test task on a clean checkout. It must discover and execute tests and
report results, exiting non-zero if any test fails.

**Acceptance Scenarios**:

1. **Given** a checkout with the Firebase configuration in place, **When** the developer runs the
   unit test task, **Then** tests are discovered, executed, and a pass/fail summary is reported.
2. **Given** a test that fails, **When** the task runs, **Then** the task fails with a non-zero exit
   code and names the failing test.
3. **Given** the project opened in Android Studio, **When** the developer opens a test class,
   **Then** it is recognised as a test and can be run from the IDE.

---

### User Story 2 - The database-resolution failure path is covered (Priority: P1)

The behaviour Feature 002 introduced is pinned by tests: when the configured Firebase project has no
Realtime Database, resolution yields no reference and does not throw; when it does have one,
resolution yields a usable reference. A future change that turns this back into a thrown exception
fails the suite instead of reaching a user.

**Why this priority**: This is the specific obligation T021 recorded, and it is a failure path, which
Principle VII says is part of the feature rather than a follow-up. It is also the exact class of bug
that produced the cross-project incident — a misconfiguration that compiles cleanly.

**Independent Test**: Run the suite with no emulator and no Firebase project. The tests must pass
without network access, because they exercise the resolution logic rather than a live database.

**Acceptance Scenarios**:

1. **Given** database resolution cannot determine a URL, **When** a reference is requested, **Then**
   no reference is returned and no exception escapes.
2. **Given** database resolution succeeds, **When** a reference is requested, **Then** the root
   reference for the configured project is returned.
3. **Given** resolution fails in an unanticipated way, **When** a reference is requested, **Then** no
   exception escapes.

---

### User Story 3 - The stored profile shape is protected (Priority: P2)

The user profile record keeps the field defaults that the database requires in order to reconstruct a
stored profile. A change that removes them is caught by a test rather than by a user seeing an empty
profile.

**Why this priority**: P2 because it guards existing behaviour rather than covering new behaviour.
It is included because the constraint is real, invisible, and already documented in the code: the
profile model's own documentation states that "All default values are required for Firebase
deserialization." Nothing currently enforces it, and violating it degrades silently — which
Principle IV treats as a data integrity concern.

**Independent Test**: Construct a profile with no arguments and assert the documented defaults. Pure
logic, no Android framework and no network.

**Acceptance Scenarios**:

1. **Given** the profile model, **When** it is constructed with no arguments, **Then** every field
   has its documented default and the hydration target default is 2500.

---

### Edge Cases

- **Tests requiring the Android framework.** Code under test calls Android logging. The suite must
  handle that without a device, or the failure-path test cannot run at all.
- **Tests requiring a device or emulator.** Out of scope. This feature establishes local unit tests
  only; instrumented tests are a later decision.
- **Tests requiring a live Firebase project or network.** Must not be needed. Tests that depend on a
  provisioned database would be unrunnable on a clean checkout, which defeats US1.
- **The Firebase configuration file.** A developer without it cannot build at all, so it remains a
  prerequisite for running tests. This feature does not change that.
- **Tests requiring UI interaction.** Out of scope, and excluded by the Manual Testing Boundary.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The project MUST provide a local unit test source set that the build discovers and
  executes.
- **FR-002**: A single documented command MUST run the test suite from the command line, and MUST
  fail with a non-zero exit code when any test fails.
- **FR-003**: The suite MUST run without a device, without an emulator, without network access, and
  without a provisioned Firebase database.
- **FR-004**: The database-resolution failure path MUST be covered: no reference returned and no
  exception escaping when the URL cannot be determined.
- **FR-005**: The database-resolution success path MUST be covered, so the failure test cannot pass
  trivially by the resolution being broken in all cases.
- **FR-006**: The profile model's documented field defaults MUST be covered.
- **FR-007**: Every dependency added MUST carry a stated reason in the build file, matching the
  existing convention of commented dependency declarations.
- **FR-008**: No application source file under `app/src/main/` may be modified. This feature adds
  tests around existing behaviour; it does not change behaviour.
- **FR-009**: Existing build and lint results MUST NOT regress: the build still succeeds and lint
  reports no errors.
- **FR-010**: Setup documentation MUST state how to run the tests.

### Key Entities

- **Test suite** — the collection of automated checks for the `:app` module, owned by the
  repository, runnable by any developer with the project's existing prerequisites.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: One documented command runs the suite and reports results, on a machine with no
  emulator running and no network access.
- **SC-002**: The suite contains at least one test that fails if the database-resolution failure path
  regresses to throwing, and at least one covering the success path.
- **SC-003**: Deliberately breaking any covered behaviour causes the suite to fail, proving the tests
  are load-bearing rather than vacuous.
- **SC-004**: All tests pass on the current codebase.
- **SC-005**: Build and lint results are no worse than before: build succeeds, lint reports zero
  errors.
- **SC-006**: The number of Firebase projects, credentials, or manual steps required to run the suite
  is zero.

## Assumptions

- **Local unit tests are the right first step.** They run fastest, need no device, and cover the
  logic T021 named. Instrumented and UI tests are deliberately deferred rather than judged
  unnecessary.
- **The existing dependency declaration style is the convention to follow.** The project declares
  dependencies as literal coordinate strings with explanatory comments and has no version catalog, so
  this feature matches that rather than introducing a catalog, per Principle VIII.
- **Testing the two screens' internal logic is not yet practical.** Validation rules and hydration
  logic live inside an Activity and a Fragment, which the constitution flags as a baseline problem.
  Extracting them is a refactor with its own risk and belongs to its own feature, so this feature
  tests what is already isolated.
- **This feature does not retroactively test Features 001 and 002 in full.** It establishes the
  infrastructure and covers the failure path T021 named. Broader coverage follows as behaviour is
  extracted into testable units.

## Dependencies

- **Feature 001** supplies the working command-line build used to run the suite.
- **Feature 002** supplies the database-resolution behaviour under test.
- `app/google-services.json` remains a build prerequisite, unchanged by this feature.

## Out of Scope

- **Instrumented and UI tests**, and any test needing a device or emulator.
- **Continuous integration.** Running the suite automatically on push is a separate decision.
- **Coverage thresholds or enforcement gates.**
- **Refactoring application code to make it testable**, including extracting validation logic out of
  `RegisterActivity` or hydration logic out of `ProfileFragment`. FR-008 forbids touching
  `app/src/main/` here.
- **Baseline findings R1, R3, and R10**, and the 18 baseline open questions. In particular this
  feature does not fix R3 — it may, however, make its consequences easier to test later.
- Any change to authentication, hydration tracking, history, device/IoT behaviour, or UI.
