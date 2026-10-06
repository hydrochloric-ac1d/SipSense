---
description: "Task list for Build Infrastructure Baseline"
---

# Tasks: Build Infrastructure Baseline

**Input**: Design documents from `/specs/001-build-infrastructure/`

**Prerequisites**: [plan.md](./plan.md), [spec.md](./spec.md), [research.md](./research.md),
[quickstart.md](./quickstart.md)

**Tests**: No automated test tasks. The spec places test infrastructure out of scope and
`plan.md` Complexity Tracking records the justified Principle VII deviation. Validation is by
executing the restored build entry point per `quickstart.md`.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependencies)
- **[Story]**: US1 = clean-clone build, US2 = automated verification, US3 = understandable failure

## Path Conventions

Single Gradle module `app`. All paths are repository-relative from
`C:\Users\adaaz\StudioProjects\SipSense`.

---

## Phase 1: Preconditions

**Purpose**: establish the environment facts the plan depends on. Stop if any fails.

- [x] **T001** Confirm the JDK is present at `C:\Program Files\Android\Android Studio\jbr` and
      reports version 17 or 21. Blocks everything (research R-2).
      → **PASS**: OpenJDK 21.0.6.
- [x] **T002** Confirm the official Gradle 8.9 distribution is present and validated at
      `%USERPROFILE%\.gradle\wrapper\dists\gradle-8.9-bin\90cnw93cvbtalezasaz0blq0a\gradle-8.9`
      with a `gradle-8.9-bin.zip.ok` marker. This is the FR-009 provenance evidence (research R-1).
      → **PASS**: `.ok` marker present.
- [x] **T003** Record the current contents of `gradle/wrapper/gradle-wrapper.properties` so the
      pinned `distributionUrl` and its rationale comments can be diffed after generation
      (research R-3, FR-002).
      → **PASS**: recorded; diff applied in T006.
- [ ] **T004** Confirm `app/google-services.json` is now present. If absent, T009 and T010 cannot
      pass and must be reported blocked rather than skipped.
      → **BLOCKED**: file still absent from the working tree as of 2026-09-30. Developer action
      required (README step 3). This blocks T010 and T011.

---

## Phase 2: Foundational — restore the build entry point

**Purpose**: the wrapper must exist before anything can be verified.

**⚠️ CRITICAL**: no verification task can run until T005 and T006 are complete.

- [x] **T005** [US1] Generate the wrapper by running the `wrapper` task from the cached official
      distribution with `--gradle-version 8.9`, producing `gradlew`, `gradlew.bat`, and
      `gradle/wrapper/gradle-wrapper.jar` (FR-001, FR-009).
      → **PASS**: `BUILD SUCCESSFUL`; all three files created.
- [x] **T006** [US1] Verify `gradle/wrapper/gradle-wrapper.properties` still pins
      `gradle-8.9-bin.zip`. If the `wrapper` task rewrote the file, restore the original
      explanatory comments and confirm no version changed (FR-002, research R-3).
      → **PASS**: version pin intact. The task did strip the comments as R-3 predicted, and it
      added `networkTimeout=10000` and `validateDistributionUrl=true` (Gradle 8.9 defaults, kept —
      the latter makes the wrapper verify what it downloads). Comments restored.
- [x] **T007** [P] [US1] Verify `gradlew` was written with LF line endings, so the script is
      usable on Linux and macOS (spec edge case, research R-4).
      → **PASS**: 0 CRLF pairs found.
- [x] **T008** [P] [US1] Verify the new wrapper files are not matched by any `.gitignore` rule, so
      they are committable (FR-003).
      → **PASS**: `git check-ignore` returned 1 for all three files.

---

## Phase 3: User Story 1 & 2 — verification (P1)

**Purpose**: prove the restored entry point actually builds and analyses the project.

- [x] **T009** [US1] Run `.\gradlew.bat --version` and confirm it reports Gradle 8.9 without a
      pre-installed Gradle on `PATH` (quickstart scenario 1, FR-001).
      → **PASS**, and stronger than required: the wrapper downloaded the pinned distribution from
      `services.gradle.org` itself, then reported `Gradle 8.9` on Launcher JVM 21.0.6. This
      exercises the full clean-machine path including distribution validation.
- [ ] **T010** [US1] Run `.\gradlew.bat :app:assembleDebug` and confirm `BUILD SUCCESSFUL`, with
      `:app:processDebugGoogleServices` passing (quickstart scenario 2, SC-001).
      → **BLOCKED by T004**: reached `:app:processDebugGoogleServices FAILED` —
      "File google-services.json is missing." The wrapper executed correctly and the build
      progressed through `:app:preBuild` and `:app:preDebugBuild`, so this is the documented B2
      blocker, not a wrapper defect. Re-run once the file is in place.
- [ ] **T011** [US2] Run `.\gradlew.bat :app:lintDebug` and confirm it runs to completion and
      reports a result. Pre-existing findings are recorded, not fixed — fixing them is Out of
      Scope (quickstart scenario 3, SC-002, SC-005).
      → **BLOCKED by T004**: fails at the same `:app:processDebugGoogleServices` task, because lint
      depends on it. No lint findings could be collected.
- [x] **T012** [US2] Confirm no file under `app/src/` was modified during this feature (FR-006).
      → **PASS**: `git status -- app/src` is empty.

---

## Phase 4: User Story 3 — documentation (P2)

**Purpose**: make the unavoidable missing-configuration failure self-serviceable.

- [x] **T013** [US3] Add a Firebase configuration section to `README.md` covering: that
      `app/google-services.json` is required; that it is obtained from the Firebase console for the
      existing project; the exact path it belongs at; and that it must never be committed
      (FR-004, FR-008).
      → **DONE**: new README step 3, placed before "Open in Android Studio" so it cannot be
      missed. Subsequent steps renumbered to 4.
- [x] **T014** [US3] In the same README section, name the observable symptom — the failing
      `:app:processDebugGoogleServices` task and its "File google-services.json is missing"
      message — and state the remedy, plus what to do without Firebase project access
      (FR-005, SC-003, acceptance scenarios 3.1 and 3.2).
      → **DONE**: the console output is quoted verbatim so it is searchable, and the section warns
      against creating a placeholder file (which would build but crash at startup).
- [x] **T015** [P] [US3] Update the README's Getting Started steps so the command-line build path
      is documented alongside the Android Studio path, now that the wrapper exists (FR-001, SC-002).
      → **DONE**: new README step 5 with Windows and Unix invocations, including the `JAVA_HOME`
      workaround for machines with no JDK on `PATH`. Prerequisites also note Gradle is not needed.
- [x] **T016** [P] [US3] Verify `git check-ignore -v app/google-services.json` still reports a
      matching rule (FR-008, quickstart negative check).
      → **PASS**: matched by `.gitignore:2`.

---

## Phase 5: Baseline record accuracy

**Purpose**: prevent the resolved blockers from continuing to misinform future runs. Justified in
`plan.md` Complexity Tracking.

- [x] **T017** Update `.specify/memory/baseline-analysis.md`: mark B1 resolved, update B2 to
      "documented, developer-supplied", and refresh the Verification performed table to record the
      build and lint results from T010 and T011.
      → **DONE**: B1 marked RESOLVED with its verification evidence; B2 marked DOCUMENTED but
      explicitly still an active blocker until the file is present; B3 updated since Gradle is no
      longer needed on `PATH`.
- [x] **T018** Update the Current Architectural Baseline section of
      `.specify/memory/constitution.md` to remove the statement that the wrapper is incomplete and
      that command-line builds cannot run. Factual correction only — no principle changes, so the
      version bump stays PATCH.
      → **DONE**: baseline now states the wrapper is committed and working, and that build and lint
      require the developer-supplied config. Version stays 1.0.1 — this corrects a factual line
      added in that same PATCH revision, so no further bump applies.

---

## Phase 6: Handoff

- [x] **T019** Report results per the `/automate-process` Final Report format, distinguishing
      automated verification from the manual steps in `quickstart.md` scenarios 4 and 5.
      → **DONE**: reported as `MANUAL TESTING REQUIRED`, since T010 and T011 are blocked.
- [x] **T020** Give the developer the exact commands to commit the wrapper with its executable bit
      set: `git add gradlew gradlew.bat gradle/wrapper/gradle-wrapper.jar` followed by
      `git update-index --chmod=+x gradlew` (research R-4, FR-003).
      → **DONE**: included in the final report.

---

## Execution Summary

**Updated 2026-09-30 after the second implement/converge pass.**

**Updated again 2026-09-30 after the config file arrived and build + lint passed.**

**26 of 28 tasks complete. All automated criteria met. 1 manual, 1 requirements decision.**

| Status | Tasks |
| --- | --- |
| Complete | T001–T003, T004, T005–T009, T010, T011, T012–T022, T025, T026, T027 |
| Partially verified — manual remainder | T023 (Android Studio session), T024 (fresh-clone walkthrough) |
| Blocked on a requirements decision | T028 — Firebase project identity conflict, `sipsense-17b6c` vs `sipsense-17a90` |

**Automated verification for this feature is complete**: build succeeds, lint runs clean of errors,
the wrapper is tracked with correct modes and line endings, and no application source was touched.
T028 is a pre-existing application defect (baseline R2) surfaced by this feature's verification; it
is Out of Scope here and cannot be resolved without an approved requirement.

**B1 is fully resolved and verified.** The wrapper works end to end — it downloads and validates
the pinned Gradle 8.9 distribution and runs the build.

**B2 is documented but still live.** `app/google-services.json` is not in the working tree, so
`:app:assembleDebug` and `:app:lintDebug` cannot pass here. This is the single remaining gate on
this feature's Definition of Done. Nothing in the code or the wrapper can resolve it — the file is
deliberately untracked and must be supplied by a developer with Firebase access.

---

## Phase 7: Convergence

Appended by `/speckit-converge` on 2026-09-30. Outcome: `tasks_appended`.

- [X] T021 Place `app/google-services.json` per README step 3, then run `:app:assembleDebug` and
      `:app:lintDebug` to completion and record the results, per SC-001, SC-002, SC-005, US1/AC3,
      and US2/AC1 (partial)
      → **PASS (2026-09-30, third attempt)**. `app/google-services.json` is now present and
      `:app:processDebugGoogleServices` passes.
      - `.\gradlew.bat :app:assembleDebug` → **BUILD SUCCESSFUL**, 34 actionable tasks.
      - `.\gradlew.bat :app:lintDebug` → **BUILD SUCCESSFUL**. 41 issues, all severity *Warning*,
        zero errors. Top ids: `ContentDescription` x8, `IconLocation` x7, `UnusedResources` x7,
        `GradleDependency` x5, `HardcodedText` x3. All pre-existing and Out of Scope here.
        Reports at `app/build/reports/lint-results-debug.{xml,html}`.
      - SC-001, SC-002, SC-005, US1/AC3 and US2/AC1 are satisfied.
      - **Discovered while verifying**: the supplied config is for Firebase project
        `sipsense-17b6c`, not `sipsense-17a90`. See T028 — outside this feature's scope.
      - Earlier attempts: the file was absent on attempts 1 and 2; the only `google-services.json`
        on the machine then was an unrelated `trivagoo-1c868` file in `Downloads`, which was
        correctly not copied.
- [x] T022 Commit `gradlew`, `gradlew.bat`, and `gradle/wrapper/gradle-wrapper.jar` with the
      executable bit set on `gradlew`, per FR-003 (partial)
      → **DONE**: all four wrapper files staged. `git ls-files --stage` confirms `gradlew` at mode
      `100755`, the rest at `100644`. Staging also surfaced and fixed a latent defect — see T026.
      Left staged, not committed; the commit is the developer's call.
- [ ] T023 Verify the Android Studio build path and the login/registration/profile flows still
      behave identically now that a wrapper exists, per FR-007 and SC-004 (partial)
      → **PARTIALLY VERIFIED**. What passed without the config file: `gradlew projects` configures
      the build successfully (`Root project 'SipSense'` → `:app'`), proving the build scripts and
      plugin resolution are sound; `gradlew :app:dependencies --configuration debugRuntimeClasspath`
      resolved every dependency including `firebase-auth:23.1.0`, `firebase-database:21.0.0`,
      `material:1.12.0`, and `constraintlayout:2.2.0`. What remains: launching Android Studio and
      exercising the app, which needs both a real IDE session and the config file.
      → **AWAITING MANUAL RESULT** — requires an Android Studio session. Not automatable.
- [ ] T024 Walk the clean-clone README procedure end to end in a fresh checkout, per SC-003 and
      US3/AC1–AC2 (partial)
      → **PARTIALLY VERIFIED**. The documented `JAVA_HOME` + `gradlew.bat` invocation from README
      step 5 is the exact mechanism used throughout this feature, so it is proven. The quoted
      failure symptom in step 3 was verified verbatim against real console output. A second failure
      mode was discovered this session and documented — see T027. The end-to-end fresh-clone
      walkthrough remains manual.
      → **AWAITING MANUAL RESULT** — requires cloning into a fresh directory. Not automatable.
- [x] T025 Justify or remove the `networkTimeout` and `validateDistributionUrl` properties the
      Gradle `wrapper` task added to `gradle-wrapper.properties`, which no requirement called for,
      per plan Out of Scope (unrequested)
      → **RESOLVED — kept, justified.** Both are standard Gradle 8.9 `wrapper` task output, not
      hand-added. `validateDistributionUrl=true` makes the wrapper verify the distribution it
      downloads, which supports Principle VI and FR-009; removing it would weaken provenance
      checking. `networkTimeout=10000` is the documented default. Hand-deleting them would diverge
      the file from generated output and both would silently return on any future regeneration.
      Recorded in the properties file's comment header.

---

## Phase 8: Convergence (second pass)

Appended 2026-09-30. Outcome: `tasks_appended`. Two findings were discovered **and fixed** within
this pass (T026, T027); one external blocker remains (T021).

- [x] T026 **CRITICAL** Prevent `gradlew` being checked out with CRLF line endings, per FR-001 and
      the spec's line-endings edge case (contradicts)
      → **FIXED**. Staging `gradlew` produced "LF will be replaced by CRLF the next time Git touches
      it". `git config --show-origin --get core.autocrlf` traced `core.autocrlf=true` to
      `C:/Program Files/Git/etc/gitconfig` — the Git for Windows **system** config, present on every
      Windows install. So the committed `gradlew` would have been unrunnable on Linux and macOS,
      contradicting FR-001 for non-Windows clones. Added a `.gitattributes` scoped to the wrapper
      only (`gradlew` → `eol=lf`, `gradlew.bat` → `eol=crlf`, `*.jar` → `binary`). Verified: no
      warning on re-stage, `git check-attr` reports the intended values, and the staged `gradlew`
      blob contains zero CRLF pairs. Research R-4 and the plan's file list were updated to record
      the deviation and its evidence.
- [x] T027 Document the wrong-project Firebase config failure mode, per FR-005 and SC-003 (partial)
      → **FIXED**. FR-005 required naming the missing-file symptom; this session proved a second,
      equally likely symptom exists — supplying a valid `google-services.json` from a different
      Firebase project, which fails with "No matching client found for package name
      'com.sipsense.app'". README step 3 now names that message and tells the reader which two JSON
      fields to check before copying.

---

## Phase 9: Convergence (third pass)

Appended 2026-09-30 after build and lint passed. Outcome: `tasks_appended` — one finding, and it is
**blocked on a requirements decision**, not on implementation.

- [X] T028 **CRITICAL** Resolve the Firebase project identity conflict before relying on any
      database behavior, per Constitution Principle I and the Requirements Authority order
      (contradicts)
      → **RESOLVED 2026-09-30 by developer decision.** The developer designated
      **`sipsense-17b6c`** as the authoritative project, created a Realtime Database in it, and
      supplied a fresh `google-services.json` containing `firebase_url`
      (`https://sipsense-17b6c-default-rtdb.firebaseio.com`). **Feature 002** then removed both
      hardcoded `sipsense-17a90` URLs so configuration is the single source of truth, and README
      step 3 was corrected to name `sipsense-17b6c`. Naming the conflict was this task's required
      action; the decision was the developer's and the code change belonged to Feature 002.
      The original analysis is preserved below for traceability.

      → **Cannot be resolved by an agent** *(original finding, now superseded)*. Two sources in the project disagree about which
      Firebase project SipSense uses, and neither outranks the other without an approved
      requirement:
      - `app/google-services.json` (developer-supplied, governs Firebase Auth):
        `project_id` = **`sipsense-17b6c`**, and `firebase_url` is **empty**, meaning that project
        has **no Realtime Database**.
      - Source code (governs the database, because the URL is passed explicitly):
        **`sipsense-17a90`** at `RegisterActivity.kt:155` and `ProfileFragment.kt:75`.

      **Runtime consequence**: users would authenticate against `17b6c` while profile reads and
      writes target `17a90`'s database. An auth token from one project does not satisfy another
      project's security rules, so `/users/{uid}` reads and writes should fail with permission
      denied. Baseline finding **R3** makes this silent on the registration path — the write is
      fire-and-forget with only `printStackTrace()`, so registration appears to succeed while no
      profile is stored. On the Profile tab it should surface as "Failed to load profile".

      **Why this is not fixed here**: FR-006 forbids modifying `app/src/`, baseline finding **R2**
      (hardcoded database URL) is explicitly Out of Scope for this feature, and choosing the
      authoritative project is a configuration decision that Principle I reserves to an approved
      requirement. Naming the conflict is the required action, not resolving it.

      **Also affected**: README step 3 currently instructs the reader to download the file from
      project `sipsense-17a90`. That instruction is now inconsistent with the file actually in use.
      It is deliberately left unchanged pending the decision, because guessing which project name
      to write would be inventing configuration.

### Observation — not a task for this feature

`gradlew :app:dependencies` succeeded but then failed to release locks on `.gradle/8.9/executionHistory`
and `.gradle/buildOutputCleanup`, consistent with an Android Studio Gradle daemon holding them
concurrently. Dependency resolution itself completed. This is contention between two daemons, not a
defect in this feature, and it is aggravated by baseline finding **R10** (the `.gradle/` directory
being tracked in git). R10 is explicitly Out of Scope here and needs its own feature.

---

## Dependencies

- T001–T004 block everything.
- T005 blocks T006, T007, T008, T009, T010, T011.
- T010 and T011 block T017.
- T004 blocks T010 specifically — without the Firebase configuration file, the build cannot
  succeed and T010 must be reported blocked.
- T013–T016 are independent of the wrapper work and may run in parallel with Phase 3.

## Out of Scope reminder

No task here may modify `app/src/`, add a dependency, change a version, or address baseline
findings R1–R10. If a task appears to require any of those, stop and raise it instead
(Principle X).
