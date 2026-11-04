# Agent instructions — Andrey Ceilings

## Current milestone (04.11.2026 — P3C2 merged; P3C3 implementation)

- **Current main baseline:** P3C2 [PR #97](https://github.com/aleks-nl5/andrey-ceilings-android/pull/97), merge `c34a2d35348a9534a620728be3cec0a1e85ae566`. Owner confirmed detailed successful APK testing; Android [#41889348040](https://github.com/aleks-nl5/andrey-ceilings-android/actions/runs/41889348040) and Windows/iOS [#41889347650](https://github.com/aleks-nl5/andrey-ceilings-android/actions/runs/41889347650) succeeded on `1e280539123d6a72ba4df3436f8f3aae8ee2e9fc`. This acceptance does not accept P3C3.
- **Previous contracts:** Price 2.0 P1/P2 (#81/#85/#86), P3A (#88), P3B (#91), P3C1 (#94), P3C2 (#97) are merged. See `docs/MEASUREMENT_ELEMENTS_P3A.md`, `docs/MEASUREMENT_ELEMENTS_P3B.md`, `docs/MEASUREMENT_POINT_PLACEMENTS_P3C1.md` and `docs/MEASUREMENT_POINT_EDIT_P3C2.md` for their historical boundaries.
- **Current authorized slice:** P3C3 [#98](https://github.com/aleks-nl5/andrey-ceilings-android/issues/98), [Draft PR #99](https://github.com/aleks-nl5/andrey-ceilings-android/pull/99), `feat/p3c3-wall-referenced-ceiling-editor`. Contract: `docs/MEASUREMENT_WALL_ANCHORS_P3C3.md`. Implementation/verification are in progress, not owner-accepted functionality.
- **P3C3:** one shared contour+points draft/Save; physical two-wall perpendicular distances or one-wall along/inward; persistent directed wall identities; safe remap, valid/unresolved reservations and durable review/later. X/Y are internal. Legacy records never acquire invented measured anchors. Replace the old unconditional active-point contour block only through an equally fail-closed atomic operation.
- **Data invariants:** additive v9→v10, verified physical encrypted upgrade/rollback/reopen and backups; immutable point UUIDs, tombstones and old receipts. Unresolved reserves capacity but is excluded from valid placed count. Point precision and review/provenance are separate. No automatic reset/reseed, SQLCipher key/WAL/format changes, financial repricing, historical estimate/PDF rewrite or new platform host.
- **Outside P3C3:** full UX redesign #95, line/area/void, solver #70, pricing/estimates #44, new runnable hosts #42. #71/#76/#78 remain broader open scopes. Do not merge/close issues or mark owner APK acceptance automatically.

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
