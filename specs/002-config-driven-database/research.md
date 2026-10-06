# Phase 0 Research: Config-Driven Database Connection

**Feature**: [spec.md](spec.md) | **Date**: 2026-09-30

Four decisions had to be settled before the design in [plan.md](plan.md) was fixed. Each is recorded
with the evidence gathered, not merely the conclusion.

---

## R-1: How is the database URL supposed to be resolved from configuration?

**Question**: If the URL literal is removed, what does the app use instead, and where does that value
come from?

**Evidence gathered**:

- `app/google-services.json` was inspected, printing only non-secret fields
  (`project_id`, `project_number`, `firebase_url`, `storage_bucket`, `package_name`). Result:

  ```text
  project_id           : sipsense-17b6c
  project_number       : 665836416312
  firebase_url         : (key absent)
  storage_bucket       : sipsense-17b6c.firebasestorage.app
  packages             : com.sipsense.app
  client count         : 1
  ```

- The `com.google.gms.google-services` Gradle plugin reads this file at build time and generates
  string resources consumed by `FirebaseApp` during automatic initialisation. When `firebase_url` is
  present it becomes the default database URL, which the no-argument
  `FirebaseDatabase.getInstance()` then returns.

**Decision**: Use the no-argument `FirebaseDatabase.getInstance()`. It resolves the URL from the
initialised `FirebaseApp`, which in turn comes from `google-services.json`. This is the mechanism
that makes FR-003 structurally true rather than true by coincidence — Auth and Database then read
the same source.

**Consequence that shaped the whole design**: because the `firebase_url` key is **absent**, not merely
empty, the no-argument call has nothing to resolve. The Firebase SDK's documented behaviour in that
situation is to raise a `DatabaseException` explaining that it cannot determine the database URL. So
the naive one-line edit — deleting the argument — would convert today's silent cross-project failure
into a hard failure at screen construction. That is what R-2 addresses.

---

## R-2: What must happen when the URL cannot be resolved?

**Question**: The configured project has no database today. What is the defined behaviour?

**Why this cannot be left undefined**: Principle III requires defined behaviour for missing and
invalid data and forbids assuming the backend is available. Principle IV requires data integrity.
An unexplained crash satisfies neither, and a silent no-op reproduces the defect class this feature
removes.

**Options considered**:

| Option | Behaviour when unresolvable | Rejected / chosen |
| --- | --- | --- |
| Let the exception propagate | `ProfileFragment` builds its reference in a property initialiser, so the exception is thrown during fragment instantiation — the Profile tab dies before any error handler exists. `RegisterActivity` dies in `onCreate`. | **Rejected.** Directly violates FR-004. |
| Catch and return a placeholder reference | Calls would be issued against a meaningless path and fail asynchronously, or appear to succeed locally. | **Rejected.** Silent wrong behaviour — the exact failure mode this feature exists to eliminate. |
| Catch and return `null`, log once, let each call site show its existing failure message | Non-fatal, visible, diagnosable, and reuses existing UI. | **Chosen.** |
| Fall back to a hardcoded URL if config lacks one | Would reintroduce the literal FR-002 removes. | **Rejected.** Self-defeating. |

**Decision**: Centralise resolution in one provider that returns `DatabaseReference?` and logs an
actionable diagnostic naming the missing Realtime Database and the corrective action. Each call site
handles `null` with the message it already displays.

**Security note**: the diagnostic states *that* no database URL is configured and what to do. It must
never log the configuration file's contents, the project number, or any API key — Principle VI.

---

## R-3: Where should the resolution live?

**Question**: One shared helper, or an independent fix in each of the two files?

**Evidence gathered**: a source search for `FirebaseDatabase`, `firebaseio`, `dbUrl`, and
`DatabaseReference` across `app/src` returned database handles in exactly two files, with three usage
sites:

```text
RegisterActivity.kt:155-156   URL literal + getInstance(dbUrl).reference
RegisterActivity.kt:629       database.child("users").child(uid).setValue(userProfile)
ProfileFragment.kt:75         URL literal + getInstance(url).reference
ProfileFragment.kt:173        database.child("users").child(uid) -> single-value read
ProfileFragment.kt:207        database.child("users").child(uid).child("hydrationTarget")
```

`LoginActivity.kt` uses `FirebaseAuth` only and holds no database handle, so it needs no change. The
change surface is therefore closed and small.

**Decision**: a single `object FirebaseDatabaseProvider` in a new `com.sipsense.app.data` package.
Duplicating the try/catch in two files would restore the duplication FR-002 removes, and Principle II
asks for data-layer concerns to sit outside Activities and Fragments. The package addition is
justified in the plan's Complexity Tracking.

---

## R-4: How is FR-002 verified without test infrastructure?

**Question**: FR-002 says no source file may contain a database URL or project identifier. What makes
that checkable and repeatable?

**Decision**: a deterministic source search over `app/src`, run as quickstart Scenario 2, for
`firebaseio`, `firebasedatabase.app`, and `sipsense-1`. Zero matches is the pass condition.

**Why this is preferred over an automated test here**: asserting the absence of a string in source is
a property of the repository, not of runtime behaviour. A unit test would need the test source set,
JUnit, and a `FirebaseApp`-capable harness to assert something a search answers exactly and instantly.
This is recorded as the mitigation for the Principle VII deviation, not as a replacement for the test
infrastructure the project still needs.

**Note on the search term `sipsense-1`**: chosen because both conflicting project identifiers
(`sipsense-17a90`, `sipsense-17b6c`) share that prefix, so the check catches a reintroduction of
either one, and of a third.

---

## Open items deliberately not researched

- **Database security rules** for `/users/{uid}` in `sipsense-17b6c`. Configured in the Firebase
  console, not in this repository. Recorded as a spec Assumption because it can independently cause
  US1 to fail.
- **Migrating data out of `sipsense-17a90`.** Recorded as a spec Assumption (treated as
  non-production development data). Reversing that assumption is a requirements decision.
- **Baseline finding R3.** The swallowed write failure remains. Out of Scope, and the reason the spec
  requires US1 to be verified against the database rather than the UI.
