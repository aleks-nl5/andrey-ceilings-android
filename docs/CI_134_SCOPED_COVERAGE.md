# Issue #134 — platform-scoped CI acceptance (22 Nov 2026)

> Status: implementation proposed on a **separate Draft PR**; not accepted or merged.
> Supersedes the old "FULL on every Ready PR and main push" policy in
> `docs/CI_134_CODEX_HANDOFF.md`. The repository owner reviews and merges manually.
> No Branch Protection, required checks, or automatic merge.

## Why

Windows-native SQLCipher/JDBC regression and headless Desktop Compose suites
are intentionally expensive. A typical Android UI change is not proof of any
Windows database regression. Do not delete tests or silently mislabel skips.

## Source-set mapping and supported depth

`ci/impact.py` computes a **union** of changed-file impact. Each platform
receives one of `skip`, `smoke`, `deep` (highest wins). `FULL` means deep on
Android, Windows, macOS/JVM and iOS; `TARGETED` can mix depths.

| Change | Android | Windows | macOS JVM | iOS |
| --- | --- | --- | --- | --- |
| Docs-only | skip | skip | skip | skip |
| Android app/androidMain UI-only | smoke | skip | skip | skip |
| Windows JVM JDBC/SQLCipher or Windows test | skip | deep | deep | skip |
| Desktop UI-only | skip | smoke | smoke | skip |
| iOS-specific app/iosMain UI-only | skip | skip | skip | smoke |
| Known shared Compose UI/theme/navigation/drawing presentation | smoke | smoke | smoke | smoke |
| Shared DB, SQLDelight, pricing/business/geometry, commonTest | deep | deep | deep | deep |
| CI, Gradle, dependencies, unknown/deleted/renamed/incomplete diff | deep | deep | deep | deep |
| Mixed platform changes | union of impact, never downgrade an affected platform |

The shared UI allowlist is *deliberately narrow*: a filename cannot prove a
data-contract change safe. Expand only with concrete source/consumer evidence
and tests. Files not recognized by the map must trigger global FULL.

## What actually runs

- **Android smoke**: checked-out exact head SHA; build debug APK, run
  `:app:testDebugUnitTest` and `:shared:compileKotlinJvm`; Android emulator
  runs focused `MeasurementWorkspaceLayoutTest`, `OverlayDragTouchTest`,
  `AreaFeatureTouchTest` (no broad SQLCipher/process-death/ENOSPC run).
  APK 7-day retention, XML reports 30 days. No retry of assertion failures.
- **Android deep**: existing all-instrumentation, encrypted SQLDelight,
  isolate process death/upgrade/ENOSPC, signed APK/release verification exactly
  as before, with the existing transport-disconnect-only retry.
- **Windows smoke**: real Windows runner, Desktop compile and
  `CeilingsFoundationSceneTest` (no `:shared:jvmTest` native encrypted suite).
- **Windows deep**: existing native SQLCipher/JDBC `:shared:jvmTest` plus
  `:desktopApp:test`; timings/profiling and 30-day reports preserved.
- **macOS/JVM smoke**: compile Desktop/shared; **deep**: full existing JVM tests.
- **iOS smoke**: link real Simulator framework; **deep**: existing iOS
  Simulator SQLCipher tests in addition to framework compile.

Smoke is NOT deep platform certification. In particular, a successful macOS
JVM job does **not** replace native Windows encrypted database verification.

## Events and gates

- Draft with FAST/TARGETED: existing independent Draft advisory does the
  lightweight work; Android and CMP full/scoped jobs remain skipped and are
  clearly marked *Draft*, **not** merge authorization.
- Draft with FULL fallback: deep all-platform suites run, but still no Ready
  acceptance.
- Ready PR, reopened Ready, or Ready synchronize: select and execute actual
  impacted platform depth on the **current PR head SHA**. The stable
  Android/CMP receipts report each requested group and its real job conclusion,
  including **NOT VERIFIED** for every skipped platform.
- Push to main: classify the verified `github.event.before...github.sha`
  diff; if the before SHA or history is not available, FULL. A normal Android
  UI merge does not restart Windows deep suites just because it reached main.
- Manual `workflow_dispatch` and merge queue candidates always request
  all-platform **deep FULL**. To run Windows FULL intentionally, dispatch
  **Verify CMP KMP platforms** against the chosen branch; check the exact
  source SHA and all native job conclusions. For Android FULL, dispatch
  **Build Android APK** separately.

The CI receipt checks concrete job conclusions and exact event SHA. A skipped,
failed, cancelled or missing **selected** job cannot pass. An unselected job can
be skipped, but must never be called verified. Both Android and CMP receipts
are reviewed together; green scoped receipts are **not all-platform FULL**.

This is an owner-reviewed policy without GitHub Branch Protection. Do not
enable rulesets, required checks or automatic PR merging without a new owner
decision.

## Risk-specific exceptions and evidence

- Production Windows SQLCipher/JDBC pooling (#147 / Draft PR #148) remains a
  distinct high-risk task requiring native Windows encrypted security/integrity
  regression. Its own final acceptance may demand manual all-platform FULL
  beyond the automatic source impact scope; do not weaken that issue.
- This CI-policy PR itself touches `.github/` and `ci/` and must therefore
  trigger full native regression as the safe fallback.
- Validate job **selection** in real Actions for Android UI-only, Windows
  JDBC-only, shared CMP UI, shared DB, unknown and CI changes. Unit tests alone
  do not prove Actions condition evaluation.
- Baseline Windows run in #134 had 499 cases and multi-hour cumulative timings.
  Measure before/after **actual** runner wall-clock and runner minutes after
  corresponding real CI runs. No savings are asserted by this document.
- Existing #1304 Android keyboard/drag and #1311 PDF scene flakes should be
  diagnosed against main, not removed or masked.

## Owner acceptance checklist

1. Inspect classifier outputs, skipped groups and both Ready receipts on the
   identical final head SHA; confirm that every impacted group actually ran.
2. Review native Windows security results for Windows DB changes, and explicit
   full-run evidence when #147 or a Windows release requires it.
3. Review debug APK and test on device when UI changes are involved.
4. Get independent code review; the owner alone changes Draft to Ready and
   performs merge. Issue #134 stays open until the real Actions scenarios and
   comparative costs are verified.

A recurring Windows FULL schedule is **not enabled automatically**. The manual
FULL workflow remains available; consider a weekly schedule only after
estimating runner cost and obtaining owner approval.
