# CI policy — PR quick checks; FULL only after merge (24 Nov 2026)

**Owner decision:** automatic expensive native FULL tests run **after** an actual commit is pushed to `main` (normally after the owner manually merges a PR). They never run automatically at Draft, Ready for review, PR synchronize, or merge queue events. Explicit `workflow_dispatch` FULL is retained for emergencies and high-risk manual verification.

**Trade-off accepted by owner:** a merged PR can pass pre-merge compilation/unit checks yet break encrypted DB migrations, Android instrumentation, native Windows/iOS regressions, signing, or production recovery. The post-merge red run detects this **after the code is already in main**, not before. No Branch Protection, required checks, auto-merge, or hidden suppression is enabled. If a post-merge FULL fails, pause further merges and fix or revert the bad commit.

## Event execution contract

| GitHub event | Android | Windows / macOS | iOS | Verification meaning |
| --- | --- | --- | --- | --- |
| Draft PR / synchronize | unsigned release candidate, JVM unit tests and compile-only instrumentation | affected Windows production and test compilation | affected iOS framework link and Simulator test compilation | **QUICK**, not FULL |
| Ready for review / synchronize | exactly the same selected QUICK policy | exactly the same QUICK policy | exactly the same QUICK policy | No second deep run |
| PR docs-only | classifier and Python regression tests only | skip | skip | FAST; platforms not verified |
| Merge completed → `push` on `main` | FULL Android instrumentation, SQLCipher recovery, ENOSPC, signed APK | FULL Windows encrypted JVM/SQLCipher and Desktop storage; macOS JVM | FULL native Simulator | Full post-merge validation of exact main SHA |
| Explicit manual `workflow_dispatch` of Android and/or CMP workflow | selected manual FULL | selected manual FULL | selected manual FULL | Owner-triggered exception, not automatic PR testing |

Both heavy workflows (`android-debug-apk.yml`, `cmp-kmp-platforms.yml`) are triggered **only** by `push` to `main` and `workflow_dispatch`, and unconditionally request FULL for those events. No diff classifier can downgrade a main push to FAST/TARGETED. The heavy workflows do not use `pull_request` or `merge_group` triggers. Distinct post-merge runs are not cancelled by later main pushes.

`.github/workflows/draft-impact-feedback.yml` now serves **all PR phases**, despite its historical filename. It checks the exact PR head SHA, executes classifier/gate regressions, and runs quick jobs on every affected platform. A severe / unknown / CI / common DB change may still be classified `FULL` **as an impact risk label**, but its PR jobs remain quick: affected platform builds, test compilation and a bounded set of regressions, with no Android emulator or full native suites. The summary expressly reports that deeper suites are **NOT VERIFIED** before merge. Unknown/unclassified changes select all three quick jobs, never silently skip them. Quick job failures fail the PR quick receipt. Android QUICK runs filtered shared geometry/formatting, project quote metadata (including approval/deletion protection), measurement edit model and measurement navigation JVM tests, and compiles instrumentation sources. The navigation filters retain direct workspace/Back, restore and dirty-exit regression coverage after P6A; they do not launch UI or emulator tests. The signed-release production recovery result is reported separately by Android FULL.

## Platform and feature selection

The classifier selects compile and test consumers by source set. Android-only changes, including assets and platform PDF code, select Android QUICK. Desktop/JVM-only changes select Windows QUICK, iOS-only changes select the framework and Simulator test compilation on macOS. Shared Kotlin, Compose, database and configuration changes select all dependent platforms. Unknown, deleted or renamed paths fail closed to all three QUICK jobs. Documentation-only changes run policy checks.

The classifier’s risk label `FULL` does not schedule native runs from a PR. See the [P5B test inventory and retained proofs](P5B_TEST_POLICY_2026-11-24.md).

## Acceptance and diagnostics

- Review each PR diff; run quick checks for the exact head SHA; owner alone changes Ready status and merges.
- For important changes (database schema, encryption, migrations, startup/recovery, backup, payment/immutable estimates), **manually dispatch Android and CMP FULL before merging if rollback risk is unacceptable**. This is optional owner action, not an automatic pre-merge requirement.
- Observe both post-merge FULL workflow runs on the new `main` SHA and fix/revert failures before further merges. Preserve the non-UI integrations, security guards, signed release recovery, isolated process-death/ENOSPC checks and report artifacts.
- Report skipped checks and unsigned production recovery explicitly. Use owner APK acceptance for gestures, screenshots and visual layout.
- Follow-up review: check a real PR workflow Ready event (only quick jobs) and the subsequent main-push runs (all FULL) before treating the new policy as fully proven.

Historical investigation and prior milestone handoff remain in `docs/CI_134_CODEX_HANDOFF.md` and GitHub issue #134 (closed).

## Owner decision: historical database coverage (26.11.2026)

During single-owner pre-release development, the regular CI baseline is v21→current (through #143A acceptance), v22→v23/current, v23→v24 and fresh current-schema databases. Pre-v21 fixture-only migration tests are archival, not regular QUICK/FULL gates. Archive source and fixtures rather than delete migrations or disable production backup compatibility. Archived Kotlin methods are explicitly annotated `@Ignore` (with a policy comment); to investigate a historical upgrade, remove the annotation on a temporary branch and run the precise test, without changing regular gates. Separate current checks from historical setups. Current-schema backup/restore, key replacement recovery, ENOSPC, process death, integrity, exact estimates, geometry, SQL constraints, concurrency and fail-closed behavior remain mandatory. Retain SQLDelight schema verification. No failure masking, no change to dispatch/merge gates. Re-evaluate the supported upgrade matrix before distributing stable APKs externally.
