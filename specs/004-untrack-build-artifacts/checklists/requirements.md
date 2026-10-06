# Requirements Checklist: Untrack Generated Build Artifacts

**Feature**: [spec.md](spec.md) | **Date**: 2026-09-30

---

## Functional requirements

| ID | Requirement | Status | Evidence |
| --- | --- | --- | --- |
| FR-001 | Generated output under `app/build/` no longer tracked | **PASS** | `git ls-files -- app/build` = 0 (was 479) |
| FR-002 | Gradle cache under `.gradle/` no longer tracked | **PASS** | `git ls-files -- .gradle` = 0 (was 17) |
| FR-003 | No file removed from the working tree | **PASS** | 836 and 17 files on disk, unchanged; APK present |
| FR-004 | Ignore rules exclude these paths; no redundant rule added | **PASS** | `.gitignore` byte-identical; plain `check-ignore` now reports lines 18/19 |
| FR-005 | Source, build scripts, wrapper, docs intact | **PASS** | `git status` shows only pre-existing F001–F003 changes; no deletions |
| FR-006 | `app/google-services.json` stays untracked and ignored | **PASS** | `git ls-files` empty; `check-ignore` reports `.gitignore:2` |
| FR-007 | Build, lint, and tests all succeed | **PASS** | three tasks, all exit code 0; lint 0 errors; 6/6 tests |
| FR-008 | A build produces no Git status noise | **PASS** | zero generated-path noise after 2 forced full reruns |
| FR-009 | Nothing committed | **PASS** | `HEAD` still `4664032`; 496 removals left staged |
| FR-010 | History exposure reported, not remediated | **PASS** | 2 key-shaped strings at `HEAD` reported; no rewrite, no rotation |

## Success criteria

| ID | Criterion | Status |
| --- | --- | --- |
| SC-001 | Zero tracked files under `app/build/` | **PASS** — 0 |
| SC-002 | Zero tracked files under `.gradle/` | **PASS** — 0 |
| SC-003 | `git status` clean of generated paths after a build | **PASS** |
| SC-004 | No configuration value in any tracked file | **PASS** — only the documented project id in README, which is not a credential |
| SC-005 | Build, lint, tests unaffected | **PASS** — lint 41/0 matches baseline; 6/6 tests |
| SC-006 | Provably zero deletions from disk | **PASS** — counts identical before and after |

## Constitution compliance

| Principle | Status | Note |
| --- | --- | --- |
| I — Business-Process Authority | **PASS** | No feature, workflow, or business rule invented. The one ambiguity (should `.idea/`, `.vs/`, `local.properties` also be untracked?) is reported as a developer decision, not resolved silently. |
| II — Android/Kotlin First | **N/A** | No code changed. |
| III — IoT Reliability | **N/A** | No device interaction touched. |
| IV — Data Integrity | **N/A** | No data model or record path touched. |
| V — Offline-Aware Design | **N/A** | No sync behaviour touched. |
| VI — Security and Privacy | **IMPROVED** | An API-key-shaped value left the tracked set. Residual history exposure reported rather than silently accepted. No key value was ever printed — counts only. |
| VII — Testing | **PASS** | Full suite re-run and passing; the verification scan itself validated with a positive control. |
| VIII — Maintainability | **PASS** | One Git command, no new dependency, no redundant ignore rule. |
| IX — Spec-Driven Development | **PASS** | specify → plan → research → tasks → implement → verify, ahead of any change. |
| X — Change Control | **PASS** | Only the index changed. No drive-by edit; the 19 other tracked-but-ignored files were reported, not touched. |
| XI — Definition of Done | **PASS** | Requirements met, tests passing, nothing broken, no unresolved critical issue within scope. |

## Outstanding

Nothing within this feature's scope. Deliberately left to the developer:

1. **Commit the staged removals** — 496 deletions are staged and uncommitted, as required by FR-009.
2. **Decide on the Firebase key** — whether to rotate it and whether to rewrite history, given the key is
   already in committed history and a Firebase Android key ships in every APK by design.
3. **Decide on the other 19 tracked-but-ignored files**, especially `local.properties`.
