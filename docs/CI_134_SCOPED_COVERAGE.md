# CI policy — PR quick checks; FULL only after merge (22 Nov 2026)

**Owner decision:** automatic expensive native FULL tests run **after** an actual commit is pushed to `main` (normally after the owner manually merges a PR). They never run automatically at Draft, Ready for review, PR synchronize, or merge queue events. Explicit `workflow_dispatch` FULL is retained for emergencies and high-risk manual verification.

**Trade-off accepted by owner:** a merged PR can pass pre-merge compilation/unit checks yet break encrypted DB migrations, Android instrumentation, native Windows/iOS regressions, signing, or production recovery. The post-merge red run detects this **after the code is already in main**, not before. No Branch Protection, required checks, auto-merge, or hidden suppression is enabled. If a post-merge FULL fails, pause further merges and fix or revert the bad commit.

## Event execution contract

| GitHub event | Android | Windows / macOS | iOS | Verification meaning |
| --- | --- | --- | --- | --- |
| Draft PR / synchronize | unsigned release candidate, JVM unit tests and compile-only instrumentation | affected Windows test compilation and mapped presentation scene tests | affected iOS framework link and Simulator test compilation | **QUICK**, not FULL |
| Ready for review / synchronize | exactly the same selected QUICK policy | exactly the same QUICK policy | exactly the same QUICK policy | No second deep run |
| PR docs-only | classifier and Python regression tests only | skip | skip | FAST; platforms not verified |
| Merge completed → `push` on `main` | FULL Android instrumentation, SQLCipher recovery, ENOSPC, signed APK | FULL Windows encrypted JVM/SQLCipher and Desktop scenes; macOS JVM | FULL native Simulator | Full post-merge validation of exact main SHA |
| Explicit manual `workflow_dispatch` of Android and/or CMP workflow | selected manual FULL | selected manual FULL | selected manual FULL | Owner-triggered exception, not automatic PR testing |

Both heavy workflows (`android-debug-apk.yml`, `cmp-kmp-platforms.yml`) are triggered **only** by `push` to `main` and `workflow_dispatch`, and unconditionally request FULL for those events. No diff classifier can downgrade a main push to FAST/TARGETED. The heavy workflows do not use `pull_request` or `merge_group` triggers. Distinct post-merge runs are not cancelled by later main pushes.

`.github/workflows/draft-impact-feedback.yml` now serves **all PR phases**, despite its historical filename. It checks the exact PR head SHA, executes classifier/gate regressions, and runs quick jobs on every affected platform. A severe / unknown / CI / common DB change may still be classified `FULL` **as an impact risk label**, but its PR jobs remain quick: affected platform builds, test compilation and a bounded set of regressions, with no Android emulator or full native suites. The summary expressly reports that deeper suites are **NOT VERIFIED** before merge. Unknown/unclassified changes select all three quick jobs, never silently skip them. Quick job failures fail the PR quick receipt. Mapped P5B Windows scenes still run when unrelated changes raise the overall impact to deep. Android QUICK runs filtered shared geometry/formatting JVM tests and compiles instrumentation sources. The signed-release production recovery result is reported separately by Android FULL.

## Platform and feature selection

The reviewed mapping in `ci/impact.py` is kept:
- Android presentation-only Inter font: Android quick build/units; shared CMP mapped drawing/scaffold/theme/FAB/typography: affected Android, Windows and iOS quick jobs. Windows presentation paths additionally execute the relevant `CeilingsFoundationSceneTest` / `MeasurementEditSceneTest` classes.
- Other Android code, including PDF and immutable estimates: Android quick build/units; **full Android security/instrumentation after merge**.
- Desktop/JVM-only code including SQLCipher: Windows quick compile + shared Kotlin compile; **full native Windows JDBC/SQLCipher after merge**.
- iOS-only code: iOS framework link; **full Simulator security/integrity tests after merge**.
- Common business/geometry/financial/SQLDelight, workflows/build, unknown or deleted/renamed files: all three quick jobs; **all-platform FULL after merge**.
- Documentation-only: classifier regressions; all platforms unverified until the next `main` push, which still runs FULL unconditionally.

`deep` and `FULL` outputs of the classifier describe **risk and intended post-merge scope**, not proof that any pre-merge native suite ran. Maintain a strict distinction between compilation, mapped UI smoke, and real encrypted database tests.

## Acceptance and diagnostics

- Review each PR diff; run quick checks for the exact head SHA; owner alone changes Ready status and merges.
- For important changes (database schema, encryption, migrations, startup/recovery, backup, payment/immutable estimates), **manually dispatch Android and CMP FULL before merging if rollback risk is unacceptable**. This is optional owner action, not an automatic pre-merge requirement.
- Observe both post-merge FULL workflow runs on the new `main` SHA and fix/revert failures before further merges. Preserve original tests, security guards, release recovery, isolated process-death/ENOSPC checks and report artifacts.
- Never interpret skipped checks as passed. Do not re-enable automatic screenshots; visual screenshot matrix stays manual.
- Follow-up review: check a real PR workflow Ready event (only quick jobs) and the subsequent main-push runs (all FULL) before treating the new policy as fully proven.

Historical investigation and prior milestone handoff remain in `docs/CI_134_CODEX_HANDOFF.md` and GitHub issue #134 (closed).
