# Phase 0 Research: Cover the Unresolved-Database Call Sites

**Feature**: [spec.md](spec.md) | **Date**: 2026-09-30

Feature 003's convergence left a genuine open technical choice rather than a known answer, so
this feature began with a **throwaway feasibility spike** instead of a plan that assumed an
outcome. Both spike files were deleted once they had answered their questions.

---

## R-1: Which test runtime can reach Activity and Fragment code?

| Option | Verdict |
| --- | --- |
| Plain JUnit + MockK (what Feature 003 uses) | **Cannot work.** A Fragment needs a real `Context`, resource loading, view inflation, and `Toast`. Feature 003 set `isReturnDefaultValues = true` precisely to avoid needing them, and that flag makes framework calls return `null`/`0` rather than behave. |
| **Robolectric** (local JVM, simulated Android) | **Chosen.** Runs in the ordinary `test` task, so it is part of automated verification. No device, no emulator. |
| Instrumented `androidTest` | **Rejected.** Requires a device or emulator, which makes it a manual/external step and so unable to satisfy FR-006. It would have converted this work into an external blocker. |
| Refactor the call sites to inject their dependencies | **Rejected.** It changes working application architecture to suit a test. Spec Out of Scope and FR-008 forbid it; FR-011 requires reporting rather than performing it. |

**Decision**: Robolectric 4.14.1, test-scoped. Version chosen for Android 15 / API 35 support,
matching `compileSdk = 35`.

---

## R-2: Does Robolectric actually work in this project? (spike 1)

Three things could have blocked it, none of which could be settled by reasoning:

1. Does it run at all, and can it fetch its `android-all` runtime?
2. Can it inflate `fragment_profile`, which contains Material components?
3. Does Material's theme requirement break inflation? Material views throw when the theme is not
   a `Theme.MaterialComponents` descendant, and a test context carries no theme by default.

**Result: all three passed.** 2 tests, 0 failures, ~24s including the one-off runtime download.
`Theme.SipSense` (parent `Theme.MaterialComponents.Light.NoActionBar`, in `values/styles.xml`)
inflates `fragment_profile` and resolves `slider_hydration` when applied via
`ContextThemeWrapper`.

**Required for this to work**: `testOptions.unitTests.isIncludeAndroidResources = true`. Without
merged resources and manifest, theme resolution fails.

---

## R-3: Can the guards actually be reached? (spike 2)

Reaching them is harder than running Robolectric, because of how the fragment obtains its
dependencies:

```kotlin
private val firebaseAuth = FirebaseAuth.getInstance()
private val database = FirebaseDatabaseProvider.reference()
```

Both are **field initializers**, so they run at construction. Anything stubbing them must do so
*before* `ProfileFragment()` is constructed. And `loadUserProfile()` opens with
`firebaseAuth.currentUser?.uid ?: return`, so without a signed-in user the method returns
**before** the guard — which would look exactly like a passing test while proving nothing. This
is the trap the spec's Edge Cases section names.

**Solution, verified by spike 2:**

- `mockkStatic(FirebaseAuth::class)` with a mock user returning a uid, established in `@Before`.
- `mockkObject(FirebaseDatabaseProvider)` so `reference()` can be driven per test.
- Host the fragment in **`MainActivity`**, which is the real host in the app, is declared in the
  manifest, and — checked before relying on it — does no Firebase work in `onCreate`. This
  avoids adding `androidx.fragment:fragment-testing` and its debug manifest artifact.
- Assert the message with `ShadowToast.getTextOfLatestToast()`.

**Decision**: host in `MainActivity` rather than adding a fragment-testing dependency. One new
dependency instead of three.

---

## R-4: How is each guard reached?

| Guard | Reachability | Approach |
| --- | --- | --- |
| `ProfileFragment.loadUserProfile()` | Called from `onCreateView` | Attaching the fragment drives it — the real path. |
| `ProfileFragment.saveHydrationTarget()` | `private`, wired to `Slider.OnSliderTouchListener.onStopTrackingTouch` | Invoked directly by reflection. Material `Slider` exposes no way to read back its registered touch listeners, and synthesising a touch requires a measured, laid-out slider. **Limitation accepted and recorded**: the guard inside the method is covered; the slider-to-method wiring is not. |
| `RegisterActivity` profile write | Inside `createUserWithEmailAndPassword(...).addOnCompleteListener { … }`, behind the click handler | **Not covered — see R-5.** |

---

## R-5: Why `RegisterActivity` is deliberately left uncovered

This is a judgement call, not an oversight, and not a difficulty excuse.

The guard sits inside a completion lambda that only runs after the click handler clears
**9 separate validation gates** across 6 form fields. A test would therefore have to submit a
form that passes every rule — including **a specific hydration target value**.

**That is the problem.** The authoritative hydration range is baseline **open question 1**, and
the disagreement between registration's 500–10000 and the profile slider's 500–5000 is baseline
finding **R1** — an unresolved requirement. A test that hardcodes a value in order to pass
validation would silently ratify one side of an undecided business rule, embed it in the test
suite, and make it look approved. Constitution Principle I forbids resolving that ambiguity by
assumption, and a test is not an exemption.

**Proportionality also argues for waiting.** Of the three guards this is the least risky: it only
writes a log line, and navigation to Login happens unconditionally either way — a deliberate
Feature 002 decision. There is no user-visible difference to regress.

**Decision**: leave FR-003 unmet, record it as a finding with this reasoning, and let convergence
append it. Per FR-011 this is reported, not worked around. It becomes straightforward once open
questions 1–2 are answered, and should be done then.

---

## R-6: Does `isReturnDefaultValues = true` conflict with Robolectric?

Feature 003 research R-3 flagged this exact moment for review, so FR-010 requires an answer
rather than an assumption.

**They do not conflict, and the flag is kept.** It governs how the *stub* `android.jar` behaves
for tests that do not use Robolectric — which still includes `FirebaseDatabaseProviderTest`,
whose code under test calls `Log.e` inside the branch being verified. Robolectric-backed tests
never consult the stub, because they supply real framework behaviour. Removing the flag would
break the two existing test classes for no benefit.

**Decision**: keep it, and replace its comment with one explaining the coexistence, so the next
reader does not re-litigate it.

---

## R-7: How is the coverage proven load-bearing?

Feature 003 set the precedent that tests must be shown capable of failing. Two mechanisms here:

1. **Negative controls in the suite itself.** Every guard is asserted twice — once where it must
   fire, once where it must not. A test that passed because the code was never reached would fail
   its negative counterpart.
2. **Deliberate mutation** (FR-005, SC-003): break each guard, observe the matching test fail,
   restore it, observe it pass.
