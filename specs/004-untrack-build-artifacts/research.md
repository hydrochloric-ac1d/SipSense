# Phase 0 Research: Untrack Generated Build Artifacts

**Feature**: [spec.md](spec.md) | **Date**: 2026-09-30

---

## R-1: Why did correct `.gitignore` rules fail to take effect?

**The surprise worth recording**: `.gitignore` was already correct before this feature began. The
natural assumption — that build output was tracked because the ignore rules were missing or wrong — is
false, and acting on that assumption would have produced a redundant rule and left the real cause in
place.

**Evidence gathered**:

```text
.gitignore:18:.gradle/      matches  .gradle/8.9/checksums/checksums.lock
.gitignore:18:.gradle/      matches  .gradle/file-system.probe
.gitignore:19:build/        matches  app/build/outputs/apk/debug/app-debug.apk
.gitignore:19:build/        matches  app/build/intermediates/packaged_res/.../values.xml
.gitignore:2:app/google-services.json   matches  app/google-services.json
```

Obtained with `git check-ignore --no-index -v`. The `--no-index` flag matters: without it,
`git check-ignore` **skips files that are in the index**, so a plain `git check-ignore app/build/…`
reports "not ignored" for a tracked file and gives the misleading impression that no rule matches. An
earlier check in this session hit exactly that trap.

Corroborated independently by `git ls-files -i -c --exclude-standard`, which lists tracked files that
match ignore rules: **515 files**, including all 479 under `app/build/` and all 17 under `.gradle/`.

**Conclusion**: Git gives the index precedence over ignore rules. A file that is already tracked stays
tracked and keeps reporting modifications, no matter what `.gitignore` says. These files were therefore
committed before the rules existed, or added with `git add -f`.

**Decision**: change no ignore rule. Removing the index entries is both necessary and sufficient, and
adding an `app/build/` line would falsely imply the old rules were inadequate.

---

## R-2: Which operation untracks without deleting?

**The central risk of this feature.** `git rm -r app/build` and `git rm -r --cached app/build` differ by
one flag and by 496 files on disk.

| Option | Effect | Verdict |
| --- | --- | --- |
| `git rm -r app/build .gradle` | removes from index **and deletes from disk** | **Rejected.** Violates FR-003 and FR-006's spirit, forces a cold rebuild, and risks the appearance of data loss. |
| `git rm -r --cached app/build .gradle` | removes from index, **leaves the working tree untouched** | **Chosen.** |
| `git update-index --skip-worktree` | hides changes without untracking; files stay in the repository | **Rejected.** Treats the symptom. The artifacts would remain committed and the API key would remain in the tracked set. |
| `git update-index --assume-unchanged` | tells Git to stop checking the file | **Rejected.** Same objection, and it is a local per-clone flag, so it would not help anyone else. |

**Decision**: `git rm -r --cached app/build .gradle`.

**Verification obligation**: because the destructive and non-destructive forms look so similar, SC-006
requires proving zero files were deleted, rather than assuming `--cached` behaved. The file count on
disk is captured before and after and compared.

---

## R-3: What causes `Failed to release lock on execution history cache`?

The developer asked specifically whether this comes from tracked `.gradle/` state or from Gradle daemon
interaction. **The evidence says primarily the latter, with tracking as a genuine aggravating factor.**

**Evidence gathered**:

1. **Android Studio is running**, `studio64` PID 17236, started 07:51 today.
2. **A second Gradle daemon is live**, confirmed by `gradlew --status`:

   ```text
      PID STATUS   INFO
    16564 IDLE     8.9
    37980 STOPPED  (stop command received)
   ```

   Several JVMs are alive — PIDs 6380 (started Sep 28), 16564 (14:44), 46060 (14:53).
3. **The exact files named in the failures are tracked.** Of the 17 tracked `.gradle/` files, four are
   live lock files:

   ```text
   .gradle/8.9/executionHistory/executionHistory.lock
   .gradle/8.9/checksums/checksums.lock
   .gradle/8.9/fileHashes/fileHashes.lock
   .gradle/buildOutputCleanup/buildOutputCleanup.lock
   ```

   The error messages named `executionHistory`, `checksums`, and `buildOutputCleanup` specifically.
4. **`gradlew --stop` resolved it.** Every invocation after stopping the daemon returned exit 0. That
   is the decisive diagnostic: if tracking alone caused it, stopping a daemon would not have helped.
5. **The failure mode is dangerous, not merely noisy.** One invocation printed `BUILD SUCCESSFUL` and
   then exited **non-zero** on the lock release. Any automation trusting exit codes would record a
   false failure, and automation trusting the log text would record a false success.

**Conclusion — two distinct contributors:**

- **Primary cause: concurrent access to one project-local cache.** Android Studio's Gradle daemon and
  the command-line wrapper share `SipSense/.gradle/`. When both touch it, one cannot release a lock the
  other holds. This is ordinary IDE/CLI contention and is **not** caused by Git.
- **Aggravating cause: those lock and cache files are tracked.** Every `git status`, `git diff`, and
  `git checkout` reads — and can rewrite — the same `.lock` and `.bin` files Gradle holds open. On
  Windows, file sharing is restrictive enough that this adds real contention, and it means Git can
  modify live Gradle state, which is never correct.

**What this feature fixes, stated precisely**: untracking removes the aggravating cause and stops Git
from ever touching live Gradle lock files. It **does not** eliminate IDE/CLI contention — that is
avoided by not running command-line builds while Android Studio is syncing, or by running
`gradlew --stop` first. Claiming this change alone will end the lock errors would be overstating it.

---

## R-4: What remains in committed history?

**Evidence gathered** (counts only; no key value was printed at any point):

- `app/build/intermediates/packaged_res/debug/packageDebugResources/values/values.xml` is tracked and
  contains **2 API-key-shaped (`AIza…`) strings** in the working tree.
- `git show HEAD:<that path>` also contains **2** such strings, so the exposure is in committed
  history, not only in the worktree.
- The same tracked file contains `firebase_database_url` and 3 occurrences of `sipsense-17b6c`.
- Resource **name** lists — `R.txt`, `R-def.txt`, `stableIds.txt`, `package-aware-r.txt` — mention
  `google_api_key` as an identifier but hold no value.
- No file under `app/build/generated/**` is tracked, so the plugin's direct output was never committed;
  the leak path is the *merged* resources produced downstream from it.

**Decision**: report only. FR-010 requires this, and rewriting history and rotating credentials are both
explicitly forbidden by the request. Untracking stops future exposure; it cannot undo past exposure.

**Proportionality, stated honestly**: a Firebase Android API key is not a server secret. It ships inside
every APK by design and is protected by Realtime Database security rules and App Check, not by being
hidden. So this is not an emergency credential leak. It is still a Principle VI violation — keys must not
be in source — and the decision on rotation and history belongs to the developer.
