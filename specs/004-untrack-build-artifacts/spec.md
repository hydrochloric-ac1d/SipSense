# Feature Specification: Untrack Generated Build Artifacts

**Feature Branch**: `004-untrack-build-artifacts`

**Created**: 2026-09-30

**Status**: Draft

**Input**: Developer request via `/automate-process`: treat baseline finding **R10** / Feature 002
task **T022** as a separate repository-hygiene change. Determine which generated files are tracked,
inspect `.gitignore`, decide whether `app/build/` and `.gradle/` should be untracked, and verify the
result. Explicit constraints: do not delete required source files, do not modify application
behaviour, do not rotate or revoke Firebase credentials, do not rewrite Git history, do not commit.

## Context

The repository tracks its own build output. Measured at the start of this feature:

| Category | Tracked files |
| --- | --- |
| `app/build/` — generated build output | 479 |
| `.gradle/` — Gradle's project-local cache and lock files | 17 |
| **Generated total** | **496** |
| Real source, configuration, and documentation | 81 |
| **Repository total** | **577** |

**86% of the tracked repository is generated output.** A further 19 tracked files are IDE and
machine-local settings (`.idea/`, `.vs/`, `local.properties`) — related but a separate category, and
outside this feature's scope.

`.gitignore` **already** contains the correct rules: `build/` at line 19 and `.gradle/` at line 18.
Confirmed with `git check-ignore --no-index`, both patterns match the tracked paths. Git ignore rules
do not apply to files already in the index, so the rules have been silently ineffective — these files
were committed before the rules existed, or were force-added. **This feature therefore changes no
ignore rule; it removes files from tracking so the existing rules can take effect.**

Three concrete harms are already observed rather than hypothetical:

1. **A Firebase API key is committed.** `app/build/intermediates/packaged_res/debug/packageDebugResources/values/values.xml`
   is tracked and contains 2 API-key-shaped (`AIza…`) strings, at `HEAD` as well as in the worktree.
   The key originates from `google-services.json`, which is correctly ignored — so an ignored secret
   is reaching the repository through generated output that is not ignored.
2. **Verification signals are unreliable.** `.gradle/8.9/executionHistory/executionHistory.lock` and
   `.gradle/buildOutputCleanup/buildOutputCleanup.lock` are tracked. During earlier verification a
   Gradle invocation printed `BUILD SUCCESSFUL` and then exited **non-zero** on
   `Failed to release lock on execution history cache`.
3. **Review is obscured.** A four-file change is buried under several hundred lines of artifact churn
   in `git status` and `git diff`.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A developer sees only their own changes (Priority: P1)

A developer changes a few source files, runs a build, and then runs `git status`. They see only the
files they edited. Build output does not appear, because Git no longer tracks it.

**Why this priority**: This is the daily cost of the defect and the reason the other harms went
unnoticed for so long. It also directly protects review quality — a reviewer who cannot see the real
diff cannot review it.

**Independent Test**: Run a full build, then `git status`. No path under `app/build/` or `.gradle/`
appears, and the count of reported changes matches the files actually edited.

**Acceptance Scenarios**:

1. **Given** the repository after this change, **When** a build is run and `git status` inspected,
   **Then** no `app/build/` or `.gradle/` path is listed as modified, added, or deleted.
2. **Given** a developer edits one source file, **When** they inspect `git status`, **Then** that file
   is the only change reported.

---

### User Story 2 - Generated output stops carrying configuration into the repository (Priority: P1)

Values from `google-services.json` — the API key, the database URL, the project id — stop entering
the repository through generated build output. The ignore rule protecting the configuration file is
no longer bypassed by the build.

**Why this priority**: Equal to US1 because it is the security consequence. Constitution Principle VI
requires that keys not be exposed in source, and the current state contradicts it.

**Independent Test**: After the change, confirm no tracked file contains an API-key-shaped string or
the database URL, and that the configuration file itself is still ignored.

**Acceptance Scenarios**:

1. **Given** the repository after this change, **When** tracked files are searched for API-key-shaped
   strings and the database URL, **Then** none is found in the working tree's tracked set.
2. **Given** a rebuild after the change, **When** `git status` is inspected, **Then** newly generated
   resources containing configuration values are untracked and ignored.

---

### User Story 3 - The build and tests are unaffected (Priority: P1)

Untracking changes only what Git records. The build output stays on disk, the application behaves
identically, and the build, lint, and test tasks all still succeed.

**Why this priority**: P1 because it is the safety property. A hygiene change that breaks the build
is a net loss, and the distinction between untracking and deleting is exactly where this kind of
change goes wrong.

**Independent Test**: Run build, lint, and the test suite after the change and compare against the
recorded pre-change results.

**Acceptance Scenarios**:

1. **Given** the change is applied, **When** `:app:assembleDebug`, `:app:lintDebug`, and
   `:app:testDebugUnitTest` are run, **Then** all three succeed with results no worse than before.
2. **Given** the change is applied, **When** the source tree is inspected, **Then** no file under
   `app/src/`, no Gradle build script, and no wrapper file has been modified or removed.

---

### Edge Cases

- **Untracking must not delete.** The distinction between removing from the index and deleting from
  disk is the central risk. Files must remain on disk so the build is not forced to start cold.
- **Files already staged by earlier features.** The wrapper files and `.gitattributes` from Feature
  001 are staged. This change must add to the index without disturbing them.
- **Locked files during the operation.** Gradle may hold handles on `.gradle/` lock files. The index
  operation must not be attempted while a build is running.
- **A teammate pulling this change.** Once committed, others will see the artifacts deleted from the
  repository; their local copies are unaffected because the files are ignored afterwards. Worth
  documenting, not worth blocking on.
- **Git history still contains the artifacts and the key.** Untracking stops future exposure; it does
  not retroactively remove past exposure. Explicitly out of scope — see Out of Scope.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: All tracked files under `app/build/` MUST be removed from Git tracking.
- **FR-002**: All tracked files under `.gradle/` MUST be removed from Git tracking.
- **FR-003**: Every file removed from tracking MUST remain present on disk and unmodified in content.
- **FR-004**: `.gitignore` MUST result in these paths being ignored after untracking. Existing rules
  already achieve this, so no rule may be added merely to appear thorough.
- **FR-005**: No file under `app/src/`, no Gradle build script, no Gradle wrapper file, and no
  documentation file may be modified or removed.
- **FR-006**: `app/google-services.json` MUST remain untracked and ignored.
- **FR-007**: `:app:assembleDebug`, `:app:lintDebug`, and `:app:testDebugUnitTest` MUST all still
  succeed, with lint reporting no new errors and the test suite reporting no new failures.
- **FR-008**: After the change, `git status` MUST report no path under `app/build/` or `.gradle/`.
- **FR-009**: Nothing may be committed. The change is left staged for the developer to review.
- **FR-010**: Any API-key-shaped value or Firebase configuration value remaining in **committed
  history** MUST be reported, not remediated.

### Key Entities

- **Tracked generated artifact** — a file produced by the build that Git records. Owned by the build
  tool, not by a developer; reproducible from source, so it has no reason to be in version control.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: The count of tracked files under `app/build/` and `.gradle/` is **zero**, down from 479
  and 17.
- **SC-002**: The tracked file count falls from 577 to approximately 81, with every remaining entry
  being genuine source, configuration, or documentation.
- **SC-003**: `git status` after a full build reports no generated path.
- **SC-004**: No tracked file in the working tree contains an API-key-shaped string or the Realtime
  Database URL.
- **SC-005**: Build, lint, and unit tests all succeed, matching the pre-change baseline of 41 lint
  warnings / 0 errors and 6 passing tests.
- **SC-006**: Zero files are deleted from disk as a result of this change.

## Assumptions

- **Generated output has no archival value.** `app/build/` and `.gradle/` are reproducible from
  source by running the build, so removing them from tracking loses nothing. This is standard Android
  practice and is what the project's own `.gitignore` already asserts.
- **The existing ignore rules are correct as written.** `build/` and `.gradle/` already match. The
  defect is tracking, not the rules, so the rules are left untouched.
- **Leaving the change staged is the right handoff.** FR-009 forbids committing, and untracking is an
  index operation, so the natural stopping point is a staged change the developer reviews and commits.
- **The Firebase API key is not treated as an emergency.** A Firebase Android API key ships inside
  every APK by design and is guarded by database security rules and App Check rather than secrecy. It
  still must not be in the repository per Principle VI, but rotation is a developer decision and is
  explicitly excluded.

## Dependencies

- **Feature 001** supplies the working command-line build used for verification.
- **Feature 003** supplies the test suite used for verification.
- No external or manual action is required to complete this feature.

## Out of Scope

- **Rewriting Git history.** Explicitly forbidden by the request. The artifacts and the API key remain
  in past commits; FR-010 requires reporting this, not fixing it.
- **Rotating or revoking the Firebase API key.** Explicitly forbidden; a Console action and a
  developer decision.
- **Committing the change.** Explicitly forbidden by FR-009.
- **Untracking `.idea/`, `.vs/`, and `local.properties`.** These 19 files are also tracked despite
  matching ignore rules, but they are IDE and machine-local settings rather than build output, and the
  request scoped this change to `app/build/` and `.gradle/`. Reported separately for a decision.
- **Any change to application behaviour, architecture, dependencies, or Firebase rules.**
- **Baseline findings R1 and R3**, and the 18 baseline open questions.
- **Feature 002's outstanding manual verification** (T017, T019, T020). Unrelated to this change.
