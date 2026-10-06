# Implementation Plan: Untrack Generated Build Artifacts

**Branch**: `004-untrack-build-artifacts` | **Date**: 2026-09-30 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/004-untrack-build-artifacts/spec.md`

## Summary

Remove 496 generated files — 479 under `app/build/` and 17 under `.gradle/` — from Git's index while
leaving every one of them on disk. `.gitignore` already contains the matching rules, so once the files
leave the index the existing rules take over and nothing regenerated is tracked again. No ignore rule
is added, no file is deleted, no source is touched, and nothing is committed: the change is left
staged for developer review.

## Technical Context

**Language/Version**: Not applicable. This change contains no code.

**Primary Dependencies**: None added. Git and the existing Gradle wrapper only.

**Storage**: Not applicable.

**Testing**: Verification is the existing `:app:assembleDebug`, `:app:lintDebug`, and
`:app:testDebugUnitTest`, plus Git index inspection and content searches. No new test is added —
there is no new behaviour to test, and Feature 003's suite already guards the code this must not
disturb.

**Target Platform**: The repository itself.

**Project Type**: Repository hygiene / configuration change.

**Performance Goals**: Not a goal, though a 577 → 81 tracked-file reduction makes `status` and `diff`
materially faster and reviewable.

**Constraints**: Must not delete files from disk (FR-003), must not modify source (FR-005), must not
commit (FR-009), must not rewrite history or rotate credentials (Out of Scope).

**Scale/Scope**: 496 index entries removed. Zero files modified. Zero files deleted.

## Constitution Check

*GATE: evaluated before Phase 0 and re-checked after design.*

| # | Principle | Status | Notes |
| --- | --- | --- | --- |
| I | Business-Process Authority | **PASS** | Invents no requirement and changes no business behaviour. Acts on an already-recorded finding (R10 / Feature 002 T022) at the developer's explicit request. |
| II | Android/Kotlin First | **PASS** | No code changes, so the architecture is untouched by construction. |
| III | IoT Reliability | **N/A** | No device interaction and no code path affected. |
| IV | Data Integrity | **PASS** | No stored data, schema, or ownership is affected. Build output is derived data with no archival value. |
| V | Offline-Aware Design | **N/A** | No runtime behaviour affected. |
| VI | Security and Privacy | **PASS — this is the principle being repaid** | Stops an API key reaching the repository through generated output, closing a bypass of the `google-services.json` ignore rule. Does not claim to remove the key from history; FR-010 reports that instead. |
| VII | Testing | **PASS** | No new behaviour to test. The existing suite is run as a regression guard, which is the appropriate verification for a change that must alter nothing. |
| VIII | Maintainability | **PASS** | Removes 86% of the tracked repository as noise. No dependency added. No rule added, because the correct rules already exist — restraint here is the simpler option. |
| IX | Spec-Driven Development | **PASS** | Specified, planned, tasked, then implemented. Traceable to R10 and Feature 002 T022. |
| X | Change Control | **PASS** | Scoped to exactly the two paths named in the request. `.idea/`, `.vs/`, and `local.properties` are also mis-tracked but are reported rather than swept in. |
| XI | Definition of Done | **PASS** | Every criterion is agent-verifiable. No external or manual dependency. |

**Gate result**: PASS with no deviations. This feature has no Complexity Tracking entries, which is
itself worth noting after three consecutive features that did.

## Project Structure

### Documentation (this feature)

```text
specs/004-untrack-build-artifacts/
├── spec.md
├── plan.md                     # This file
├── research.md                 # Phase 0: why the rules failed, and the Gradle lock investigation
├── quickstart.md               # Verification scenarios
├── tasks.md                    # Phase 2 output
└── checklists/
    └── requirements.md
```

No `data-model.md` and no `contracts/`: no entity and no interface.

### Repository (files affected)

```text
.gitignore                      # UNCHANGED - already correct; verified, not edited
app/build/**                    # UNTRACKED (479 index entries removed, files kept on disk)
.gradle/**                      # UNTRACKED (17 index entries removed, files kept on disk)

app/src/**                      # UNCHANGED - FR-005
app/build.gradle.kts            # UNCHANGED - FR-005
gradlew, gradlew.bat, gradle/   # UNCHANGED - FR-005, and still staged from Feature 001
README.md                       # UNCHANGED
app/google-services.json        # UNCHANGED, still ignored and untracked - FR-006
```

**Structure Decision**: No file is created or edited by this feature. That is unusual enough to state
plainly — the entire implementation is a Git index operation. The temptation to also "tidy"
`.gitignore` is declined because the rules already match; adding a redundant `app/build/` line would
imply the old rules were wrong and obscure the real cause, which was tracking precedence.

## Phase 0 — Research

See [research.md](research.md). Three questions: why correct ignore rules failed to take effect, which
Git operation untracks without deleting, and whether the Gradle lock failures come from tracked
`.gradle/` state or from daemon contention.

## Phase 1 — Design

**The operation**, run from the repository root with no build in progress:

```powershell
git rm -r --cached app/build .gradle
```

`--cached` is the entire safety property: it removes index entries and leaves the working tree
untouched. Without it the same command would delete 496 files from disk and force a cold rebuild.

**Why this needs no `.gitignore` edit.** `git check-ignore --no-index -v` confirms `.gitignore:19:build/`
matches `app/build/…` and `.gitignore:18:.gradle/` matches `.gradle/…`. The rules never failed to
match; Git simply gives the index precedence over ignore rules. Removing the index entries is
therefore both necessary and sufficient.

**Interaction with already-staged work.** Feature 001's wrapper files and `.gitattributes` are staged.
`git rm --cached` adds deletions to the same index without disturbing unrelated staged entries, so the
result is one combined staged change set the developer commits together.

**Ordering.** Run `gradlew --stop` first if any daemon is active, so no process holds a handle on a
`.gradle/` lock file while the index is rewritten. Then run the build, lint, and test tasks *after* the
untracking, to prove the build still works from artifacts that are present but no longer tracked.

**What this deliberately does not do**: rewrite history, rotate the key, commit, or touch the 19
mis-tracked IDE and machine-local files. Each is either explicitly forbidden or out of the requested
scope, and each is reported instead.
