# Feature Specification: Cover the Unresolved-Database Call Sites

**Feature Branch**: `005-cover-unresolved-database`
**Created**: 2026-09-30
**Status**: Draft
**Input**: Feature 003 convergence task T019 — the user-visible half of Feature 002's failure
path is untested, because the call sites live in an Activity and a Fragment.

---

## Why This Feature Exists

Feature 002 made the app degrade understandably when the configured Firebase project has no
Realtime Database: instead of crashing, `FirebaseDatabaseProvider.reference()` returns `null`
and each of three call sites informs the user and stops. Feature 003 then covered the provider
itself thoroughly, including the broad-catch behaviour, and proved that coverage load-bearing
through mutation testing.

**What is still untested is the half a user would actually see.** Nothing verifies that
`ProfileFragment` shows its message and returns instead of dereferencing `null`, or that
`RegisterActivity` records the problem and still completes navigation. A regression in those
three guards would reach a user as a crash or a silent no-op — precisely what Feature 002
FR-004 forbids.

Feature 003 could not close this gap: its plan deliberately chose local unit tests and put
instrumented tests and Robolectric out of scope, so reaching Activity and Fragment code was
outside its approved mechanism. Its convergence task T019 recorded the gap and recommended
deciding on a test runtime **as its own feature**. This is that feature.

**This feature adds no application behaviour.** It verifies behaviour that already exists.

### Why it matters beyond these three guards

Every remaining baseline defect that touches the UI layer — **R1** (the reachable Profile-tab
crash) and **R4** (callbacks outliving the fragment view) — is unverifiable for the same reason
T019 was. Constitution Principle VII requires failure-path tests, so the inability to test
Activity and Fragment code blocks *any* future UI-layer feature from being declared done. This
feature removes that constraint, which is why it is worth doing now rather than alongside a
later feature.

---

## User Scenarios & Testing

### User Story 1 — A regression in the failure path is caught automatically (Priority: P1)

A developer changes `ProfileFragment` or `RegisterActivity` and accidentally removes or inverts
the unresolved-reference guard. The test suite fails, naming the behaviour that broke, before
the change can reach a user.

**Why this priority**: it is the entire point of the feature and the content of T019.

**Acceptance Scenarios**

1. **Given** the configured project resolves no database reference, **When**
   `ProfileFragment` loads the profile, **Then** an automated test asserts the user is shown
   the existing "Failed to load profile" message and no dereference of the null reference is
   attempted.
2. **Given** the same condition, **When** a new hydration target is saved, **Then** an
   automated test asserts the existing "Failed to update target" message is shown and the write
   is not attempted.
3. **Given** the same condition, **When** registration completes, **Then** an automated test
   asserts the failure is recorded and navigation to Login still happens, because Feature 002
   deliberately preserved that navigation.
4. **Given** a resolvable database reference, **When** the same paths run, **Then** automated
   tests assert the guards do **not** trigger — proving the tests detect the branch rather than
   passing unconditionally.

### User Story 2 — The UI layer becomes testable at all (Priority: P1)

A developer writing any future Activity or Fragment change can write an automated test for it,
so Constitution VII can be satisfied without a physical device.

**Why this priority**: it is the durable value, and it unblocks R1 and R4.

**Acceptance Scenarios**

1. **Given** the project's test configuration, **When** a test needs real Android behaviour
   (resource loading, view inflation, toast capture), **Then** it can obtain it in a local
   `test` run with no device and no emulator.
2. **Given** the existing Feature 003 tests, **When** the new runtime is added, **Then** those
   tests still pass unchanged — the new mechanism must not disturb them.

### User Story 3 — The suite stays honest (Priority: P2)

The new tests must be capable of failing for the right reason.

**Acceptance Scenarios**

1. **Given** a new test, **When** the guard it covers is deliberately broken, **Then** that test
   fails; **and when** the guard is restored, it passes again.

### Edge Cases

- No user is signed in, so the call site returns before reaching the guard — the tests must not
  mistake that early return for the guard working.
- The message is shown through a mechanism needing a live context; a detached component must not
  produce a false pass.
- The new runtime interacts with Feature 003's `unitTests.isReturnDefaultValues = true`, which
  Feature 003 research R-3 flagged for review at exactly this moment.

---

## Requirements

### Functional Requirements

- **FR-001**: The test suite MUST verify that `ProfileFragment`'s profile load shows the
  existing failure message and returns when no database reference is available.
- **FR-002**: The test suite MUST verify that `ProfileFragment`'s hydration-target save shows
  the existing failure message and does not attempt the write when no reference is available.
- **FR-003**: The test suite MUST verify that `RegisterActivity` records the failure and still
  navigates to Login when no reference is available.
- **FR-004**: The test suite MUST verify the negative case for each guard — that it does not
  trigger when a reference **is** available — so a test cannot pass by never reaching the code.
- **FR-005**: Each new test MUST be demonstrated capable of failing, by temporarily breaking the
  behaviour it covers and observing the failure.
- **FR-006**: All tests MUST run in a local `test` task with **no device and no emulator**, so
  they are part of automated verification rather than a manual step.
- **FR-007**: The six existing Feature 003 tests MUST continue to pass unchanged.
- **FR-008**: **No application behaviour may change.** Test-visibility adjustments to
  application code are permitted **only** if no alternative exists, MUST be justified in the
  plan, and MUST NOT alter runtime behaviour.
- **FR-009**: Any new dependency MUST be justified against Constitution VIII and MUST be
  test-scoped, never packaged into the APK.
- **FR-010**: The interaction with `unitTests.isReturnDefaultValues = true` MUST be resolved
  deliberately and recorded, not left to chance.
- **FR-011**: If the chosen mechanism proves infeasible, that MUST be reported as a finding with
  the decision the developer needs to make — **not** worked around by weakening the requirement,
  deleting a test, or refactoring application architecture without approval.

### Key Entities

None. This feature adds no data.

---

## Success Criteria

- **SC-001**: All three unresolved-reference guards have automated coverage of both the
  triggering and the non-triggering case.
- **SC-002**: `:app:testDebugUnitTest` passes with **exit code 0** and reports the six existing
  tests plus the new ones, with zero failures.
- **SC-003**: Every new test is proven able to fail via a deliberate mutation.
- **SC-004**: `:app:assembleDebug` and `:app:lintDebug` still succeed with exit code 0, and lint
  reports no new errors against the 41-warning / 0-error baseline.
- **SC-005**: No file under `app/src/main/` changes behaviour; any change there is
  visibility-only and justified.
- **SC-006**: The tests require no device, no emulator, and no network-dependent external
  service at run time.

---

## Out of Scope

- **Fixing R1, R3, R4, or any other baseline defect.** This feature builds the means to test the
  UI layer; the fixes are separate work, and R1 in particular is blocked on open questions 1–2.
- **Answering any of the 18 open requirement questions.**
- **Instrumented (`androidTest`) tests**, which need a device and so cannot be part of automated
  verification.
- **Refactoring the Firebase dependency construction** in `ProfileFragment` or
  `RegisterActivity` into an injected seam. That is an architecture change; if the tests cannot
  be written without it, FR-011 applies and it is reported rather than performed.
- **Any UI, layout, or behavioural change.**
- **Committing anything.**

---

## Assumptions

- Feature 002's three guards are the correct behaviour; this feature verifies them and does not
  re-litigate them.
- The existing user-facing message strings are correct as written and are asserted as they are.
- A test-scoped dependency that never ships in the APK is within Constitution VIII's allowance
  provided it is justified.
