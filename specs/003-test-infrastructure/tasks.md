# Tasks: Automated Test Infrastructure

**Feature**: [spec.md](spec.md) | **Plan**: [plan.md](plan.md) | **Date**: 2026-09-30

Dependency-ordered. `[P]` marks tasks that can run in parallel.

---

## Phase 1: Build configuration

- [x] T001 Add a `testOptions { unitTests { isReturnDefaultValues = true } }` block inside `android { }`
      in `app/build.gradle.kts`, with a comment explaining that it exists because the code under test
      logs through `android.util.Log`, which throws in local unit tests. (research R-3)
      → **DONE.** Comment records why it is load-bearing and when to revisit it (Robolectric).
- [x] T002 Add `testImplementation("junit:junit:4.13.2")` and
      `testImplementation("io.mockk:mockk:1.13.13")`, each with a comment stating its reason, matching
      the file's existing commented-literal style. Both MUST be `testImplementation`, never
      `implementation`. (FR-007, Principle VIII)
      → **DONE.** Both declared under a "Local Unit Tests" banner matching the file's style, each with
      a stated reason. No version catalog introduced, per the existing convention.
- [x] T003 Confirm the module resolves the new configuration. (FR-001)
      → **PASS**, by successful test compile: `:app:compileDebugUnitTestKotlin` executed and
      `:app:testDebugUnitTest` ran.

## Phase 2: Cover the database-resolution paths

- [x] T004 Create `app/src/test/java/com/sipsense/app/data/FirebaseDatabaseProviderTest.kt`, mirroring
      the main source tree's package layout. (FR-001, plan Structure Decision)
      → **DONE.** Package `com.sipsense.app.data`, matching the code under test.
- [x] T005 Test the **failure path**: stub the static `FirebaseDatabase.getInstance()` to throw
      `DatabaseException`, assert `reference()` returns `null` and that nothing is thrown. (FR-004,
      US2/AC1, Constitution VII)
      → **DONE and proven load-bearing** — fails when the `try`/`catch` is removed (T013 mutation 3).
- [x] T006 Test the **success path**: stub resolution to return a mock whose `reference` is a known
      value, assert `reference()` returns that same value. Without this, the failure test could pass
      while resolution is broken in every case. (FR-005, US2/AC2)
      → **DONE.** Asserts identity with `assertSame`, not merely non-null, so a wrong-but-present
      reference would fail.
- [x] T007 Test the **unexpected failure** case: stub resolution to throw `IllegalStateException` and
      assert `reference()` still returns `null`. This is the only check that the broad
      `catch (e: Exception)` is deliberate. (US2/AC3)
      → **DONE and proven load-bearing** — this is the *only* test that fails when the catch is
      narrowed to `DatabaseException` (T013 mutation 1). Its value is now demonstrated, not asserted.
- [x] T008 Ensure every test unstubs the static afterwards, so no test leaks a stubbed static into
      another and makes the suite order-dependent. (research R-2)
      → **DONE.** `@Before` stubs and `@After` unstubs, so the lifecycle is symmetric and per-test.

## Phase 3: Cover the profile model

- [x] T009 [P] Create `app/src/test/java/com/sipsense/app/model/UserProfileTest.kt` and assert that
      no-argument construction yields the documented defaults, including `hydrationTarget == 2500`.
      (FR-006, US3/AC1, Principle IV)
      → **DONE**, 3 tests: documented defaults, `createdAt` within a measured time window, and that
      supplied values survive construction. Proven load-bearing by T013 mutation 2.

## Phase 4: Documentation

- [x] T010 [P] `README.md` — add a section stating how to run the suite, noting that it needs no device,
      emulator, or network. (FR-010)
      → **DONE.** New **step 6** with Windows and Unix commands, the no-device/no-network/no-database
      guarantee, the non-zero exit behaviour, the HTML report path, and IDE instructions.

## Phase 5: Automated verification

- [x] T011 Run `.\gradlew.bat :app:testDebugUnitTest`. Must be `BUILD SUCCESSFUL` with the task
      **executed**, not `NO-SOURCE`. (quickstart Scenario 1, SC-001, SC-004)
      → **PASS. BUILD SUCCESSFUL**, exit code 0. `:app:compileDebugUnitTestKotlin` and
      `:app:testDebugUnitTest` both executed — not `NO-SOURCE`, so the source set was genuinely found.
- [x] T012 Parse `app/build/test-results/testDebugUnitTest/*.xml` and confirm total tests > 0 with zero
      failures, errors, and skips. A passing build with zero tests is a false green. (SC-004)
      → **PASS. 6 tests, 0 failures, 0 errors, 0 skipped.**
      `FirebaseDatabaseProviderTest` 3/3, `UserProfileTest` 3/3.
- [x] T013 **Prove the tests are load-bearing** by running quickstart Scenario 4. (SC-003)
      → **PASS — all three mutations were caught, each by the correct test:**

      | Mutation | Result |
      | --- | --- |
      | Narrow `catch (e: Exception)` to `catch (e: DatabaseException)` | `returns null when resolution fails unexpectedly` FAILED, 6 tests / 1 failed, exit code 1 |
      | Change `UserProfile.hydrationTarget` default 2500 → 2000 | `constructs with documented defaults when given no arguments` FAILED, 6 tests / 1 failed |
      | Remove the `try`/`catch` so resolution throws | both failure-path tests FAILED, 6 tests / 2 failed; the success-path test correctly still passed |

      Each mutation was reverted immediately after its run. This also satisfies US1/AC2 and quickstart
      Scenario 3: a failing suite produced **exit code 1** and named the failing test.
- [x] T014 Confirm `git status` shows no modified file under `app/src/main/` after T013's mutations are
      reverted. (FR-008, quickstart Scenario 6)
      → **PASS.** `UserProfile.kt` does not appear as modified at all, so it is byte-identical to
      `HEAD` — mutation fully reverted. `FirebaseDatabaseProvider.kt` was re-read directly and contains
      `catch (e: Exception)` with no leftover scaffold. The only entries under `app/src/main/` are
      Feature 002's `ProfileFragment.kt`, `RegisterActivity.kt`, and the untracked `data/` directory;
      **Feature 003 contributed no change to `app/src/main/`.**
- [x] T015 Run `.\gradlew.bat :app:assembleDebug` and `.\gradlew.bat :app:lintDebug`. (FR-009, SC-005)
      → **PASS.** Both `BUILD SUCCESSFUL`. Lint: **41 issues, all Warning, 0 errors** — identical count
      and identical category distribution to the pre-change baseline. Notably the two new dependencies
      produced **no** additional `GradleDependency` warning, which had been flagged as a risk.
- [x] T016 Run the negative check confirming both new dependencies are `testImplementation` only. (Principle VI)
      → **PASS.** Lines 122 and 128 are both `testImplementation`. No `implementation(...)` line
      references `junit` or `mockk`, so no test framework is packaged into the APK.
- [x] T017 Confirm the suite needed no device, network, or provisioned database. (FR-003, SC-006)
      → **PASS**, and stronger than expected: `app/google-services.json` still has **no `firebase_url`
      key**, and the only attached device (`emulator-5554`) was reported **offline**. The suite passed
      anyway, so it demonstrably depends on neither.

## Phase 6: Manual

- [ ] T018 **MANUAL.** Run quickstart Scenario 5 — confirm Android Studio shows run gutter icons for the
      test classes and can run a single test from the IDE. (US1/AC3)
      → **AWAITING MANUAL RESULT** — requires an Android Studio session. The tests themselves are
      already verified from the command line, so this confirms IDE ergonomics only, not correctness.

---

## Execution Summary

**17 of 18 tasks complete. Every automated criterion passed. The single remaining task is IDE
confirmation, which needs Android Studio.**

| Requirement | Status | Evidence |
| --- | --- | --- |
| FR-001 unit test source set | **PASS** | task executed, not `NO-SOURCE` |
| FR-002 one command, honest exit code | **PASS** | exit 0 green; exit 1 with a failing test |
| FR-003 no device/network/database | **PASS** | passed with `firebase_url` absent and emulator offline |
| FR-004 failure path covered | **PASS** | proven by mutation |
| FR-005 success path covered | **PASS** | `assertSame` on the resolved reference |
| FR-006 profile defaults covered | **PASS** | proven by mutation |
| FR-007 stated reason per dependency | **PASS** | commented declarations matching file style |
| FR-008 no `app/src/main/` change | **PASS** | `UserProfile.kt` identical to HEAD; provider re-verified |
| FR-009 no build/lint regression | **PASS** | 41 warnings, 0 errors, unchanged |
| FR-010 documentation | **PASS** | README step 6 |
| SC-001 documented command runs suite | **PASS** | T011 |
| SC-002 failure + success coverage | **PASS** | T005, T006, T007 |
| SC-003 tests are load-bearing | **PASS** | 3/3 mutations caught by the right test |
| SC-004 all tests pass | **PASS** | 6/6, none skipped |
| SC-005 build and lint no worse | **PASS** | identical lint profile |
| SC-006 zero manual setup to run | **PASS** | T017 |

**Files changed**: `app/build.gradle.kts` (modified), `README.md` (modified),
`app/src/test/java/com/sipsense/app/data/FirebaseDatabaseProviderTest.kt` (added),
`app/src/test/java/com/sipsense/app/model/UserProfileTest.kt` (added).

**Constitution VII is now materially satisfied for the resolution behaviour**: the failure path that
Feature 002 introduced is covered by tests that are demonstrably capable of failing. Coverage remains
narrow by design — see Phase 7.

### Observation — not a task for this feature

Two mutation runs ended with a secondary `Failed to release lock on ...` error against `.gradle/8.9/`
after the test task had already produced its result. This is the same Android Studio daemon
contention recorded during Feature 001, aggravated by baseline finding **R10** (build artifacts tracked
in git). It did not affect any test outcome — the test results were produced and parsed correctly in
every run. R10 is Out of Scope here, so this is recorded rather than actioned.

---

## Phase 7: Convergence

Appended 2026-09-30 by the convergence assessment. Outcome: `tasks_appended` — **1 finding**.

Checked: 10 functional requirements, 6 success criteria, 6 user-story acceptance scenarios, 4 plan
decisions, 11 constitution principles. Findings by gap type: 0 `missing`, 1 `partial`,
0 `contradicts`, 0 `unrequested`.

- [X] T019 Cover the three call sites that consume an unresolved database reference, per Constitution
      VII and Feature 002 FR-004 (`partial`)
      → **ADDRESSED 2026-09-30 by [Feature 005](../005-cover-unresolved-database/tasks.md)**, created
      as its own feature exactly as this task recommended, because adopting a heavier test runtime
      contradicted *this* feature's plan. Feature 005 adopted **Robolectric** (rejecting instrumented
      tests, which need a device and so could not be part of automated verification) and added 6
      tests covering both `ProfileFragment` guards — each asserted in the triggering **and**
      non-triggering case, plus the real slider-touch wiring. `isReturnDefaultValues` was reviewed
      at this exact moment as recommended and deliberately **kept**, because it governs the
      non-Robolectric tests, including this feature's two classes.
      **Partially remaining**: the `RegisterActivity` call site is tracked as Feature 005 T022 and is
      blocked on baseline open questions 1–2 — covering it would require a test to assert a hydration
      value that ratifies an undecided business rule. The original analysis follows.

      → **Evidence**: this feature covers `FirebaseDatabaseProvider` thoroughly, but the *user-visible*
      half of Feature 002's failure path is still untested — `ProfileFragment.loadUserProfile()` and
      `saveHydrationTarget()` showing their existing failure messages and returning early, and
      `RegisterActivity` logging and still navigating to Login. A regression there would surface to a
      user as a crash or a silent no-op, which is exactly what Feature 002 FR-004 forbids.

      **Severity: MEDIUM, not CRITICAL.** The resolution logic that decides *whether* a reference
      exists is now fully covered, including the broad-catch behaviour. What is uncovered is how two
      screens react, which is narrower and lower-risk than the untested provider was.

      **Why it was not done here**: these call sites live in an Activity and a Fragment, so reaching
      them requires Robolectric or instrumented tests. Feature 003 deliberately chose local unit tests
      only (research R-3) and lists instrumented tests under Out of Scope. Pulling them in now would
      mean adopting a heavier test runtime mid-feature, against the plan's stated decision.

      **Recommended**: decide deliberately between adding Robolectric and adding instrumented tests,
      as its own feature. That decision is also the moment to revisit
      `unitTests.isReturnDefaultValues = true`, which research R-3 flagged as needing review precisely
      when a test first needs real Android behaviour. This is that point.

### Already-tracked gaps — deliberately not duplicated

US1/AC3 (Android Studio recognises the tests) is unverified but already tracked as T018 and is manual
by nature. The append contract forbids reissuing existing tasks.

**The specified scope of this feature has no remaining implementation gaps.** FR-001 through FR-010
are all satisfied and independently verified, including the mutation testing that proves the suite can
actually fail.

## Out of Scope — do not address in this feature

Restated so the task list cannot drift: instrumented and UI tests, continuous integration, coverage
thresholds, refactoring `RegisterActivity` or `ProfileFragment` to make their logic testable, baseline
findings **R1**, **R3**, and **R10**, the 18 baseline open questions, and any change to authentication,
hydration tracking, history, device/IoT behaviour, or UI.

**FR-008 is the hard boundary**: no committed change to `app/src/main/`. The temporary mutations in T013
were applied and reverted for verification, and T014 confirmed the tree is clean afterwards.
