# Implementation Plan: Cover the Unresolved-Database Call Sites

**Feature**: [spec.md](spec.md) | **Research**: [research.md](research.md) | **Date**: 2026-09-30

---

## Summary

Add Robolectric as a test-scoped dependency and cover the `ProfileFragment` unresolved-reference
guards with automated tests that run in the ordinary `test` task. `RegisterActivity`'s guard is
deliberately excluded and reported, because covering it would require ratifying an undecided
business rule (research R-5).

**No application behaviour changes.** No file under `app/src/main/` is modified at all — not even
for visibility, so FR-008's escape hatch is unused.

**Scale**: 1 build file modified, 1 test file added, 5 tests. No production code touched.

---

## Technical Context

**Language**: Kotlin 2.0.20, JVM target 17
**Test runtime**: JUnit 4.13.2, MockK 1.13.13, **Robolectric 4.14.1** (new)
**Target**: `app/src/test/` — local unit tests, no device
**Baselines to preserve**: 6 existing tests passing, lint 41 warnings / 0 errors

---

## Constitution Check

| # | Principle | Status | Note |
| --- | --- | --- | --- |
| I | Business-Process Authority | **PASS** | Invents nothing. Actively *avoids* inventing: FR-003 is left unmet precisely so a test does not ratify the undecided hydration range (research R-5). |
| II | Android/Kotlin First | **PASS** | Kotlin. Tests mirror the source package layout. No layer added. |
| III | IoT Reliability | **NOT APPLICABLE** | No device interaction. |
| IV | Data Integrity | **NOT APPLICABLE** | No records written; the covered paths are the ones that write nothing. |
| V | Offline-Aware Design | **PASS (indirect)** | The behaviour covered is how the app degrades when its backend cannot be resolved. |
| VI | Security and Privacy | **PASS** | No credentials. `test-uid` and `test@example.com` are obvious fakes. No config value asserted. |
| VII | Testing | **PASS** | This *is* the principle being served — the failure path Feature 002 created is now covered, with negative controls and mutation proof. |
| VIII | Maintainability | **PASS** | One new dependency, justified in research R-1 and in the build file. Deliberately avoided two more (`fragment-testing` and its manifest artifact) by hosting in `MainActivity`. |
| IX | Spec-Driven Development | **PASS** | spec → research/spike → plan → tasks → implement → verify → converge. |
| X | Change Control | **PASS** | Zero production files modified. The one build-file change is additive. |
| XI | Definition of Done | **PARTIAL by design** | FR-003 is knowingly unmet and reported per FR-011, not hidden. |

**Complexity Tracking**: one entry.

| Item | Why needed | Simpler alternative rejected because |
| --- | --- | --- |
| Robolectric | The guards live in a Fragment and report through `Toast`; neither is reachable otherwise | Plain JUnit cannot construct a Fragment or read a Toast. Instrumented tests need a device, which would fail FR-006. Refactoring for injection would change working architecture, which FR-008 forbids. |

---

## Design Decisions

### Host in `MainActivity`, not `FragmentScenario`

The standard approach, `androidx.fragment:fragment-testing`, needs the library **plus**
`fragment-testing-manifest` as a `debugImplementation` to supply an empty host activity — two
dependencies and a build-variant change to test one guard.

`MainActivity` is already the fragment's real host, is declared in the manifest, and was checked
to do no Firebase work in `onCreate` before being relied on. Using it means **one** new
dependency and tests that exercise the same hosting the app uses.

### Stub before construction

`ProfileFragment` resolves `FirebaseAuth.getInstance()` and `FirebaseDatabaseProvider.reference()`
in **field initializers**, so stubs must exist before `ProfileFragment()` is constructed. `@Before`
establishes them; `unmockkAll()` in `@After` prevents order dependence, since `mockkStatic` and
`mockkObject` are global while active.

### Every guard gets a negative control

`loadUserProfile()` begins `firebaseAuth.currentUser?.uid ?: return`. A test with no signed-in
user returns **before** the guard and passes while proving nothing. So each guard is asserted
twice — once where it must fire, once where it must not. The negative case is what distinguishes
"the guard worked" from "the code was never reached".

### Reflection for `saveHydrationTarget`, with the limitation stated

It is `private` and wired to `Slider.OnSliderTouchListener`. Material `Slider` exposes no way to
read back registered touch listeners, and synthesising a touch needs a measured, laid-out slider.
Invoking the method directly covers the guard inside it; the slider-to-method wiring stays
uncovered and is recorded as such rather than glossed over.

### `isReturnDefaultValues` is kept

Per research R-6 it governs non-Robolectric tests, which still include the two Feature 003
classes. Robolectric tests bypass it. Removing it would break working tests for no gain. Its
comment is rewritten to explain the coexistence so this is not re-litigated.

---

## Project Structure

```
app/
├── build.gradle.kts                     # MODIFY - add Robolectric, isIncludeAndroidResources
└── src/test/java/com/sipsense/app/
    ├── ProfileFragmentDatabaseGuardTest.kt   # ADD - 5 tests
    ├── data/FirebaseDatabaseProviderTest.kt  # UNCHANGED (Feature 003)
    └── model/UserProfileTest.kt              # UNCHANGED (Feature 003)
```

**Nothing under `app/src/main/` is touched.**

---

## Phases

1. **Feasibility** — spike Robolectric, then spike guard reachability. Delete both spikes. *(Done
   in research; a plan that assumed the outcome would have been dishonest.)*
2. **Configure** — Robolectric dependency, `isIncludeAndroidResources`, resolve
   `isReturnDefaultValues`.
3. **Cover** — the 5 tests, each guard positive and negative.
4. **Prove** — mutation-test each guard; confirm the 6 existing tests still pass.
5. **Verify** — build, lint, full suite, all by exit code.
6. **Report** — FR-003 as a finding; converge.

---

## Risks

| Risk | Mitigation |
| --- | --- |
| A test passes because the guard was never reached | Negative control per guard, plus mutation testing |
| Robolectric disturbs the 6 existing tests | Run the whole suite, not just the new class |
| Robolectric's first run downloads a runtime jar | One-off; confirmed working in the spike. If a future offline run fails, it is an environment issue, not a defect |
| Reflection hides a break in the slider wiring | Stated explicitly as uncovered rather than implied covered |
| Test slowness discourages running the suite | Accepted: ~25s for the Robolectric class. Still far cheaper than a device |
