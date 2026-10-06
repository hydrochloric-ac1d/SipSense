# Verification Quickstart: Untrack Generated Build Artifacts

**Feature**: [spec.md](spec.md) | **Date**: 2026-09-30

Every scenario is agent-verifiable. This feature has no external dependency and no manual step.

---

## Scenario 1 — Nothing generated is tracked any more (SC-001, FR-001, FR-002)

```powershell
cd C:\Users\adaaz\StudioProjects\SipSense
"app/build tracked : " + (git ls-files -- app/build | Measure-Object).Count
".gradle tracked   : " + (git ls-files -- .gradle  | Measure-Object).Count
"repo total tracked: " + (git ls-files | Measure-Object).Count
```

**Pass**: the first two are **0** (down from 479 and 17), and the total is about **81**.

---

## Scenario 2 — Nothing was deleted from disk (SC-006, FR-003)

**The safety check.** `git rm --cached` and `git rm` differ by one flag and by 496 files, so prove the
files are still there rather than assuming.

```powershell
cd C:\Users\adaaz\StudioProjects\SipSense
"app/build files on disk : " + (Get-ChildItem app\build -Recurse -File | Measure-Object).Count
".gradle files on disk   : " + (Get-ChildItem .gradle  -Recurse -File -Force | Measure-Object).Count
"APK present             : " + (Test-Path app\build\outputs\apk\debug\app-debug.apk)
```

**Pass**: both counts are non-zero and unchanged from the pre-change capture, and the APK is present.

---

## Scenario 3 — `git status` is clean of generated paths (SC-003, FR-008)

Run a full build first, so the build actively rewrites artifacts, then check that Git stays quiet:

```powershell
cd C:\Users\adaaz\StudioProjects\SipSense
.\gradlew.bat :app:assembleDebug
git status --short | Select-String -Pattern 'app/build|\.gradle/'
```

**Pass**: the `Select-String` returns **nothing**. A build that rewrites hundreds of files produces no
Git noise at all.

---

## Scenario 4 — Ignore rules now actually apply (FR-004)

```powershell
cd C:\Users\adaaz\StudioProjects\SipSense
git check-ignore -v app\build\outputs\apk\debug\app-debug.apk
git check-ignore -v .gradle\8.9\executionHistory\executionHistory.lock
git check-ignore -v app\google-services.json
```

**Pass**: each prints the matching rule — `.gitignore:19:build/`, `.gitignore:18:.gradle/`, and
`.gitignore:2:app/google-services.json` respectively.

> Note the earlier trap: **before** untracking, plain `git check-ignore` reports these as *not* ignored,
> because it skips files that are in the index. That is why `--no-index` was needed during research and
> is **not** needed here — a plain check succeeding is itself evidence the files left the index.

---

## Scenario 5 — No configuration values in the tracked set (SC-004, US2)

```powershell
cd C:\Users\adaaz\StudioProjects\SipSense
$tracked = git ls-files
$hits = $tracked | Where-Object { Test-Path $_ } | ForEach-Object {
    $m = Select-String -Path $_ -Pattern 'AIza[0-9A-Za-z_\-]{10,}','firebase_database_url','sipsense-17b6c' -ErrorAction SilentlyContinue
    if ($m) { $_ }
}
if ($hits) { "FAIL - tracked files still contain config values:"; $hits } else { "PASS - no tracked file contains an API key, database URL, or project id" }
```

**Pass**: no hits. Counts only — never print a matched key value.

---

## Scenario 6 — Source and configuration are intact (SC-005, FR-005, FR-006)

```powershell
cd C:\Users\adaaz\StudioProjects\SipSense
git status --short -- app/src app/build.gradle.kts build.gradle.kts settings.gradle.kts gradlew gradlew.bat gradle .gitattributes README.md .gitignore
git ls-files app/google-services.json   # must print NOTHING
git check-ignore -v app/google-services.json
```

**Pass**: the first command shows only the pre-existing Feature 002/003 changes and Feature 001's staged
wrapper files — **no new modification and no deletion**. `.gitignore` must **not** appear as modified,
since this feature deliberately leaves it alone. `git ls-files` on the config prints nothing.

---

## Scenario 7 — The build still works (SC-005, FR-007)

```powershell
cd C:\Users\adaaz\StudioProjects\SipSense
.\gradlew.bat --stop                       # avoid IDE/CLI lock contention first - see research R-3
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:lintDebug
.\gradlew.bat :app:testDebugUnitTest
```

**Pass**: all three `BUILD SUCCESSFUL` with **exit code 0**. Lint: 41 warnings, 0 errors. Tests: 6
passed, 0 failures.

**Check the exit code explicitly, not just the log text.** Research R-3 recorded an invocation that
printed `BUILD SUCCESSFUL` and then exited non-zero on a lock-release error, so log text alone is not
proof.

---

## Scenario 8 — Nothing was committed (FR-009)

```powershell
cd C:\Users\adaaz\StudioProjects\SipSense
git log --oneline -1
git diff --cached --stat | Select-Object -Last 1
```

**Pass**: the most recent commit is unchanged from before this feature, and the staged change set
contains the 496 removals plus Feature 001's wrapper files, awaiting the developer's commit.
