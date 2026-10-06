# Tasks: Config-Driven Database Connection

**Feature**: [spec.md](spec.md) | **Plan**: [plan.md](plan.md) | **Date**: 2026-09-30

Dependency-ordered. `[P]` marks tasks that could run in parallel; there are few, because the change
is deliberately narrow and sequential.

---

## Phase 1: Confirm the change surface

- [x] T001 Re-confirm that only `RegisterActivity.kt` and `ProfileFragment.kt` obtain a database
      handle, by searching `app/src` for `FirebaseDatabase`, `firebaseio`, `dbUrl`, and
      `DatabaseReference`. If a third site exists, stop and update the plan before editing. (research
      R-3)
      → **PASS.** Exactly two files, five lines: `RegisterActivity.kt:67,68,136,155,156` and
      `ProfileFragment.kt:37,75`. No third site. `LoginActivity.kt` holds Auth only.
- [x] T002 [P] Record the pre-change lint baseline so Scenario 1 can detect regressions: note the
      total issue count and the set of warning categories from
      `app/build/reports/lint-results-debug.xml`. (SC-005)
      → Baseline: **41 issues, all Warning, 0 errors.** Categories: `ContentDescription` 8,
      `IconLocation` 7, `UnusedResources` 7, `GradleDependency` 5, `HardcodedText` 3,
      `LockedOrientationActivity` 3, `DiscouragedApi` 3, `SetTextI18n` 1, `OldTargetApi` 1,
      `UnusedAttribute` 1, `IconLauncherShape` 1, `TypographyDashes` 1.

## Phase 2: Add the single resolution point

- [x] T003 Create `app/src/main/java/com/sipsense/app/data/FirebaseDatabaseProvider.kt` — an `object`
      exposing one function returning `DatabaseReference?`, which obtains the database with **no URL
      argument**, catches the SDK's resolution failure, returns `null`, and logs one actionable
      diagnostic. (FR-001, FR-004, plan Phase 1)
      → **DONE.** `reference()` calls `FirebaseDatabase.getInstance()` with no argument, returns
      `DatabaseReference?`, never throws. Matches the project's existing file-header KDoc style.
- [x] T004 Verify the diagnostic wording names the cause and the corrective action and contains no
      API key, project number, client secret, or configuration contents. (FR-004, Principle VI)
      → **PASS.** The message names the missing Realtime Database and the three corrective steps
      (create the database, re-download the file, rebuild). It interpolates nothing — no project id,
      no project number, no key, no file contents. Only the caught exception is attached.

## Phase 3: Update the call sites

- [x] T005 `ProfileFragment.kt` — replace the URL literal at line 75 with the provider; drop the now
      unused `FirebaseDatabase` import. (FR-001, FR-002, FR-006)
      → **DONE.** Import replaced with `com.sipsense.app.data.FirebaseDatabaseProvider`.
- [x] T006 `ProfileFragment.loadUserProfile()` — return early showing the existing
      "Failed to load profile" message when the reference is unresolved. Do not introduce new UI.
      (FR-004, FR-007, Principle X)
      → **DONE.** Reuses the exact string already shown in `onCancelled`. No new UI.
- [x] T007 `ProfileFragment.saveHydrationTarget()` — return early showing the existing
      "Failed to update target" message when the reference is unresolved. (FR-004, FR-007)
      → **DONE.** Reuses the string already shown in `addOnFailureListener`. Also removed trailing
      whitespace on the line that was edited.
- [x] T008 `RegisterActivity.kt` — change `database` from `lateinit var DatabaseReference` to a
      nullable `var`, and set it from the provider in `onCreate`, removing the `dbUrl` literal at
      lines 155-156. (FR-001, FR-002, plan Complexity Tracking)
      → **DONE.** Now `private var database: DatabaseReference? = null`, assigned in `onCreate`.
- [x] T009 `RegisterActivity` profile write at line 629 — guard on the nullable reference; when
      unresolved, log the diagnostic and continue to navigate to Login exactly as before. **Preserve
      the existing `printStackTrace()` failure handler as-is** — that is baseline finding R3 and is
      Out of Scope. (FR-004, FR-007)
      → **DONE.** `printStackTrace()` handler preserved byte-for-byte. `navigateToLogin()` remains
      outside the branch, so navigation is unchanged on every path.
- [x] T010 Confirm no other behaviour changed in either file: no validation rule, no layout, no
      navigation, no data shape, no field name. (FR-005, FR-007)
      → **PASS**, by reading the full `git diff` of `app/src`. The diff contains only: 3 import
      lines, 2 field declarations, 2 initialisations, 3 null guards, 1 added log branch, and 2
      comment updates. No validation, layout, navigation, `UserProfile` field, or database path was
      touched. `/users/{uid}` and `hydrationTarget` are unchanged.

## Phase 4: Documentation

- [x] T011 `README.md` — state that the configured Firebase project must have a Realtime Database,
      and that the app resolves the database from `google-services.json`. Correct the step-3 text that
      currently names `sipsense-17a90` as the project to download from, since that identifier is the
      one being removed and now contradicts the configuration in use. (FR-008)
      → **DONE.** Prerequisite now requires an Android app for `com.sipsense.app` **and** a Realtime
      Database. Step 3 names `sipsense-17b6c` as the project currently in use, with a note to update
      that line if it changes since nothing in source pins it any more. The wrong-project check no
      longer asserts a `project_id`, only the package name. New **step 3b** explains the
      config-driven resolution, gives the `firebase_url` check, quotes the exact logcat diagnostic,
      and gives the create-then-re-download fix, warning that a stale copy still lacks the URL.

## Phase 5: Automated verification

- [x] T012 Run `.\gradlew.bat :app:assembleDebug`. Must report `BUILD SUCCESSFUL`. (FR-009, SC-005,
      quickstart Scenario 1)
      → **PASS. BUILD SUCCESSFUL in 25s**, 34 actionable tasks, 6 executed. `:app:compileDebugKotlin`
      and `:app:packageDebug` both ran, so the new source genuinely compiled rather than being served
      from cache.
- [x] T013 Run `.\gradlew.bat :app:lintDebug`. Must report `BUILD SUCCESSFUL` with no errors, no new
      warning category, and no increase over the T002 baseline. (FR-009, SC-005)
      → **PASS. BUILD SUCCESSFUL in 19s.** **41 issues, all Warning, 0 errors** — identical count and
      identical category distribution to the T002 baseline. The added file introduced no warning.
- [x] T014 Run the quickstart Scenario 2 source search. Must return zero matches. (FR-002, SC-002)
      → **PASS**, after correcting a defective check — see the note below. Zero matches for
      `firebaseio`, `firebasedatabase.app`, and `sipsense-1` across 42 `.kt`/`.xml` files, confirmed
      by two independent tools, with a positive control proving the search matches known strings.
- [x] T015 Run quickstart Scenario 3 by inspection, and the negative check confirming
      `app/google-services.json` is still untracked. (FR-003, Principle VI)
      → **PASS.** The only database handle in the codebase is
      `FirebaseDatabase.getInstance()` at `FirebaseDatabaseProvider.kt:47`, with no URL argument;
      `FirebaseAuth.getInstance()` likewise takes no project argument, so both derive from the same
      bundled configuration. `git check-ignore -v` reports `.gitignore:2` matches the config, and
      `git ls-files --error-unmatch` fails with "did not match any file(s) known to git", confirming
      it is not committed.

### Verification defect found and fixed during Phase 5

`Select-String -Path app\src -Include *.kt -Recurse` was used for the first T014 attempt.
`Select-String` has **no `-Recurse` parameter**, so it threw a parameter-binding error, matched
nothing, and printed "ZERO MATCHES - PASS" — a false pass that looked identical to a real one. The
check was re-run as `Get-ChildItem -Recurse | Select-String`, cross-checked with a second search
tool, and given a positive control against a string known to be present. Both agreed on zero
matches, so FR-002 genuinely holds. Quickstart Scenario 2 was corrected and now carries an explicit
warning about this trap, because the failure mode is silent and would let a future regression pass.

## Phase 6: Hand off what only the developer can do

- [x] T016 **EXTERNAL — blocks SC-001.** Create a Realtime Database in project `sipsense-17b6c`,
      re-download `app/google-services.json` so it carries `firebase_url`, and rebuild. Confirm with
      the snippet in quickstart Scenario 5. Until this is done the app has no database to reach and
      US1 cannot pass.
      → **COMPLETE (verified 2026-09-30, 15:10).** The developer created the Realtime Database and
      supplied a fresh configuration. All three gate checks now pass:

      | Check | Result |
      | --- | --- |
      | `project_info.project_id` = `sipsense-17b6c` | **PASS** |
      | Android client package = `com.sipsense.app` | **PASS** |
      | `project_info.firebase_url` present | **PASS** — `https://sipsense-17b6c-default-rtdb.firebaseio.com` |

      `project_info` keys are now `project_number`, `firebase_url`, `project_id`, `storage_bucket` —
      `firebase_url` is present where it was absent on all three prior attempts.

      **Verified against the build, not only the file.** The generated output was deleted and
      `:app:processDebugGoogleServices` forced to re-run at 15:10. The regenerated
      `app/build/generated/res/processDebugGoogleServices/values/values.xml` now contains:

      ```xml
      <string name="firebase_database_url" translatable="false">https://sipsense-17b6c-default-rtdb.firebaseio.com</string>
      ```

      That string resource is precisely what `FirebaseApp` reads and what the no-argument
      `FirebaseDatabase.getInstance()` resolves, so `FirebaseDatabaseProvider.reference()` will now
      return a real reference. The database and Firebase Auth both resolve to `sipsense-17b6c`, which
      is what **FR-003** requires — and it is now true in provisioning as well as in code.

      The URL is in the **default** region (`firebaseio.com`), so no regional-hostname edge case applies.
      → **STILL OUTSTANDING (verified 2026-09-30, 14:45).** A fresh `app/google-services.json` was
      supplied (674 bytes, modified 14:40:25) and it is correct on two of three counts, but the
      database URL is still missing:

      | Check | Result |
      | --- | --- |
      | `project_info.project_id` = `sipsense-17b6c` | **PASS** |
      | Android client package = `com.sipsense.app` | **PASS** (1 client) |
      | Realtime Database URL present | **FAIL** — the `firebase_url` key is absent entirely |

      `project_info` contains only `project_number`, `project_id`, and `storage_bucket`.

      **Confirmed against the build, not just the file.** `:app:processDebugGoogleServices` reported
      `UP-TO-DATE` for the new file, which means Gradle's content hash was unchanged — the new
      download is effectively identical to the previous one. To rule out a stale artifact, the
      generated output was deleted and the task forced to re-run at 14:45; the regenerated
      `app/build/generated/res/processDebugGoogleServices/values/values.xml` contains
      `gcm_defaultSenderId`, `google_api_key`, `google_app_id`, `google_crash_reporting_api_key`,
      `google_storage_bucket`, and `project_id` — and **no `firebase_database_url`**.

      **Consequence**: `FirebaseDatabase.getInstance()` still has no URL to resolve, so
      `FirebaseDatabaseProvider.reference()` still returns `null` and profiles still cannot be saved
      or loaded. This is the defined, logged degradation FR-004 requires — the code is behaving
      correctly; the project is not yet provisioned.

      **Most likely causes, in order** — a developer decision, not an agent one:
      1. **Cloud Firestore was created instead of Realtime Database.** The two sit adjacent under
         **Build** in the Firebase console and are easily confused. Firestore adds nothing to
         `project_info`, which matches exactly what is observed.
      2. The config was downloaded before the database finished provisioning. Re-downloading now
         would fix it.
      3. The database exists but the downloaded file came from a cached console session.

      **To confirm which**: open the Firebase console for `sipsense-17b6c` → **Build**. If
      **Realtime Database** shows "Create Database" rather than a data viewer with a
      `https://...firebasedatabase.app` URL at its top, it was never created. SC-001 and US1 remain
      unverifiable until that URL appears in the config.
- [ ] T017 **EXTERNAL.** Confirm the `/users/{uid}` security rules in that project allow the owning
      authenticated user to read and write their own record. Console-only; outside this repository.
      (spec Assumptions)
      → **RESULT NOT RECORDED (2026-09-30 15:28).** The developer reported having performed manual
      testing and supplied a results template, but the result field arrived as the unfilled
      placeholder `[PASTE HERE WHETHER THE FIREBASE SECURITY RULES ALLOWED THE EXPECTED USER ACCESS]`.
      No outcome is recorded, because recording one would mean inventing it. **This task remains
      open.** See the shared note under T020.
      → **AWAITING MANUAL RESULT** — Firebase console check. Needed for SC-001/US1 validation.
- [~] T018 **MANUAL.** Run quickstart Scenario 4 — the understandable-failure path. Best captured
      *before* T016, since creating the database makes it unreproducible.
      → **NO LONGER REPRODUCIBLE, and now covered automatically instead.** The task warned that
      creating the database would close this window, and T016 has now done so: with `firebase_url`
      present, resolution succeeds and the failure branch cannot be reached by running the app. It
      was not captured manually before T016 and **is not claimed to have been.**

      **FR-004 is nevertheless verified**, by Feature 003's automated tests rather than by this manual
      scenario:

      | Behaviour | Covering test |
      | --- | --- |
      | no reference returned when the URL cannot be determined | `returns null when the configured project has no database` |
      | no exception escapes on an unanticipated failure | `returns null when resolution fails unexpectedly` |
      | a configured database yields its root reference | `returns the root reference when a database is configured` |

      Those tests were proven capable of failing by the mutation testing in Feature 003 T013, so this
      is genuine coverage and not a paper substitution. The automated form is also strictly better
      than the manual scenario, because it remains runnable forever, whereas the manual scenario was
      only reproducible while the project was misconfigured.

      **Residual gap**: the tests cover the provider, not the two screens' reaction to a null
      reference. That is tracked as Feature 003 T019 and is unchanged by this.
- [ ] T019 **MANUAL.** Run quickstart Scenario 5 — register, then confirm the record in the Firebase
      console, not only in the UI. (SC-001, US1)
      → **RESULT NOT RECORDED (2026-09-30 15:28).** The result field arrived as the unfilled
      placeholder `[PASTE HERE WHETHER /users/{uid} WAS CREATED]`. **This task remains open.**
      → **AWAITING MANUAL RESULT** — register a user on a device, then confirm `/users/{uid}` in the
      Firebase console. Blocks SC-001.
- [ ] T020 **MANUAL.** Run quickstart Scenario 6 — swap the configuration and confirm both sign-in
      and data follow it. (SC-003, US2)
      → **RESULT NOT RECORDED (2026-09-30 15:28).** The result field arrived as the unfilled
      placeholder `[PASTE HERE WHETHER THE DIFFERENT-FIREBASE-CONFIG TEST PASSED]`.
      **This task remains open.**
      → **AWAITING MANUAL RESULT** — swap in a different `google-services.json` and confirm auth and
      data both follow it. Blocks SC-003.

### Note on the 2026-09-30 15:28 manual-results submission

All three manual result fields (T017, T019, T020) were submitted as unfilled
`[PASTE HERE …]` placeholders. The accompanying instruction was explicit — *"Use the following
results exactly as provided"* and *"Do not invent results"* — and the results provided were empty.

Nothing was recorded for any of the three. No outcome was inferred from the fact that testing was
reported as performed, because "I tested it" does not disclose what happened, and Constitution
Principle I forbids resolving that ambiguity by assumption. The Manual Testing Boundary is also
unambiguous: *"Never claim manual testing was completed."*

**Consequence for convergence**: SC-001 (US1) and SC-003 (US2/AC1) remain unverified, so Feature 002
is **not** fully validated. Its *automated* verification remains complete and unaffected — no
automated result depends on these three items.

**To close them**, re-supply the three results with actual outcomes. For T019, the useful answer
states whether a node appeared at `/users/{uid}` in the `sipsense-17b6c` Realtime Database and
whether the Profile tab displayed the saved name and hydration target. If T019 failed, the verbatim
logcat line or console error matters, because a permission-denied failure points at T017's security
rules — a configuration issue — rather than at application code.

---

## Execution Summary

**Updated 2026-09-30 15:10 — the Realtime Database now exists, so T016 is complete.**

**17 of 20 tasks complete. Every automated criterion passes and the database is provisioned. The
3 remaining items are external or manual by nature; none is blocked by unfinished implementation.**

| Requirement | Status | Evidence |
| --- | --- | --- |
| FR-001 resolve from config | **PASS** | `FirebaseDatabase.getInstance()`, no URL argument |
| FR-002 no URL/project literal in source | **PASS** | zero matches over 42 files, two tools, positive control |
| FR-003 Auth and DB same project | **PASS, now provisioned** | build generates `firebase_database_url` for `sipsense-17b6c`; every `FirebaseAuth.getInstance()` also takes no project argument, so neither can diverge |
| FR-004 defined behaviour when unresolvable | **PASS** (automated) | nullable provider + 3 guarded sites, covered by 3 Feature 003 tests proven fail-capable by mutation; supersedes the now-unreproducible T018 |
| FR-005 data shape unchanged | **PASS** | `UserProfile` untouched; `/users/{uid}` path unchanged |
| FR-006 both call sites updated | **PASS** | T005–T009, change surface closed by T001 |
| FR-007 no other behaviour change | **PASS** | full diff reviewed in T010 |
| FR-008 documentation | **PASS** | README prerequisite + step 3 + new step 3b |
| FR-009 build and lint clean | **PASS** | BUILD SUCCESSFUL twice; 41 warnings, 0 errors, no change |
| SC-001 profile stored and read back | **UNBLOCKED, awaiting manual run** | T016 done, so the database exists; T019 needs a device and the Firebase console |
| SC-002 zero source matches | **PASS** | T014, re-verified 15:10 across 44 files |
| SC-003 config swap moves projects | **MANUAL** | T020 |
| SC-004 understandable failure | **PASS** (automated) | Feature 003 tests; T018's manual window closed when the database was created |
| SC-005 build and lint no worse | **PASS** | identical lint profile to baseline |

**Files changed**: `RegisterActivity.kt`, `ProfileFragment.kt` (modified), `README.md` (modified),
`app/src/main/java/com/sipsense/app/data/FirebaseDatabaseProvider.kt` (added). Net ~25 lines of
behaviour change.

**Baseline finding R2 is now fully resolved.** It was resolved in code when the literals were removed;
as of T016 the configuration it defers to actually has an answer. The app asks the configuration where
the database is, and the configuration says `sipsense-17b6c` — the same project Auth uses. Finding
**T028** (the project-identity conflict) is therefore closed as well: there is no longer a second
project involved anywhere in the build.

---

## Phase 7: Convergence

Appended 2026-09-30 by the convergence assessment. Outcome: `tasks_appended` — **1 finding**.

Checked: 9 functional requirements, 5 success criteria, 7 user-story acceptance scenarios, 4 plan
decisions, 11 constitution principles. Findings by gap type: 1 `missing`, 0 `partial`,
0 `contradicts`, 0 `unrequested`.

- [x] T021 **CRITICAL** Establish automated test infrastructure and cover the database-resolution
      failure path with a test, per Constitution VII (`missing`)
      → **COMPLETE.** Delivered by **Feature 003 (Automated Test Infrastructure)**, as this task
      recommended. `app/src/test/java/` now exists with JUnit 4 and MockK declared
      `testImplementation`-only, and `:app:testDebugUnitTest` runs 6 tests with 0 failures.
      Three of them cover exactly the path this task named — resolution failing, resolution
      succeeding, and resolution failing unexpectedly. Feature 003 T013 proved all three are capable
      of failing by mutating the production code and confirming each mutation was caught by the
      correct test, so this is real coverage rather than a checkbox.
      **Residual**: the two screens' reaction to a null reference is still uncovered, tracked as
      Feature 003 T019. Not duplicated here.
      → **Evidence**: `app/src/test` and `app/src/androidTest` are both absent, and
      `app/build.gradle.kts` declares no `testImplementation` or `androidTestImplementation`
      dependency at all. This feature added a new failure path — `FirebaseDatabaseProvider.reference()`
      returning `null`, and the three call sites that handle it — and none of it is covered by an
      automated test.

      **Why this is CRITICAL rather than deferred quietly**: Principle VII states that
      "Failure-path tests are part of the feature, not a follow-up," and that a feature
      "MUST NOT be considered complete merely because the application compiles." Feature 002
      compiles, lints clean, and satisfies every functional requirement, but it does not satisfy
      Principle VII. The plan's Complexity Tracking explains why the deviation was taken; an
      explanation is not a cure, so the obligation is recorded here rather than closed.

      **Scope warning — do not absorb this into Feature 002.** Satisfying it requires adding a test
      source set, a test dependency, and a harness able to exercise Firebase initialisation. That is
      infrastructure work affecting the whole project, and the workflow document assigns it to a
      dedicated feature: "The first feature that needs unit tests must add the test dependencies and
      source set as part of its plan, not as an unplanned side change." Doing it here would also
      breach Principle X. **Recommended: run this as Feature 003**, with Feature 002's provider as
      its first subject.

---

## Phase 8: Convergence

Appended 2026-09-30 15:15, after T016 completed and all automated checks were re-run. Outcome:
`tasks_appended` — **1 finding**, and it is a security finding **outside this feature's scope**.

Checked: 9 functional requirements, 5 success criteria, 7 user-story acceptance scenarios, 4 plan
decisions, 11 constitution principles. Findings by gap type: 0 `missing`, 0 `partial`,
1 `contradicts`, 0 `unrequested`.

- [X] T022 **CRITICAL** Stop tracking build artifacts that embed the Firebase API key, per
      Constitution VI (`contradicts`)
      → **RESOLVED 2026-09-30 by [Feature 004](../004-untrack-build-artifacts/tasks.md)**, handled
      as a separate repository-hygiene change so it would not be mixed into this feature.
      `git rm -r --cached app/build .gradle` removed 496 index entries, dropping the tracked set
      from 577 files to 81 with **zero files deleted from disk**. No tracked file now contains an
      API-key-shaped string. `.gitignore` needed no change — it was already correct, and the actual
      cause was that Git gives the index precedence over ignore rules.
      **Two items remain the developer's decision, deliberately not automated**: the removals are
      staged but **not committed**, and the 2 API-key-shaped values already in committed history are
      reported rather than remediated (no history rewrite, no key rotation).

      → **Discovered while verifying this feature's "no secrets staged for commit" condition**
      *(original finding, retained for traceability)*.
      `app/google-services.json` is correctly untracked, but **479 files under `app/build/` are
      tracked** (baseline finding **R10**), and several of them contain the values copied out of that
      configuration. Counting only, never printing:

      | Tracked file | Contains |
      | --- | --- |
      | `app/build/intermediates/packaged_res/debug/packageDebugResources/values/values.xml` | **2 API-key-shaped (`AIza…`) strings**, plus `firebase_database_url` and 3 occurrences of `sipsense-17b6c` |
      | `app/build/intermediates/incremental/debug/{mergeDebugResources,packageDebugResources}/merger.xml` | resource values including `google_api_key` |
      | `.../R.txt`, `.../R-def.txt`, `.../stableIds.txt`, `.../package-aware-r.txt` | resource **names** only, not values |

      **A Firebase API key is already committed**: `git show HEAD:<packaged_res values.xml>` contains
      2 `AIza…` strings, so this predates the current work and is in history, not merely in the
      worktree. The worktree copy is modified and now carries the **current** project's key and
      database URL, so committing that file would add them too.

      **Proportionate severity.** A Firebase Android API key is not a server secret — it ships inside
      every APK by design and is protected by database security rules and App Check rather than by
      being hidden. So this is not an emergency credential leak. But Constitution VI says plainly that
      keys MUST NOT be exposed in source, tracked build output is part of the repository, and the key
      being in history is exactly the state the principle exists to prevent. It is graded CRITICAL
      because it contradicts a MUST principle, not because exploitation is imminent.

      **Why not fixed here**: R10 is explicitly Out of Scope for Feature 002, none of these files is
      one this feature touches, and untracking 479 files is a repository-wide change that Principle X
      forbids doing as a side effect. Remediating history is also a decision with consequences for
      anyone who has cloned the repo.

      **Recommended as its own feature**, covering: adding `app/build/` and `.gradle/` to
      `.gitignore`, `git rm -r --cached` for both, and a decision on whether to rotate the Firebase
      API key and whether to rewrite history. Rotation is a Firebase Console action and cannot be done
      automatically. Untracking `.gradle/` would also remove the cause of the
      `Failed to release lock on …` failures seen repeatedly during verification, one of which
      produced a **non-zero exit code after printing `BUILD SUCCESSFUL`** — a false signal that could
      mislead future automated verification.

### Already-tracked gaps — deliberately not duplicated as new tasks

As of 2026-09-30 15:15, SC-004 and US3/AC1-2 are now **verified automatically** by Feature 003's
tests, and SC-001 is **unblocked**. What remains unverified is SC-001/US1 (T019) and SC-003/US2/AC1
(T020) — both needing a real device plus the Firebase console — and T017, the security-rules check,
which is console-only. Each is already an existing task, so none is reissued here.

The residual coverage gap on the two screens' null-reference handling is tracked as Feature 003 T019
and is not duplicated.

**The implementation itself has no remaining gaps.** FR-001 through FR-009 are all satisfied and
independently re-verified after the configuration changed: build, lint, 6 passing tests, a source
search over 44 files, a forced re-processing of the Google Services configuration, and a review of
every changed file. T022 is a repository-hygiene and security finding surfaced by this feature's
verification, not a defect in this feature's implementation.

## Out of Scope — do not address in this feature

Restated so the task list cannot drift: baseline findings **R1** (hydration range conflict crashing
the Profile tab), **R3** (swallowed registration write failure), **R10** (tracked build artifacts),
the 18 baseline open questions, automated test infrastructure, database security rules as code, and
any change to hydration tracking, history, device/IoT behaviour, or UI layout.
