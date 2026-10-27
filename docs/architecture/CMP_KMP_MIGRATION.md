# Compose Multiplatform + Kotlin Multiplatform migration

**Status:** planned; migration branch only. The existing Android app remains the reference implementation until each phase passes its acceptance checks.

## Decision

Evaluate and migrate toward a shared Compose Multiplatform UI and Kotlin Multiplatform business/domain layer for Android, iOS, and Windows. Do not merge this migration into `main` until the existing Android workflow and real-device smoke tests pass.

## Why

The product goal is one shared UI and minimal duplicated code across Android, iOS, and Windows. Go plus platform-specific UIs does not meet that goal as directly as Compose Multiplatform. Kotlin can own the calculation rules without a Go/AAR bridge.

## Target structure

- `:androidApp`: Android application entry point and Android-specific integrations.
- `:shared`: common Compose UI, domain models, calculation rules, validation, and use cases.
- `:desktopApp`: Windows/JVM desktop entry point using the shared UI.
- `iosApp`: Xcode entry point hosting the shared Compose UI and iOS-specific integrations.
- Platform-specific adapters for encrypted storage, file pickers, PDF export, backup, and operating-system APIs.

The exact module names may be adjusted to minimize disruptive moves from the current `:app` module.

## Non-negotiable behavior and data constraints

- Preserve the current Android app as the behavioral reference during migration.
- Keep the database encrypted. Photos, drawings, and PDFs do not need separate file encryption.
- Do not lose existing user data or silently reset the database. Database migration/backup and restore must be tested.
- All price, area, perimeter, material, complexity, estimate, and additional-work calculations must have one source of truth in shared Kotlin code. No duplicated Kotlin/Go calculation implementations.
- Add regression tests for totals, especially the known issue where additional works appeared in PDF but were missing from on-screen totals.
- Preserve price-list demo seeding, clients, objects, measurements, estimate duplication/deletion, PDF output, and persisted data.
- Offline-first local use is the initial target. Cross-device synchronization remains undecided; keep the design extensible but do not add a server or account system yet.

## Compatibility and version policy

Use stable releases and an explicitly verified compatible version set. Do not select versions just because they are newest individually. Official Kotlin compatibility documentation currently lists Kotlin 2.4.20–2.4.21 with Gradle 7.6.3–9.7.0 and Android Gradle Plugin 8.5.2–9.3.1; Compose Multiplatform 1.12.2 is the current stable version listed in its official compatibility table. The current repository build uses AGP 9.4.0 and Gradle 9.8.2, outside that documented KMP compatibility range. Resolve this incompatibility deliberately before adding KMP plugins; document the supported stable toolchain chosen and why. Do not silently change versions or claim compatibility without a successful build.

References:
- https://kotlinlang.org/docs/multiplatform/multiplatform-compatibility-guide.html
- https://kotlinlang.org/docs/multiplatform/compose-compatibility-and-versioning.html
- https://kotlinlang.org/docs/multiplatform/compose-multiplatform-getting-started.html

## Migration phases

### Phase 0 — Baseline and compatibility audit
- [ ] Keep this work on `architecture/cmp-kmp-migration`; do not modify `main` directly.
- [ ] Record current app modules, Go/AAR dependency, database schema/encryption APIs, UI screens, and PDF/backup integrations.
- [ ] Choose and verify a compatible stable Kotlin/Compose/AGP/Gradle set before changing the build.
- [ ] Capture a working Android APK and the existing functional smoke-test checklist.

### Phase 1 — Build skeleton
- [ ] Add a minimal shared Compose UI and Windows/JVM desktop entry point.
- [ ] Add an iOS app/framework entry point; document that iOS compilation requires macOS + Xcode.
- [ ] Keep the existing Android app runnable while the new structure is validated.
- [ ] CI builds/tests the Android app and desktop target; run iOS framework build on a macOS runner when configured.

### Phase 2 — Shared domain and calculations
- [ ] Port Go calculation rules into pure common Kotlin, with explicit decimal/rounding rules.
- [ ] Port regression tests and verify equivalent outputs for existing sample estimates.
- [ ] Include additional works in all UI/PDF totals; test edge cases and invalid dimensions.
- [ ] Remove Go/AAR only after parity tests pass and no runtime feature depends on it.

### Phase 3 — Shared UI and platform adapters
- [ ] Move screens incrementally into shared Compose UI, starting with price list and area calculator.
- [ ] Then migrate clients, objects, measurements, estimates, and estimate detail.
- [ ] Implement platform adapters for encrypted database, PDF, files, and backup.
- [ ] Preserve theme, fonts, navigation, and list behavior across platforms.

### Phase 4 — Data safety and validation
- [ ] Test existing database upgrade, encrypted open, backup, restore, and failure handling.
- [ ] Test create/edit/delete/duplicate flows and persistence after app restart.
- [ ] Compare generated PDFs and totals with the current Android reference.
- [ ] Test Android on a physical device, Windows desktop, and iOS simulator/device before release.

### Phase 5 — Decide on synchronization
- [ ] Decide whether users need shared records across devices.
- [ ] If yes, separately design accounts, server API, conflict resolution, encryption in transit/at rest, and offline synchronization.
- [ ] If no, keep independent local databases and provide deliberate export/backup/restore.

## Merge criteria

- No merge while CI is red or migration tests are missing.
- Existing Android smoke tests pass and user data is preserved.
- Calculation parity and additional-work totals are verified.
- Android, Windows, and iOS builds have evidence appropriate to each platform.
- The user explicitly approves merging the migration PR.
