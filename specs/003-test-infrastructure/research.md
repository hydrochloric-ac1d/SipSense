# Phase 0 Research: Automated Test Infrastructure

**Feature**: [spec.md](spec.md) | **Date**: 2026-09-30

---

## R-1: Which test framework, and where do tests live?

**Evidence gathered**: `app/build.gradle.kts` applies `com.android.application` and
`org.jetbrains.kotlin.android`, sets Java and Kotlin to 17, and declares dependencies as literal
coordinate strings each preceded by an explanatory comment. There is **no** `gradle/libs.versions.toml`,
so the project has no version catalog. `app/src/test` and `app/src/androidTest` do not exist.

**Decision**: JUnit 4 (`junit:junit:4.13.2`) in the conventional `app/src/test/java` source set, run by
`:app:testDebugUnitTest`.

**Why**: AGP configures this source set and task by default, with no plugin or runner setup. JUnit 5 on
Android needs an extra plugin and platform runner and offers nothing this feature requires. Following
the convention means Android Studio recognises the tests with no configuration, which US1/AC3 requires.

**Style decision**: declare the new dependencies as commented literal strings, matching the file. FR-007
requires a stated reason per dependency, and Principle VIII requires reusing existing conventions, so
introducing a version catalog here would be an unrequested architectural change.

---

## R-2: How can the failure path be reached at all?

**The problem**: the behaviour under test lives in a `catch` block around a static call:

```kotlin
FirebaseDatabase.getInstance().reference
```

`getInstance()` is a static SDK method. To make it fail on demand, the test must intercept a static
call. FR-008 forbids modifying `app/src/main/`, so a dependency-injection seam cannot be added to
production code — and adding one purely to test the feature that was just written would be circular.

**Options considered**:

| Option | Verdict |
| --- | --- |
| Hand-written fake | **Rejected.** Cannot intercept a static call; there is no seam to substitute. |
| Refactor the provider to accept an injected factory | **Rejected.** Requires editing `app/src/main/`, which FR-008 forbids. It also changes production code to suit a test, on the exact code being covered. |
| Mockito with `mockito-inline` | **Rejected.** Works, but needs an extra artifact and more ceremony for Kotlin `object`s and final classes. |
| MockK `mockkStatic` | **Chosen.** Kotlin-first, stubs Java statics directly, and unstubs cleanly per test. |

**Decision**: `io.mockk:mockk:1.13.13` as `testImplementation`, using `mockkStatic(FirebaseDatabase::class)`.

**Discipline required**: static stubbing is global while active, so each test must unstub afterwards.
Otherwise a stubbed static leaks into later tests and the suite becomes order-dependent — a failure mode
that is worse than no tests, because it is intermittent.

---

## R-3: `android.util.Log` in a local unit test

**This is the decision that shaped the build change.** In local unit tests, AGP puts a *stub* Android
framework on the classpath whose methods throw rather than run:

```text
java.lang.RuntimeException: Method e in android.util.Log not mocked.
```

The provider calls `Log.e` **inside the `catch` block that is the whole point of the failure test**, so
without handling this the test would fail on the logging call and never assert the behaviour.

**Options considered**:

| Option | Verdict |
| --- | --- |
| `testOptions { unitTests { isReturnDefaultValues = true } }` | **Chosen.** One line, no dependency; framework stubs return defaults instead of throwing. |
| Robolectric | **Rejected.** Solves it by providing a real Android runtime, but is a heavy dependency and markedly slower, for a project whose only present need is a logger that does nothing. |
| `mockkStatic(Log::class)` in each test | **Rejected.** Works, but repeats per-test setup for an incidental concern and couples every future test to remembering it. |
| Wrap the logger behind an injectable interface | **Rejected.** Requires editing `app/src/main/`; FR-008 forbids it. |

**Decision**: enable `isReturnDefaultValues`. Recorded here because it is easy to mistake for
boilerplate: it is load-bearing, and removing it breaks the failure-path test specifically.

**Known trade-off**: the setting applies module-wide, so a future test that needs *real* Android
behaviour will silently receive defaults instead. That is the point at which Robolectric or an
instrumented test becomes the right answer. Noted so the decision is revisited deliberately rather
than inherited by accident.

---

## R-4: What is worth covering first?

**Reasoning**: the honest constraint is that most SipSense logic currently sits inside an Activity or a
Fragment — validation rules in `RegisterActivity`, hydration handling in `ProfileFragment` — which a
local unit test cannot reach without instantiating Android components. The constitution already flags
that as baseline debt. Extracting it is a refactor with genuine regression risk and belongs to its own
feature.

**Decision**: cover what is already isolated and genuinely valuable:

1. **The database-resolution failure path** — the specific debt T021 named, and a real failure path.
2. **The resolution success path** — without it, the failure test could pass while resolution is broken
   in every case, which is a vacuous green.
3. **The profile model's defaults** — a real integrity constraint that the model's own documentation
   states ("All default values are required for Firebase deserialization") and that nothing enforces.

**Explicitly not covered, and why**: the two screens' internal logic (needs a refactor, Out of Scope);
anything requiring a device, network, or provisioned database (FR-003 forbids); UI behaviour (excluded
by the Manual Testing Boundary).

**Guard against vacuous tests**: SC-003 requires demonstrating that deliberately breaking a covered
behaviour makes the suite fail. A test that cannot fail is documentation, not verification, so this is
verified rather than assumed — see quickstart Scenario 4.
