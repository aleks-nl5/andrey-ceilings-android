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
- Preserve existing user data; never reset or replace the database as a migration shortcut.
- The database must remain encrypted, including correct key handling and safe upgrade/restore behavior.
- Photos, drawings, and PDFs do not need separate file encryption.
- Preserve offline-first local use, price-list demo seeding, clients, objects, measurements, estimates, duplicate/delete flows, PDF generation, and backup/restore.
- Cross-device synchronization, accounts, and server infrastructure are out of scope for this migration phase.
- Keep the Go/AAR calculation core until the shared Kotlin implementation passes parity and regression tests. Do not maintain two active calculation sources of truth after the eventual cutover.
- Track and fix the known additional-works totals defect: additional works must be reflected consistently in the UI, saved estimate, and PDF.
- A successful compile alone is not proof of functional parity. Require CI and appropriate device/platform smoke tests.

## Target architecture (evolve only after compatibility checks)

- `:app`: retain the existing Android application and platform-specific integrations while migrating incrementally.
- `:shared`: common Kotlin domain models, validation, use cases, calculation logic, and shared Compose UI.
- `:desktopApp`: JVM desktop host for Windows, using shared UI.
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
- [x] Add the initial `:shared` Kotlin Multiplatform module with common Compose dependencies and a minimal shared component; the existing Android UI is untouched. Build verification is pending.
- [x] Add a minimal JVM Compose Desktop host; compilation verification is pending in CI.
- [ ] Add iOS framework targets and a runnable iOS host/Xcode project; verify on macOS/Xcode CI.
- [ ] Add/extend CI jobs for shared tests, Android APK, desktop compilation, and iOS framework. Android/shared tests pass; desktop compile has just been added; iOS CI remains pending.
- [ ] Confirm the existing Android APK still builds and launches with the original UI.
- [ ] Do not mark this phase complete until CI has completed successfully; cancelled jobs do not count.

### Phase 2 — Shared domain and calculations
- [ ] Inspect `go-core/core/calculator.go` and current app call sites before porting any rule.
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
- [ ] Verify opening and upgrading the existing encrypted database without data loss.
- [ ] Verify encryption and key storage rather than relying on configuration names alone.
- [ ] Verify backup/restore: valid password, invalid password, corrupt file, and data integrity.
- [ ] Test create/edit/delete/duplicate flows and persistence after restart.
- [ ] Compare estimate totals and PDFs against the current Android reference.
- [ ] Test Android on a physical device, Windows desktop, and iOS simulator/device where supported.

### Phase 5 — Decide on future synchronization separately
- [ ] Keep independent local data stores and deliberate export/backup/restore for now.
- [ ] Consider synchronization only as a separate future design decision.

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

## Progress log — 2026-10-27

- Created `architecture/cmp-kmp-migration-v2` from current `main`.
- Baseline Android workflow run `40584526142` completed successfully before build-system changes.
- Added the first `:shared` KMP module with Android, JVM, iOS device/simulator targets, common Compose dependencies, a small shared composable, and an initial common test. Workflow run `40586217184` passed all jobs, including Go tests, shared KMP assemble/JVM compile/common tests, Android unit tests, debug APK, and release APK.
- Added a minimal `:desktopApp` Compose Desktop host and a CI compile step; verification is pending in the next run.
- Updated the Gradle/AGP versions to the documented Kotlin 2.4.21 compatibility range (AGP 9.3.1 / Gradle 9.7.0). The next CI run must validate the new combination; KSP compatibility and iOS/desktop builds are not yet confirmed.
- The existing Android UI has not been replaced or connected to the new shared component.
