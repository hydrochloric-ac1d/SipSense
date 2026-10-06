# Tasks: Cover the Unresolved-Database Call Sites

**Feature**: [spec.md](spec.md) | **Plan**: [plan.md](plan.md) | **Research**: [research.md](research.md)
**Date**: 2026-09-30

---

## Phase 1: Feasibility (spike, throwaway)

- [X] T001 Spike Robolectric: does it run, can it fetch its runtime, can it inflate
      `fragment_profile` with Material components under `Theme.SipSense`? (research R-2)
      → **PASS.** 2 tests, 0 failures, ~24s including the one-off runtime download.
      Required `isIncludeAndroidResources = true`; without merged resources the theme cannot
      resolve.
- [X] T002 Spike guard reachability: can `ProfileFragment`'s unresolved-reference guard be reached
      and asserted without adding `androidx.fragment:fragment-testing`? (research R-3)
      → **PASS.** Hosting in `MainActivity` works. `MainActivity` was checked first and does no
      Firebase work in `onCreate`. Both Firebase dependencies are **field initializers**, so
      stubs must exist before construction.
- [X] T003 Delete both spike files before implementation.
      → Done. `SpikeRobolectricTest.kt` and `SpikeProfileGuardTest.kt` removed.

## Phase 2: Configure the test runtime

- [X] T004 Add Robolectric 4.14.1 as `testImplementation`, with the justification recorded in the
      build file. (FR-009, Constitution VIII)
- [X] T005 Enable `testOptions.unitTests.isIncludeAndroidResources = true`. (research R-2)
- [X] T006 Resolve the `isReturnDefaultValues` question Feature 003 research R-3 deferred to this
      moment, and record the decision. (FR-010)
      → **KEPT.** It governs non-Robolectric tests, which still include both Feature 003 classes —
      `FirebaseDatabaseProviderTest`'s code under test calls `Log.e` inside the branch being
      verified. Robolectric tests supply real framework behaviour and bypass it entirely, so the
      two coexist. Removing it would break working tests for no benefit. Its comment was rewritten
      so this is not re-litigated. (research R-6)

## Phase 3: Cover the guards

- [X] T007 Cover `loadUserProfile()` where no reference resolves — the existing "Failed to load
      profile" message is shown and no dereference occurs. (FR-001, US1/AC1)
- [X] T008 Cover `loadUserProfile()` where a reference **does** resolve — the guard must not fire.
      (FR-004, US1/AC4)
- [X] T009 Cover `saveHydrationTarget()` where no reference resolves — "Failed to update target"
      is shown and no write is attempted. (FR-002, US1/AC2)
- [X] T010 Cover `saveHydrationTarget()` where a reference **does** resolve. (FR-004, US1/AC4)
- [X] T011 Assert the screen stays usable when no reference resolves — no exception escapes and
      the slider is present and enabled. This is FR-004's substance: degrade, do not crash.
- [X] T012 Confirm no file under `app/src/main/` was modified. (FR-008, SC-005)
      → **Zero production files changed.** FR-008's visibility escape hatch went unused.

## Phase 4: Prove the tests are load-bearing

- [X] T013 Prove each positive assertion can fail, per FR-005 and SC-003.
      → **PROVEN by test-only mutation.** Both expected messages were replaced with sentinels and
      the suite was re-run: exactly the 2 corresponding tests failed, with
      `expected:<MUTANT-load> but was:<Failed to load profile>` and
      `expected:<MUTANT-save> but was:<Failed to update target>`. The actual observed values are
      therefore the real guard messages, and the assertions genuinely execute. The mutation was
      reverted and verified clean.

      **Why the mutation was applied to the test rather than to `ProfileFragment`**: mutating
      production source to prove a test is a needless risk when the same question can be answered
      without touching it. Combined with T008/T010 — where a resolvable reference produces **no
      toast at all** — the evidence that the message is caused by the null-reference path is
      complete. No production file was modified at any point.
- [X] T014 Confirm the 6 pre-existing Feature 003 tests still pass unchanged. (FR-007)
      → **PASS.** `FirebaseDatabaseProviderTest` 3/3, `UserProfileTest` 3/3, neither file touched.

## Phase 5: Automated verification

- [X] T015 `:app:assembleDebug` — **BUILD SUCCESSFUL, exit code 0**. (SC-004)
- [X] T016 `:app:lintDebug --rerun-tasks` — **exit code 0, 41 warnings, 0 errors**, exactly the
      established baseline, so nothing regressed. (SC-004)
- [X] T017 `:app:testDebugUnitTest --rerun-tasks` — **exit code 0, 11 tests, 0 failures**
      (6 existing + 5 new). (SC-002)
- [X] T018 Confirm the new dependency is not packaged into the APK. (FR-009, SC-006)
      → **PASS.** 0 of 1012 APK entries match `robolectric`, `mockk`, or `junit`.
- [X] T019 Confirm repository integrity still holds after Feature 004 — no generated output
      tracked, `google-services.json` still untracked.
      → **PASS.** `app/build` 0, `.gradle` 0, `google-services.json` 0.
- [X] T020 Static check: no API key, database URL, or project id in the new test file, validated
      with a positive control. (Constitution VI)
      → **PASS**, and the control confirmed the scan actually reads the file.

## Phase 6: Report what was deliberately not done

- [X] T021 Record FR-003 (`RegisterActivity`) as an unmet requirement with its reasoning, per
      FR-011 — not worked around, not weakened.
      → **REPORTED, NOT IMPLEMENTED.** See research R-5. The guard sits behind **9 validation
      gates** across 6 form fields, so a test must submit a form that passes every rule —
      including a specific **hydration target value**. The authoritative hydration range is
      baseline **open question 1**, and the 500–10000 versus 500–5000 disagreement is baseline
      finding **R1**. A test hardcoding a value to clear validation would quietly ratify one side
      of an undecided business rule and make it look approved, which Principle I forbids.
      **Proportionality**: this is the least risky of the three guards — it only writes a log
      line, and navigation to Login is unconditional either way by deliberate Feature 002 design,
      so there is no user-visible behaviour to regress.
      **Unblocked by**: answering open questions 1–2. Should be done then.

---

## Deliverables

| File | Change |
| --- | --- |
| `app/build.gradle.kts` | **MODIFY** — Robolectric, `isIncludeAndroidResources`, rewritten `isReturnDefaultValues` comment |
| `app/src/test/java/com/sipsense/app/ProfileFragmentDatabaseGuardTest.kt` | **ADD** — 5 tests |
| `app/src/main/**` | **UNCHANGED — zero files** |

---

## Out of Scope — do not address in this feature

Fixing R1, R3, R4 or any baseline defect; answering any of the 18 open requirement questions;
instrumented (`androidTest`) tests; refactoring the Firebase dependency construction into an
injected seam; any UI, layout, or behavioural change; committing anything.

---

## Phase 7: Convergence — 2026-09-30

Outcome: **`tasks_appended`** — 2 findings. Assessed against spec, plan, and tasks.

Checked: 11 functional requirements, 6 success criteria, 6 user-story acceptance scenarios,
5 plan decisions, 11 constitution principles. Findings by gap type: 1 `missing`, 1 `partial`,
0 `contradicts`, 0 `unrequested`.

- [ ] T022 Cover the `RegisterActivity` unresolved-reference guard, per FR-003 and US1/AC3
      (`missing`)
      → **BLOCKED on baseline open questions 1–2**, not on implementation effort. Recorded in full
      under T021 and research R-5. Writing this test today requires asserting a hydration value
      that passes 9 validation gates, which would ratify an undecided business rule.
      **This is an external/requirements blocker**: it needs an approved requirement stating the
      authoritative hydration minimum, maximum, step, and default. It is not automatable until
      then, and forcing it would violate Principle I.
- [X] T023 Cover the slider-to-`saveHydrationTarget` wiring, per FR-002 (`partial`)
      → **Evidence of the gap**: T009 and T010 covered the guard *inside* `saveHydrationTarget` by
      invoking the private method directly, leaving the wiring from
      `Slider.OnSliderTouchListener.onStopTrackingTouch` uncovered — a regression that disconnected
      the listener would not have been caught.
      → **CLOSED in the first convergence pass, through the public API.** Rather than reaching into
      Material internals, the test measures and lays the slider out (a view processes no touch until
      it has non-zero size), then dispatches a real `ACTION_DOWN`/`ACTION_UP` pair at its centre.
      That routes through Material's own touch handling to `onStopTrackingTouch` and into the guard.
      Test: `releasing the slider routes through the guard` — **PASS**.
      **This test is self-validating**: had the touch not reached the listener, no toast would have
      been produced and the assertion would have failed on `null`. Its passing is itself the proof
      that the wiring fired.

**Not duplicated**: FR-003 is deliberately tracked once, as T022 above. The append contract
forbids reissuing T021, which records the same finding from the implementation side.

**Everything else is converged.** FR-001, FR-002, FR-004 through FR-011 are satisfied and
independently verified, SC-001 through SC-006 hold (SC-001 partially — two of three guards, the
third blocked as above), and the mutation testing proves the suite can actually fail.
