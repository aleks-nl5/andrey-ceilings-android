# Agent instructions — Andrey Ceilings

## Current milestone (04.11.2026 — P3A measurement elements, issue #87)
- **Price 2.0 baseline is merged:** P1 [#81](https://github.com/aleks-nl5/andrey-ceilings-android/pull/81), Unicode v6 [#85](https://github.com/aleks-nl5/andrey-ceilings-android/pull/85), and P2 [#86](https://github.com/aleks-nl5/andrey-ceilings-android/pull/86), merge `fd965db1071d37894726d8eb8bb9d9bcfafd8fe0`. #80/#82/#83 are closed; umbrella #78 and product #76 stay open. The owner accepted the P2 APK/CI and deferred cosmetic UI/UX work. Do not infer granular smoke actions or both themes independently confirmed.
- **Current scope: [#87](https://github.com/aleks-nl5/andrey-ceilings-android/issues/87), P3A.** Shared typed measurement elements/wishes, SQLDelight v7 (`6.sqm`), CAS, snapshots, tombstones and Android/Windows/iOS encrypted persistence contracts. See `docs/MEASUREMENT_ELEMENTS_P3A.md`. One feature branch and Draft PR; do not merge or close issues. Platform acceptance requires final-head Android and Windows/iOS CI, not only local JVM tests.
- **P3A invariants:** one existing `room_measurements` plus normalized `measurement_elements`; catalog identity by UUID; no price in elements, no geometry writes, no precision recalculation, no UI/Canvas. Unknown, exact, range and derived quantities remain distinct integer thousandths. Approval is independent of placement, which P3A can only initialize as unplaced/0. Do not reseed, reset, cascade-delete or change immutable estimates/PDF/WAL/keys.
- **Next:** P3B adds CMP elements to existing measurement flows. Pricing #44 and placements/Canvas #71 remain separate. Cosmetic price UI/UX and runnable iOS host #42 remain follow-ups.
- Measurement foundation PR #66/#68/#72 and B1/B2 (#73/#75) predate P1/P2; historical Markdown statuses are archival. Preserve measurement create/edit CAS, UUID/tombstones, geometry and captured precision. Contracts: `docs/CMP_MEASUREMENT_CREATE.md`, `docs/CMP_MEASUREMENT_EDIT.md`.
- The owner confirmed existing records are test data. No legacy Room/Go/drawing import compatibility work is required; automatic DB destruction remains forbidden. Preserve existing SQLCipher data and backups through controlled upgrades.

## Read before making changes
- Review `README.md`, `docs/PROJECT_ROADMAP.md`, and the relevant GitHub Issue and architecture notes. Check the real code before trusting older documentation.
- Implement only the requested issue scope. Keep unrelated refactors and platform migrations out of the PR. If requirements are unclear, document the tradeoff instead of silently changing behavior.

## Architecture and data safety
- Prefer shared Kotlin Multiplatform logic and Compose Multiplatform UI in `shared/src/commonMain`; Android, Windows and (when runnable) iOS hosts should be thin platform adapters.
- Use existing SQLDelight/SQLCipher repositories, reactive Flow, domain models and navigation state. Do not create duplicate Android-only business logic or mock production data.
- Preserve UUID / soft-delete semantics, SQLCipher encryption and WAL, backup/export/restore, immutable estimate snapshots, price calculation precision, numbering, and PDF behavior. Change these only when explicitly scoped and tested.
- Never expose credentials, DB keys, private records, or unredacted personal information in routes, logs, build artifacts, PR bodies or tests.
- Do not reintroduce Go, Room, or gomobile, or assume an iOS runnable UI exists. Refer to issue #42 for iOS host status.
- Keep platform-specific capabilities (e.g. Android gesture bridge, Keystore, PDF, storage permissions) in platform modules, with shared contracts where appropriate.

## Implementation and verification
- Prefer small, focused changes with matching `commonTest`/JVM tests and platform checks. Preserve currently working behavior on Android and Windows.
- The repo uses JDK 21 and Gradle 9.7.0 in CI (no checked-in Gradle wrapper). Example local checks, when tools are present: `gradle --no-daemon :shared:jvmTest :app:testDebugUnitTest` and `gradle --no-daemon :desktopApp:compileKotlin`.
- GitHub Actions `Build Android APK` and `Verify CMP KMP platforms` are mandatory before merge: Android build, unit/instrumentation tests, Windows/JVM, and iOS Simulator. Never claim a platform or smoke test passed unless it actually ran successfully.
- Android UI changes require owner-performed APK smoke on the final PR SHA in both light and dark themes. CI cannot replace this acceptance step.

## GitHub delivery contract
- Work on a feature branch and open a **Draft PR** into `main`, linking the issue; do not push directly to `main`.
- PR description: scope, behavior, key files, migrations/invariants, tests run and their results, CI links/status, manual APK smoke checklist, known limitations and follow-ups.
- Keep the PR Draft until the owner accepts the implementation and final-SHA checks pass. Do not auto-merge, close related issues, alter branch protection, or mark manual tests complete on the owner's behalf.
