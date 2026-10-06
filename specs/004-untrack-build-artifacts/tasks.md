# Tasks: Untrack Generated Build Artifacts

**Feature**: [spec.md](spec.md) | **Plan**: [plan.md](plan.md) | **Date**: 2026-09-30

Dependency-ordered. Short list, because the implementation is a single Git index operation and almost
all the work is proving it did exactly what was intended and nothing more.

---

## Phase 1: Capture the before state

- [X] T001 Record tracked counts for `app/build`, `.gradle`, and the repository total, so SC-001 and
      SC-002 can be measured rather than asserted. (SC-001, SC-002)
- [X] T002 Record the **on-disk** file counts for `app/build` and `.gradle`. This is the baseline that
      proves nothing was deleted. (SC-006, FR-003)
- [X] T003 Confirm `.gitignore` already matches both paths using `git check-ignore --no-index -v`, so no
      rule is added unnecessarily. (FR-004, research R-1)

## Phase 2: Untrack

- [X] T004 Stop any active Gradle daemon with `gradlew --stop` so no process holds a handle on a
      `.gradle/` lock file while the index is rewritten. (research R-3)
- [X] T005 Run `git rm -r --cached app/build .gradle`. **`--cached` is mandatory** — without it the same
      command deletes 496 files from disk. (FR-001, FR-002, FR-003, research R-2)
- [X] T006 Confirm `.gitignore` was **not** modified. This feature changes no ignore rule, and a diff on
      `.gitignore` would mean something unintended happened. (FR-004)

## Phase 3: Verify the untracking did exactly what was intended

- [X] T007 Tracked counts for `app/build` and `.gradle` are both **0**, and the repository total is about
      **81**. (SC-001, SC-002, quickstart Scenario 1)
- [X] T008 On-disk counts match T002 exactly and the debug APK is still present — **zero files deleted**.
      (SC-006, FR-003, quickstart Scenario 2)
- [X] T009 Plain `git check-ignore -v` now reports the matching rule for both paths, which also proves
      they left the index. (FR-004, quickstart Scenario 4)
- [X] T010 No tracked file contains an API-key-shaped string, `firebase_database_url`, or
      `sipsense-17b6c`. Counts only; never print a key value. (SC-004, US2, quickstart Scenario 5)
- [X] T011 `app/google-services.json` is still untracked and still ignored. (FR-006)
- [X] T012 No file under `app/src/`, no Gradle build script, no wrapper file, and no documentation file
      is newly modified or deleted. (FR-005, quickstart Scenario 6)

## Phase 4: Verify the build is unaffected

- [X] T013 `.\gradlew.bat :app:assembleDebug` — `BUILD SUCCESSFUL` **and exit code 0**. Check the exit
      code explicitly; research R-3 recorded a run that printed success and then exited non-zero.
      (FR-007, SC-005)
- [X] T014 `.\gradlew.bat :app:lintDebug` — `BUILD SUCCESSFUL`, 41 warnings, **0 errors**, matching the
      established baseline. (FR-007, SC-005)
- [X] T015 `.\gradlew.bat :app:testDebugUnitTest` — `BUILD SUCCESSFUL`, **6 tests, 0 failures**.
      (FR-007, SC-005)
- [X] T016 Run a build and then confirm `git status` reports **no** `app/build/` or `.gradle/` path, even
      though the build just rewrote artifacts. This is the user-visible payoff. (SC-003, FR-008,
      quickstart Scenario 3)

## Phase 5: Report what must not be done automatically

- [X] T017 Confirm nothing was committed and the change is left staged for developer review. (FR-009,
      quickstart Scenario 8)
- [X] T018 Report the API-key-shaped values remaining in **committed history**, without rewriting
      history and without rotating credentials. Both are explicitly forbidden. (FR-010, research R-4)
- [X] T019 Report the Gradle lock-error root cause: primarily Android Studio / CLI daemon contention on
      a shared project cache, aggravated by the lock files being tracked. State plainly which part this
      feature fixes and which it does not. (research R-3)
- [X] T020 Report the 19 remaining tracked-but-ignored files (`.idea/` 11, `.vs/` 7,
      `local.properties` 1) as a separate decision, outside this feature's requested scope. (spec Out of
      Scope, Principle X)

---

## Execution Summary — 2026-09-30

**Status**: all 20 tasks complete. Automated verification passed. Nothing committed.

### Measured results

| Measure | Before | After | Target |
| --- | --- | --- | --- |
| Tracked under `app/build/` | 479 | **0** | 0 |
| Tracked under `.gradle/` | 17 | **0** | 0 |
| Tracked files, repository total | 577 | **81** | ~81 |
| Files on disk under `app/build/` | 836 | **836** | unchanged |
| Files on disk under `.gradle/` | 17 | **17** | unchanged |
| Debug APK present | yes | **yes** | unchanged |
| `.gitignore` SHA-256 | `22B490CE…` | **`22B490CE…`** | identical |

**Zero files were deleted** (T008). The `--cached` flag behaved as intended and this was proven by
measurement, not assumed.

**`.gitignore` was not modified** (T006) — byte-identical hash, and Git reports no change. No ignore rule
was added, because the existing rules on lines 18 and 19 already matched; the defect was index
precedence, not the rules. See research R-1.

### Build verification

| Task | Result | Exit code |
| --- | --- | --- |
| `:app:assembleDebug` | BUILD SUCCESSFUL | **0** |
| `:app:lintDebug --rerun-tasks` | BUILD SUCCESSFUL, 41 warnings, **0 errors** | **0** |
| `:app:testDebugUnitTest --rerun-tasks` | BUILD SUCCESSFUL, **6 tests, 0 failures** | **0** |

Lint's 41 warnings / 0 errors matches the established baseline exactly, so nothing regressed. Tests:
`FirebaseDatabaseProviderTest` 3/3 and `UserProfileTest` 3/3. Two of the three runs used
`--rerun-tasks` to force real artifact rewrites rather than accepting `UP-TO-DATE`, and exit codes were
checked explicitly because research R-3 recorded a run that printed success and then exited non-zero.

**T016, the user-visible payoff**: after three builds including two forced full reruns,
`git status` reports **zero** modified or untracked generated files. Only the 496 intentional staged
removals remain, awaiting the developer's commit.

### Verification defect found and fixed

T010's scan initially reported `README.md` as containing a configuration value. Investigation showed the
match was the **project id** `sipsense-17b6c` on line 48 — deliberate setup documentation added in
Feature 002, not a credential, and no `AIza` key matched. A project id tells a developer which Firebase
project to configure and is not a secret, so this is not a Principle VI violation and README was left
unchanged.

The scan itself was validated with a **positive control** (37 tracked files matched a string known to be
present) before its "no hits" result was trusted. This guards against the failure seen earlier in this
project, where a broken `Select-String` invocation produced zero matches that read identically to a pass.

### Reported, not remediated

- **T018 — history exposure**: `app/build/…/packageDebugResources/values/values.xml` contains **2**
  API-key-shaped strings at `HEAD`, so the key is in committed history. Untracking prevents future
  exposure and cannot undo past exposure. History rewriting and key rotation are both explicitly out of
  scope and are the developer's decision.
- **T019 — Gradle lock errors**: primarily Android Studio's Gradle daemon contending with the CLI
  wrapper over the shared project-local cache; aggravated by the four `.lock` files having been tracked.
  This feature removes the aggravating cause only. See research R-3.
- **T020 — 19 further tracked-but-ignored files**: `.idea/` (11), `.vs/` (7), `local.properties` (1).
  Outside the requested scope; `local.properties` deserves particular attention since it contains a
  machine-specific SDK path.

## Out of Scope — do not address in this feature

Restated so the task list cannot drift: rewriting Git history, rotating or revoking the Firebase API
key, committing anything, untracking `.idea/` / `.vs/` / `local.properties`, any change to application
behaviour, architecture, dependencies, or Firebase security rules, baseline findings **R1** and **R3**,
the 18 baseline open questions, and Feature 002's outstanding manual verification (T017, T019, T020).

**The hard boundary**: `--cached` on the `git rm`. Omitting it converts this feature from a safe index
change into the deletion of 496 files.

---

## Phase 6: Convergence — 2026-09-30

**Verdict: CONVERGED.** No unbuilt work remains within scope, so no new tasks are appended.

Assessment performed against spec, plan, and tasks:

- **All 10 functional requirements and all 6 success criteria pass** with measured evidence. See
  [checklists/requirements.md](checklists/requirements.md).
- **Swept beyond the two named paths** for anything missed: no tracked file remains under any
  `build/`, `.gradle/`, `.cxx/`, `out/`, `bin/`, or `obj/` directory anywhere in the repository.
- **Tracked inventory is now 81 files** and accounted for: `app/` 49, `.idea/` 11, `.vs/` 7, `Images/` 3,
  `gradle/` 2, and 9 individual root files. The only entries that are not deliberate source or
  configuration are the 19 out-of-scope IDE files already reported in T020.
- **Features 001–003 are intact.** Verified present: the wrapper (`gradlew`, `gradlew.bat`,
  `gradle-wrapper.jar`), `.gitattributes`, `FirebaseDatabaseProvider.kt`, both test classes, and
  `app/google-services.json` (still ignored). The full test suite passes, which exercises Feature 002's
  provider and Feature 003's infrastructure together.

**Left deliberately incomplete, by instruction**: the 496 removals are **staged and uncommitted**
(FR-009). Committing is the developer's action.

