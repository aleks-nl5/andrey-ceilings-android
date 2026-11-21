# #134 — CI performance, FAST / TARGETED / FULL — developer handoff

> Status 21.11.2026: initial Windows observability slice committed on branch `ci/p5b-impact-full-gate`. **Not implementation-complete; Draft PR only.** Base: owner-merged P5A #132 (`b20d185b83a7e38d02d9bea2225b36175eb33887`), #131 completed. Main contract: [issue #134](https://github.com/aleks-nl5/andrey-ceilings-android/issues/134). Read it fully, AGENTS.md, and actual current workflows before coding.

## Measured Windows baseline (do not confuse sum of testcase durations with wall clock)

Final accepted P5A [run 44925047682](https://github.com/aleks-nl5/andrey-ceilings-android/actions/runs/44925047682) succeeded on Windows and macOS/iOS. Its Windows `windows-desktop-test-reports` artifact ID 14718695387 contains **129 JUnit XML files, 499 cases, 0 failures, 0 skipped**. Summed testcase time: **82.7 min** (shared 435 / 59.8 min; desktop 64 / 22.9 min). Slowest classes:

| Test | Cases | Summed time |
| --- | ---: | ---: |
| `PriceCatalogWindowsCipherTest` | 5 | 1733.6 s / 28.9 min |
| `MixedDetailsSceneTest` | 3 | 822.5 s / 13.7 min |
| `MeasurementWindowsCipherTest` | 4 | 278.9 s |
| `FrozenIndividualEstimateTest` | 6 | 274.5 s |
| `SqlCipherWindowsReturningTest` | 11 | 272.6 s |
| `OverlayDragSceneTest` | 1 | 225.3 s |

Top individual tests: `MixedDetailsSceneTest.allMixedOrdersUseProductionDetailsAndOneSaveOrExplicitDiscard` 799.4 s; `PriceCatalogWindowsCipherTest.encryptedPointPlacementsV8UpgradeRollbackReopenRacesAndDomainProof` 672.8 s; `encryptedV5NamesRollbackAndConcurrentConnections` 558.4 s. Two largest classes account for ~51.5% of total testcase time. Existing errors `hmac check failed` are expected in wrong-key negative tests; do not treat those messages alone as proof of failed tests.

## First committed slice — diagnostics only

- `ci/windows_test_timings.py` summarizes existing shared and desktop JUnit XML, errors, skipped, top slow cases/classes and adds `GITHUB_STEP_SUMMARY`. Handles missing results without claiming tests passed. No new dependencies, DB operations, or test-skips.
- `ci/tests/test_windows_test_timings.py`: stdlib unit cases (success, failure, skip, malformed/non-finite XML, missing results, multiple roots). Run `python -m unittest discover -s ci/tests -p test_windows_test_timings.py`.
- Windows Gradle `:shared:jvmTest` and `:desktopApp:test` log test STARTED, to identify tests stalled for many minutes. Windows workflow runs parser unit tests before Gradle and always attempts test timing summary + existing XML/HTML artifact upload. All current Gradle tests remain in FULL.

Local parser exercised on the actual final P5A Windows XML artifact: reproduced 129/499/0/0 and rankings above. This is not a claim that new Windows Actions CI already passed.

## Remaining implementation order — do not silently skip gates

1. **Measure:** run isolated slow classes and compare full Windows time; isolate per-connection SQLCipher physical open, initialization/PRAGMA, schema verification, statement latency, and Compose scene waiting/scheduling. Log counts and timings only — never key/SQL payload/DB contents. Do not implement JDBC pooling without measured proof and a separate scoped issue/review. Keep cipher/security and race/migration proofs.
2. **FAST:** new feedback workflow for intermediate Draft commits, fast compile + nonexpensive targeted unit/smoke. Must not impersonate FULL or report green required status if no tests ran; provide status/SHA/coverage summary. Preserve easy debug APK access as needed.
3. **TARGETED:** conservative changed-file classification with explicit cross-module dependency map and unit/table tests (docs only, PDF, UI foundation/theme/measurement canvas, pricing, platform hosts, DB, SQLCipher, Gradle, workflows, unknown/renamed/deleted/multiple paths). Fallback FULL on any ambiguous/critical changes, including CI/config modifications.
4. **FULL:** existing Android instrumentation/encrypted migration/process-death/release checks, Windows Desktop/JVM SQLCipher, macOS/iOS simulator retained exactly; run on ready-for-review PR, non-draft synchronize/reopen, push/main as supported, manual explicit FULL. Ensure **full results on final head SHA** and that FAST-only or a skipped/failed/stale check can never satisfy merge conditions. Do not circumvent existing bounded emulator-disconnect retry logic. Be conservative with GitHub Actions required-status behavior. Protect secrets on untrusted PRs.
5. **Retention:** debug APK 7d, transient signed APK 14d, screenshots 14d, reports 30d; official Releases retained, caches managed automatically; workflow run/history retention subject to actual repository settings (target 90d). Do not delete active PR artifacts or implement destructive cleanup automatically.
6. **Evidence:** exact before/after durations, unit classifier tests, Android/Windows/iOS FULL success on exact final SHA, owner APK/manual gate and independent code review; owner merges. Document operational fallback and action permissions.

### Required-check limitation

The connected integration can read `main` but received **403 Resource not accessible by integration** for `GET /branches/main/protection`; `GET /rulesets` yielded an empty list. This **does not establish that branch protection is correctly enforced**. Record which stable checks must be required and have the owner verify/apply protection in repository settings; until confirmed, do not claim mechanical merge enforcement.

### What is explicitly out of scope

No runtime business rule, migrations, user-visible UX, SQLCipher KDF/keys or schema, immutable quotes/PDF, broad test exclusions, silent CI skips or blanket retries. The next feature after this CI phase is P5B unified POINT/LINE/AREA UX in #95; P5C contour and P5D live pricing/quotes are separate.


## Additive draft feedback slice — 21.11.2026 (not merge-ready)
- New `.github/workflows/draft-impact-feedback.yml` provides a **separate non-required advisory** on Draft PR updates. It never claims the Android/Windows/iOS FULL gate is green.
- `ci/impact.py` reads a complete merge-base `git diff --name-status -z --find-renames BASE...HEAD`, checks exact PR head, and fails closed to **FULL** if the diff, path, status, or SHA cannot be established. Critical source (commonMain including pricing/PDF/theme/navigation/Canvas), SQLDelight/SQLCipher/backup, CI, Gradle, or tests all require FULL.
- Documentation-only changes select **FAST** (real parser regression tests). Confident Android-only, Desktop-only, and iOS-only paths select **TARGETED** with a conservative union of relevant draft tasks: Android unit + debug APK; Windows Desktop compile + production scene smoke; iOS Simulator framework + SQLCipher test. Any selected test failure turns the advisory result red; no silent success.
- This slice **does not yet reduce FULL workflow frequency**. The established `android-debug-apk.yml` and `cmp-kmp-platforms.yml` still execute FULL on PRs. Do not modify required check settings to use the advisory. Next separate step: validate classifier on real Draft scenarios, design ready-for-review/full final SHA gates without skipped required statuses, then optimize frequency. Until that gate is validated, existing FULL is the safety net.
- `ci/tests/test_impact.py` contains table-driven tests for docs, Android/Desktop/iOS, combined groups, shared foundation/pricing/PDF, database/Gradle/CI, deleted/renamed, unknown paths and invalid diff. Command: `python3 -m unittest discover -s ci/tests -p test_impact.py -v`.
- Artifact retention changed conservatively: intermediate debug APK **7 days**; release/signed APK **14 days**; P5A screenshots **14 days**; Android instrumentation and Windows test reports **30 days**. GitHub Actions run/log retention (target 90 days, if possible) and actual required check protection remain manual repo setting verifications; no history deletion.
- The slower JDBC/Compose suites still require isolated profiling before any SQLCipher pooling / production optimization. This diagnostic slice intentionally does not touch production code or reduce test coverage.


## Mandatory FULL gates and Draft optimization slice (21.11.2026)
- The existing Android and CMP FULL workflows now include a cheap impact preflight. On Draft PR: **FAST/TARGETED** skip the FULL jobs and use independent advisory feedback; **FULL-classified (critical, shared UI/finance, database, CI/Gradle, unknown)** still executes the ENTIRE existing test suites. On ready-for-review, reopened ready, synchronize non-draft, main push, manual workflow_dispatch, or merge_group: FULL always runs.
- New `ci/verify_full_gate.py` + `ci/tests/test_full_gate.py` enforce fail-closed results from real jobs. Failed, cancelled, missing or skipped FULL jobs **cannot satisfy a ready PR gate**. The two stable ready-only check names are **`FULL Android merge gate`** and **`FULL CMP merge gate`**. **Draft checks use different names**: `Draft Android advisory` / `Draft CMP advisory`, so a successful Draft skip is not the same required context.
- FULL checkout pins an exact PR head SHA and verifies it before heavy tests; merge_group validates its merge candidate SHA. A new PR commit requires its own new FULL run; never infer success from a past SHA. Existing test tasks, Android emulator transport-loss retry and signed release verification are intact. For required checks, only treat the two new explicit FULL gate names as authoritative after owner enables them in GitHub branch protection; do not allow the Draft impact feedback or legacy skipped jobs to be substitutes.
- For future use, set Settings → Branches / Rulesets → main → required checks from GitHub Actions: `FULL Android merge gate` and `FULL CMP merge gate`. Require up-to-date branch or merge queue checks as repository policy; ensure approval on workflow changes. This connected GitHub app **cannot read or change branch protection (403)**, so the owner MUST verify settings manually before claiming merge safety.
- New workflow event types: opened, synchronize, reopened, ready_for_review, converted_to_draft, merge_group (plus existing manual/full and applicable main push). Revalidate actual draft→ready transition and FULL on exact SHA at owner acceptance. Until then #134 / PR #135 remain open/Draft.
