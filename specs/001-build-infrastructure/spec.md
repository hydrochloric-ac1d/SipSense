# Feature Specification: Build Infrastructure Baseline

**Feature Branch**: `001-build-infrastructure`

**Created**: 2026-09-30

**Status**: Draft

**Input**: User description: "Restore Gradle wrapper and document Firebase configuration so the project builds and verifies from a clean clone"

## Context

`.specify/memory/baseline-analysis.md` records two build blockers, B1 and B2, and notes that
Principle XI (Definition of Done) is unreachable while they stand: no feature can claim
"relevant tests pass" when no automated check can run at all. This feature closes both.

B2 was confirmed by a real build on 2026-09-30, which failed at
`:app:processDebugGoogleServices` with "File google-services.json is missing."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A new developer can build from a clean clone (Priority: P1)

A developer clones SipSense onto a machine that has a supported JDK but no pre-installed
build tooling. Following the documented setup steps alone, they reach a successful debug
build. Nothing in the process requires guessing, and nothing requires an IDE.

**Why this priority**: This is the entry point for every other contribution. Today the
README's build instructions cannot be completed on a clean clone, because the build tooling
the project pins is absent from the repository and a required configuration file is
undocumented.

**Independent Test**: Follow the documented steps in a fresh checkout and confirm a debug
build completes. Delivers a working development environment on its own.

**Acceptance Scenarios**:

1. **Given** a clean clone and a supported JDK, **When** the developer runs the documented
   build command, **Then** the pinned build tooling is obtained automatically and the build
   proceeds without any separate tooling installation.
2. **Given** a clean clone, **When** the developer reads the setup documentation, **Then** the
   required Firebase configuration file is named, its source is identified, its exact location
   is given, and the instruction not to commit it is explicit.
3. **Given** the Firebase configuration file has been placed as documented, **When** the
   developer runs the build, **Then** the build succeeds.

---

### User Story 2 - Automated verification can run outside the IDE (Priority: P1)

An automated agent or CI runner executes build and static analysis from the command line, with
no IDE present, and gets a real pass or fail result.

**Why this priority**: `/automate-process` requires build verification, lint, and tests before
any feature may be reported as done. All three are currently impossible outside Android
Studio, so the workflow's automated verification stage cannot execute.

**Independent Test**: Invoke the build and lint entry points from a shell in a checkout with no
IDE involvement and confirm both produce results.

**Acceptance Scenarios**:

1. **Given** a checkout with the Firebase configuration present, **When** build and lint are
   invoked from a command line, **Then** each runs to completion and reports pass or fail.
2. **Given** the same checkout, **When** the build tooling is invoked, **Then** it uses the
   version already pinned by the project rather than whatever version happens to be installed.

---

### User Story 3 - A missing Firebase configuration fails understandably (Priority: P2)

A developer who has not yet obtained the Firebase configuration file runs the build, sees it
fail, and can resolve it from the project's own documentation without external help.

**Why this priority**: The failure is unavoidable by design — the file is deliberately
untracked for security reasons (Principle VI). What is avoidable is the developer not knowing
why, which is what happened on 2026-09-30.

**Independent Test**: Remove the configuration file, run the build, and confirm the resulting
failure is described in the setup documentation together with its remedy.

**Acceptance Scenarios**:

1. **Given** the Firebase configuration file is absent, **When** the build fails, **Then** the
   setup documentation names that failure and states exactly how to resolve it.
2. **Given** a developer without access to the Firebase project, **When** they consult the
   setup documentation, **Then** it tells them to request access rather than to work around the
   requirement.

---

### Edge Cases

- **Configuration file absent**: the build must still fail. This feature makes the failure
  documented and diagnosable; it does not suppress it or substitute placeholder values.
- **Developer lacks Firebase project access**: documentation directs them to request access and
  to receive the file out of band, never through version control.
- **Tooling version drift**: the restored tooling must match the version the project already
  pins. A mismatch between the pinned version and the restored tooling is a defect.
- **Unsupported JDK**: already covered by existing README guidance; this feature must not
  regress it.
- **Non-Windows checkout**: the build entry point must work on Unix-like systems as well, so
  line endings and the executable bit matter for the committed scripts.
- **Configuration file accidentally staged**: the ignore rule must continue to prevent it.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The repository MUST contain the complete, version-controlled build entry point so
  that a build can be started on a clean clone without separately installing build tooling.
- **FR-002**: The restored build tooling version MUST match the version already pinned by the
  project. This feature MUST NOT change the pinned version, the Android Gradle Plugin version,
  or the Kotlin version.
- **FR-003**: The build entry-point files MUST be tracked in version control and MUST NOT be
  excluded by ignore rules.
- **FR-004**: Setup documentation MUST state that the Firebase configuration file is required,
  where a developer obtains it, the exact path it belongs at, and that it must never be
  committed.
- **FR-005**: Setup documentation MUST name the observable symptom of the missing configuration
  file — the failing build task and its message — so a developer who hits it can match the
  failure to the remedy.
- **FR-006**: The application's runtime behavior, dependencies, permissions, and architecture
  MUST NOT change. No source file under `app/src/` may be modified by this feature.
- **FR-007**: The existing Android Studio build path MUST continue to work unchanged.
- **FR-008**: The Firebase configuration file MUST remain untracked, satisfying Principle VI.
- **FR-009**: The build tooling MUST be obtained from its official distribution. A build
  artifact MUST NOT be copied from an unverified third-party source.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A developer with a supported JDK and the Firebase configuration file can go from
  clone to successful debug build using only the documented steps, with zero undocumented
  actions required.
- **SC-002**: Build and static analysis can each be started from a command line in a checkout
  with no IDE installed.
- **SC-003**: A developer who hits the missing-configuration failure can find the cause and
  remedy in the project's own documentation without consulting another person or an external
  search.
- **SC-004**: Application behavior is unchanged: the login, registration, and profile flows
  behave exactly as they did before this feature.
- **SC-005**: A verification run can report a genuine pass or fail for build and lint, making
  Principle XI satisfiable for subsequent features.

## Assumptions

- The pinned Gradle version stays as already declared, because it was chosen for compatibility
  with the project's Android Gradle Plugin version. Upgrading is out of scope.
- A supported JDK (17 or 21) is available on the developer's machine. The README already
  documents this, and Android Studio's bundled runtime satisfies it.
- The Firebase configuration file remains deliberately untracked per Principle VI. Developers
  obtain it from the Firebase console for the existing project, or from a teammate out of band.
- The developer performing this work has access to the Firebase project. Confirmed 2026-09-30.
- Committing the build entry point is standard practice for this build system and introduces no
  new dependency — the project already declares the tooling version it expects.

## Out of Scope

Named explicitly so Principle X (Change Control) is enforceable. Each item below is a separate
future feature and MUST NOT be touched here.

- Adding test sources or test dependencies. Baseline "Missing" item 8 — the next feature.
- Creating a CI pipeline or workflow definition.
- Fixing any finding in the baseline analysis: R1 (hydration target range crash), R2 (hardcoded
  database URL), R3 through R10.
- Removing tracked build artifacts from git history (baseline R10).
- Answering any of the 18 open questions blocked on missing requirements.
- Upgrading Gradle, the Android Gradle Plugin, Kotlin, or any dependency.
- Enabling or configuring additional static analysis beyond what the Android Gradle Plugin
  already provides.
