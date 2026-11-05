# Agent instructions — Andrey Ceilings

## Current milestone (05.11.2026 — P3C5 handoff; NOT implemented)

- **Accepted baseline:** P3C4 [PR #101](https://github.com/aleks-nl5/andrey-ceilings-android/pull/101) **merged** 05.11.2026 15:08 UTC, merge SHA `f6d3993e55c1bc99c1baeab24e39e371ae033cbd`, SQLDelight/SQLCipher **v12**; final feature SHA `21b7fe3b89d5891fe5a8bc557b5f0b91b345d283`, Android [CI #42037345796](https://github.com/aleks-nl5/andrey-ceilings-android/actions/runs/42037345796) and Windows/iOS [CI #42037345362](https://github.com/aleks-nl5/andrey-ceilings-android/actions/runs/42037345362) success; owner smoke-tested and merged. P3C4 issue #100 **closed/completed**. Older P3A–P3C3 also merged.
- **Current authorized feature:** [P3C5 issue #102](https://github.com/aleks-nl5/andrey-ceilings-android/issues/102) / [full standalone implementation contract](docs/MEASUREMENT_AREA_FEATURES_P3C5.md): AREA/OBSTACLE/VOID_MARKER **overlays only**, polygon/rectangle preset and true circle with mm geometry, typed unpriced annotation or explicit `CatalogSelection` snapshot `PlacementKind.AREA` + compatible unit; wall anchors, review/unresolved, quantities computed from geometry independently of declared quantity, shared CMP Canvas + Android/Windows hosts, single atomic contour+POINT+LINE+AREA Save, safe SQLDelight/SQLCipher v12→v13 migration and platform tests. **Implementation NOT started / current branch contains only documentation.**
- **Existing Codex working branch:** `feat/p3c5-area-obstacles` with [Draft PR #103](https://github.com/aleks-nl5/andrey-ceilings-android/pull/103). Continue in the **same** branch/PR; no duplicate PR, no direct `main` writes or auto-merge. This branch was created from current `main` after merged #101.
- **Deferred:** P4 #44 live KMP estimate/pricing after P3C5. Full UX redesign #95 after functional P3C/P4, including known line-placement/control/short-line hit-test problems transferred from PR #101; **do not implement full #95 in P3C5**. Umbrella #71/#76/#78 open, as well as separate #70 solver/#42 platforms. Unpriced obstacles/void markers **never automatically subtract ceiling area** or generate price rows.
- **Preserve:** all P3C3 wall UUID + measured mounting, P3C4 normalized LINE + integer lengths + independent declared quantities, sticky unresolved/review, SQL `ceiling_operations` full-set CAS/receipts and physical rollback, source-version fingerprints, both known deployed-v10 schemas, v11/v12 history; SQLCipher keys/WAL/portable backup/restore, frozen historical estimates/revisions and PDF. Reject partial/hybrid schemas, no reset/reseed/unsafe SQL bypass.
- **Acceptance:** actual code/tests → Android + Windows/iOS **same final feature SHA** CI success → owner APK smoke/review → only owner decides Ready/Merge. Do not mark checklists green early. UX polish is separately tracked.

## Read before making changes
- Review `README.md`, `docs/PROJECT_ROADMAP.md`, and the relevant GitHub Issue and architecture notes. Check the real code before trusting older documentation.
- Implement only the requested issue scope. Keep unrelated refactors and platform migrations out of the PR. If requirements are unclear, document the tradeoff instead of silently changing behavior.

## Architecture and data safety
- Prefer shared Kotlin Multiplatform logic and Compose Multiplatform UI in `shared/src/commonMain`; Android, Windows and (when runnable) iOS hosts should be thin platform adapters.
- Use existing SQLDelight/SQLCipher repositories, reactive Flow, domain models and navigation state. Do not create duplicate Android-only business logic or mock production data.
- Preserve UUID / soft-delete semantics, SQLCipher encryption and WAL, backup/export/restore, immutable estimate snapshots, price calculation precision, numbering, and PDF behavior. Change these only when explicitly scoped and tested.
- Never expose credentials, DB keys, private records, or unredacted personal information in routes, logs, build artifacts, PR bodies or tests.
- Treat downloaded Draft APK schemas as distributed contracts. A future SQL definition change needs a real migration; do not reuse a persisted schema version with incompatible guards. Preserve known source lineages explicitly when correcting an existing transition.
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
