# Compose Multiplatform + Kotlin Multiplatform migration

**Status:** initial planning on `architecture/cmp-kmp-migration-v2`, created from the current `main`. No product screen is considered migrated until it is implemented and verified on the target platform.

## Purpose and strategy

Move Andrey Ceilings incrementally toward Compose Multiplatform (CMP) for shared UI and Kotlin Multiplatform (KMP) for shared domain/business logic across Android, iOS, and Windows desktop. Avoid a wholesale rewrite. The current Android app on `main` is the behavioral and visual reference.

This branch supersedes the technical approach in PR #21, but PR #21 remains an information source. Do not merge or cherry-pick its branch wholesale: it diverged from current `main` and contains older build/dependency choices and an earlier UI baseline.

## Preserve current work on main

Before moving screens, inventory and preserve the current UI and interactions, including:
- Light, dark, and system theme modes, with the selected theme persisted and shared between the top bar and settings.
- Local Inter font assets and the established typography weights/spacing.
- Consistent themed cards and list surfaces on Objects, Measurements, and Estimates.
- Calendar date selection and spacing.
- Currency card labels, values, UAH notation, and latest-update timestamp.
- Current navigation/back behavior, scroll reset behavior, edge-to-edge/system-bar insets, and keyboard handling.
- Existing screen layouts and any UI work already merged into `main`. The migration scaffold must not replace the real app with a placeholder screen.

The full UI redesign is not yet complete. CMP migration and remaining visual redesign must be sequenced deliberately, not combined as one giant rewrite.

## Non-negotiable product constraints

- Android remains the primary platform during migration.
- The current app is still in the test phase and its database contains test data only; the owner accepts that this data may be discarded during the planned Room → SQLDelight transition. Do not spend effort migrating these test records. Clearly document any intentional reset and do not silently reset data in a later production release.
- The target database must be encrypted on Android, iOS, and Windows, including correct key handling and safe backup/restore behavior. SQLDelight does not itself encrypt the SQLite database; select and verify a compatible encryption strategy before treating SQLDelight as approved for production.
- Photos, drawings, and PDFs do not need separate file encryption.
- Preserve offline-first local use, price-list demo seeding, clients, objects, measurements, estimates, duplicate/delete flows, PDF generation, and backup/restore.
- Do not implement cross-device synchronization, accounts, or server infrastructure in this phase. Design the local data layer offline-first and synchronization-ready (stable record IDs, explicit repositories, timestamps/change metadata where useful, and deletion/conflict handling that can be added later) without coupling it to a specific server or cloud provider.
- Planned direction: remove Go/AAR and move calculation/domain logic to Kotlin Multiplatform. Keep Go/AAR only as a temporary reference during the transition; remove it after representative parity fixtures and regression tests pass and all runtime consumers are migrated. Do not maintain two active calculation sources of truth after cutover.
- Track and fix the known additional-works totals defect: additional works must be reflected consistently in the UI, saved estimate, and PDF.
- A successful compile alone is not proof of functional parity. Require CI and appropriate device/platform smoke tests.

## Target architecture (evolve only after compatibility checks)

- `:app`: retain the existing Android application and platform-specific integrations while migrating incrementally.
- `:shared`: common Kotlin domain models, validation, use cases, calculation logic, SQLDelight schema/queries/repositories, and shared Compose UI.
- SQLDelight is the selected database migration candidate to replace Room, conditional on proving encrypted SQLite compatibility and correct operation on Android, iOS, and Windows. Keep SQLDelight-facing APIs in common code and isolate platform-specific driver/encryption setup behind platform source sets/adapters.
- `:desktopApp`: JVM desktop host for Windows, using shared UI and a verified local database driver.
- `iosApp`: a runnable Xcode/SwiftUI host for the shared Compose UI, plus iOS-specific integration.
- Platform-specific adapters for encrypted storage, document/file pickers, PDF generation, backup/restore, and OS APIs.

Module names may change if the current app can be preserved with less disruption. Do not move all Android code into a new module just to match an idealized diagram.

## Toolchain policy

The current `main` uses AGP 9.4.0, Kotlin Compose plugin 2.4.21, KSP 2.3.12, and Gradle 9.8.2 in CI. PR #21 used an older CMP/KMP toolchain. Neither set should be copied blindly.

Compatibility audit (2026-10-27): the official Kotlin Multiplatform compatibility table lists Kotlin 2.4.20–2.4.21 with Gradle up to 9.7.0 and AGP up to 9.3.1; the current `main` toolchain exceeds those listed maxima. The official Compose compatibility page says current stable Compose Multiplatform 1.12.2 is compatible with the latest Kotlin release (currently 2.4.21). Therefore, before adding KMP plugins, select a stable, explicitly compatible set around Kotlin 2.4.21 + Compose Multiplatform 1.12.2 and align AGP/Gradle to the officially supported range, then validate KSP 2.3.12 against that selection. This may require a deliberate toolchain adjustment, but do not downgrade blindly: keep the existing Android build working and use completed CI as evidence. References: https://kotlinlang.org/docs/multiplatform/multiplatform-compatibility-guide.html and https://kotlinlang.org/docs/multiplatform/compose-compatibility-and-versioning.html.

References:
- https://kotlinlang.org/docs/multiplatform/multiplatform-compatibility-guide.html
- https://kotlinlang.org/docs/multiplatform/compose-compatibility-and-versioning.html
- https://kotlinlang.org/docs/multiplatform/compose-multiplatform-getting-started.html
- https://kotlinlang.org/docs/ksp-overview.html

## Migration phases

### Phase 0 — Baseline and inventory
- [x] Confirm the new branch starts at the current `main` and keep PR #21 separate.
- [x] Run the existing Android CI/build unchanged and capture a baseline result (workflow run 40584526142 succeeded before build-system changes).
- [ ] Inventory current screens, theme, fonts, navigation, data models, Room/SQLCipher encryption, Go/AAR calls, PDF, backup/restore, and existing tests.
- [ ] Record the current Android smoke-test checklist and known bugs.
- [ ] Verify official compatibility guidance and select the KMP/CMP toolchain before changing Gradle files.

### Phase 1 — Minimal multiplatform build foundation
- [x] Add the initial `:shared` Kotlin Multiplatform module with common Compose dependencies and a minimal shared component; the existing Android UI is untouched. CI passed on run `40595098996`.
- [x] Add a minimal JVM Compose Desktop host that consumes `SharedFoundationStatus()` from `:shared`; desktop compilation passed on runs `40595076763` and `40595098996` after fixing the missing Material 3 dependency.
- [ ] Add iOS framework targets and a runnable iOS host/Xcode project; the iOS framework target is configured in `:shared`, and a new macOS workflow now verifies framework linking. A runnable Xcode/SwiftUI host is still pending.
- [x] Add/extend CI jobs for shared tests, Android APK, desktop compilation, and iOS framework. Android, shared, and desktop checks passed on run `40595098996`; the new macOS iOS-framework workflow is pending its first result.
- [x] Confirm the existing Android APK still builds in CI with the original UI unchanged (run `40595098996`); manual launch check for this migration branch remains pending.
- [ ] Do not mark this phase complete until CI has completed successfully; cancelled jobs do not count.

### Phase 2 — Shared domain and calculations
- [ ] Inspect `go-core/core/calculator.go` and current app call sites before porting any rule.
- [x] Add the first pure common-domain slice: client, project, and room-measurement models plus input validation. Common tests and Android/platform CI pass; this is still an isolated domain slice.
- [ ] Add SQLDelight to `:shared` as the planned replacement for Room after verifying current plugin/runtime/driver compatibility with Kotlin 2.4.21 and the target platforms.
- [ ] Define common SQL schema and queries for clients, projects, measurements, price items, estimates, and estimate lines; treat current test data as disposable for this schema transition.
- [ ] Prove database encryption on Android, iOS, and Windows before marking the shared database layer complete; do not assume SQLDelight provides encryption by itself.
- [ ] Add common repository interfaces and platform drivers/adapters; connect one low-risk CRUD flow and test persistence before migrating all entities/screens.
- [ ] Create representative Go/Kotlin input-output fixtures and compare exact business outputs.
- [ ] Port geometry, price/cost, complexity, options, additional works, and estimate totals to common Kotlin in small slices.
- [ ] Define rounding, money precision, units, invalid-input behavior, and defaults explicitly.
- [ ] Add common tests for normal, boundary, invalid, and historical estimate cases.
- [ ] Verify additional works are included in UI, persisted totals, and PDF.
- [ ] Remove Go/AAR only after parity is demonstrated, all runtime consumers are migrated, and regression tests pass.

### Phase 3 — Shared UI and platform adapters
- [ ] Preserve the existing Android app as the reference; do not swap in a placeholder UI.
- [ ] Prototype one small real screen and compare appearance/behavior against current Android.
- [ ] Migrate real screens incrementally, starting with a low-risk screen (price list or area calculator), after data/domain boundaries are clear.
- [ ] Migrate clients, objects, measurements, estimates, and estimate editing in separate slices.
- [ ] Preserve theme modes, Inter typography, navigation, scroll behavior, system insets, keyboard handling, and accessibility.
- [ ] Isolate encrypted database, files, PDF, backup, and OS APIs behind platform interfaces.
- [ ] Keep Android behavior stable before broadening to iOS/Desktop.

### Phase 4 — Data safety and end-to-end validation
- [ ] Since the current database contains disposable test data, validate a clean SQLDelight database creation instead of migrating Room test records; retain backup/restore behavior as a product feature.
- [ ] Verify encryption and key storage on Android, iOS, and Windows rather than relying on configuration names alone.
- [ ] Verify backup/restore: valid password, invalid password, corrupt file, and data integrity.
- [ ] Test create/edit/delete/duplicate flows and persistence after restart.
- [ ] Compare estimate totals and PDFs against the current Android reference.
- [ ] Test Android on a physical device, Windows desktop, and iOS simulator/device where supported.

### Phase 5 — Keep the architecture ready for future synchronization
- [x] Decide that synchronization is a future feature, not part of the current migration.
- [ ] Keep the app offline-first: all core operations work from the local SQLDelight database without network access.
- [ ] Use stable cross-device record identifiers and repository boundaries; add creation/update metadata and deletion markers when the data model needs them.
- [ ] Avoid coupling repositories/domain models to a specific cloud provider or server protocol.
- [ ] When synchronization becomes a priority, separately design change transport, authentication, conflict resolution, deletion propagation, retries, and encryption in transit.

## Status inherited from PR #21 (historical, not verified on this branch)

PR #21 established a useful direction and documentation, and proposed:
- a `:shared` Compose module, `:desktopApp` JVM entry point, iOS framework named `AndreyCeilingsShared`, and CI workflows;
- a first common-Kotlin geometry calculator and seven common geometry tests;
- gradual screen migration, domain parity tests, and retaining Go/AAR until parity;
- explicit merge gates for CI, real-device checks, encrypted database, PDF, backup/restore, and user approval.

However, the PR's Android workflow had been cancelled during APK build and its iOS workflow during framework compilation; the geometry tests were not confirmed by a completed CI run at the time of that report. Its code and build settings are not assumed to be correct or compatible with current `main`. Re-audit and reimplement only what remains useful.

## Acceptance / merge criteria

- Current Android build and all existing screens remain functional.
- Real shared UI screens exist; a scaffold alone is insufficient.
- Common calculations have verified parity/regression tests before Go/AAR removal.
- Database encryption, existing data, PDF, backup/restore, and offline use are preserved.
- CI succeeds for applicable Android, shared, desktop, and iOS targets.
- Required real-device/platform smoke tests are completed.
- The owner explicitly approves merging; keep the migration PR Draft until then.

## Progress log — 2026-10-28

- Latest confirmed CI runs `40651441664` (Android APK) and `40651441699` / `40651436609` (iOS simulator framework, Compose Desktop compilation, shared JVM tests) succeeded at commit `8a9d2817`.
- Added `shared/.../domain/DomainModels.kt` with platform-independent `Client`, `Project`, and `RoomMeasurement` data classes and validation helpers. Added common tests for valid/invalid input. These are parallel domain types only: no Room schema, existing records, Android screens, or Go calculation code was changed. CI for this new slice is pending.

## Progress log — 2026-10-27

- Created `architecture/cmp-kmp-migration-v2` from current `main`.
- Baseline Android workflow run `40584526142` completed successfully before build-system changes.
- Added the first `:shared` KMP module with Android, JVM, iOS device/simulator targets, common Compose dependencies, a small shared composable, and an initial common test. Workflow run `40586217184` passed all jobs, including Go tests, shared KMP assemble/JVM compile/common tests, Android unit tests, debug APK, and release APK.
- Added a minimal `:desktopApp` Compose Desktop host and a CI compile step. The first desktop CI attempt failed because the host imported Material 3 directly without a desktop dependency; changed the host to consume `SharedFoundationStatus()` from `:shared` (commit `d712309d`). Corrected CI runs `40595076763` and `40595098996` succeeded, including desktop compilation.
- Updated the Gradle/AGP versions to the documented Kotlin 2.4.21 compatibility range (AGP 9.3.1 / Gradle 9.7.0). CI runs `40595076763` and `40595098996` passed with this combination, including KSP-dependent Android build, shared JVM tests, desktop compilation, and APK artifacts. iOS framework linking remains to be confirmed by the new macOS workflow.
- Added `.github/workflows/cmp-kmp-platforms.yml` on commit `6c61b0e` to compile the iOS simulator framework and desktop host on macOS and run shared JVM tests. Its first run is pending.
- The existing Android UI has not been replaced or connected to the new shared component.


## Architecture decision update — SQLDelight, Go retirement, and future sync (2026-10-28)

- **Database direction:** SQLDelight is the preferred replacement candidate for Room because it can share schema, SQL queries, and typed APIs across KMP targets. This is a conditional decision: first prove a viable encrypted SQLite configuration for Android, iOS, and Windows, and verify driver/native compatibility. SQLDelight alone is not encryption.
- **Test data:** the current database contains test data only and may be recreated during the Room → SQLDelight transition. Do not build a production-data migration path just to preserve these records. Any reset must be explicit and limited to this migration/testing stage.
- **Calculation direction:** Kotlin Multiplatform is the intended final source for domain and estimate calculations. Go/AAR is transitional and must not be removed until parity fixtures, regression tests, and runtime call-site migration are complete.
- **Future sync:** do not build network synchronization now. Keep local/offline-first behavior and avoid architecture choices that make future change tracking, stable cross-device IDs, deletions, or conflict resolution unnecessarily difficult. No server/provider is selected.
- **Next technical gate:** compare SQLDelight's supported Kotlin/plugin/runtime versions with the current toolchain; prototype database creation and CRUD with encryption on each target; only then replace Room incrementally.
