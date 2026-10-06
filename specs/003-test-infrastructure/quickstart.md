# Verification Quickstart: Automated Test Infrastructure

**Feature**: [spec.md](spec.md) | **Date**: 2026-09-30

Every scenario here is agent-verifiable. This feature has no external dependency and no manual step —
the only manual item is confirming IDE integration (Scenario 5), which needs Android Studio.

---

## Scenario 1 — The suite runs and passes (SC-001, SC-004, FR-001, FR-002)

```powershell
cd C:\Users\adaaz\StudioProjects\SipSense
.\gradlew.bat :app:testDebugUnitTest
```

**Pass**: `BUILD SUCCESSFUL`, with `:app:testDebugUnitTest` executed rather than `NO-SOURCE`. A
`NO-SOURCE` result means the source set was not found and nothing ran — treat it as a failure.

Confirm the tests were actually discovered and executed:

```powershell
Get-ChildItem app\build\test-results\testDebugUnitTest\*.xml |
    ForEach-Object {
        $x = [xml](Get-Content $_)
        "{0}: tests={1} failures={2} errors={3} skipped={4}" -f `
            $x.testsuite.name, $x.testsuite.tests, $x.testsuite.failures,
            $x.testsuite.errors, $x.testsuite.skipped
    }
```

**Pass**: total tests greater than zero, with zero failures, zero errors, and zero skipped.

---

## Scenario 2 — The suite needs no device, network, or database (SC-006, FR-003)

The run in Scenario 1 already demonstrates this if no emulator was attached and no Realtime Database
exists in the configured Firebase project — which is the current state of `sipsense-17b6c`.

To check deliberately:

1. Confirm no emulator or device is connected.
2. Confirm `app/google-services.json` still has no `firebase_url`, so no database could be reached even
   if the tests tried.
3. Re-run Scenario 1. It must pass regardless.

**Pass**: the suite passes with nothing provisioned. If a test needs a live database, it is the wrong
kind of test for this feature.

---

## Scenario 3 — A failing test fails the build (US1/AC2, FR-002)

A suite that cannot fail the build is not a gate. Verify the exit code is honest:

```powershell
cd C:\Users\adaaz\StudioProjects\SipSense
.\gradlew.bat :app:testDebugUnitTest
Write-Host "exit code: $LASTEXITCODE"
```

**Pass** for the healthy suite: exit code `0`. Then confirm the failing case in Scenario 4 reports a
non-zero exit code and names the failing test.

---

## Scenario 4 — The tests are load-bearing (SC-003)

**This is the scenario that distinguishes real verification from decorative tests.** A test that passes
no matter what the code does proves nothing, so break each covered behaviour on purpose and confirm the
suite notices.

For each mutation: apply it, run `:app:testDebugUnitTest`, confirm the suite **fails** and names the
relevant test, then revert it.

| # | Temporary mutation | Expected |
| --- | --- | --- |
| 1 | In `FirebaseDatabaseProvider`, remove the `try`/`catch` so resolution throws | failure-path test fails |
| 2 | In `FirebaseDatabaseProvider`, narrow the catch to `catch (e: DatabaseException)` | the unexpected-failure test fails |
| 3 | In `UserProfile`, change `hydrationTarget`'s default from `2500` to another number | profile defaults test fails |
| 4 | In `UserProfile`, remove a field default entirely | the model no longer compiles with no-argument construction, so the test fails to compile — also a pass, since the constraint is enforced |

**Pass**: every mutation produces a failure. **Revert all mutations afterwards** and re-run Scenario 1 to
confirm the suite is green again. Mutation 2 is the important one — it is the only check that the broad
`catch (e: Exception)` is deliberate.

> Mutations 1–4 modify `app/src/main/` temporarily. That does not breach FR-008, which governs the
> committed state of the feature. Confirm with `git status` that nothing under `app/src/main/` is
> modified when you finish.

---

## Scenario 5 — Android Studio recognises the tests (US1/AC3) — manual

1. Open the project in Android Studio and let Gradle sync.
2. Open a test class under `app/src/test/java/`.
3. Confirm a run gutter icon appears next to the class and each test function.
4. Run one test from the IDE and confirm it passes in the test results panel.

**Pass**: tests are runnable from the IDE without extra configuration.

---

## Scenario 6 — Nothing regressed (SC-005, FR-009, FR-008)

```powershell
cd C:\Users\adaaz\StudioProjects\SipSense
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:lintDebug
git status --short -- app/src/main
```

**Pass**: both builds succeed, lint reports **zero errors**, and `git status` shows **no modified file
under `app/src/main/`** — the tests were added around existing behaviour without changing it.

Lint warning count may rise if lint considers a newly added dependency outdated; that is a
`GradleDependency` warning, not an error, and does not fail this scenario. Record the number either way.

---

## Negative check — test dependencies do not ship in the APK (Principle VI)

Both new dependencies must be `testImplementation`, never `implementation`, or a test framework would be
bundled into the shipped application.

```powershell
cd C:\Users\adaaz\StudioProjects\SipSense
Select-String -Path app\build.gradle.kts -Pattern 'junit','mockk' -SimpleMatch
```

**Pass**: every match is on a `testImplementation` line. Any `implementation("junit...")` or
`implementation("io.mockk...")` is a failure.
