# Agent instructions — Andrey Ceilings

## Current milestone (03.11.2026)
- PR #66 (CMP measurement read-only) and PR #68 (Windows SQLCipher JDBC RETURNING) are merged. Issue #67 is closed. **PR #72 (CMP creation) is merged and owner-tested. Current: PR B1 safe editing/fullscreen (#73); B2 global-tab/cutover follows separately.** See `docs/CMP_MEASUREMENT_EDITOR_PLAN.md` and issues #60/#57.
- The owner confirmed **all existing database records are test data**. No old drawing/legacy Room/Go DB JSON/archive compatibility requirement for PR A/B; do not add migration shims for old test data. Do not automatically destroy the database or regress new geometry records, encrypted storage, existing estimate/PDF semantics, or approved precision rules.
- Historical Markdown/old Draft statuses are archival; use current roadmap and current code over historical checkpoints. 
- PR A implementation/verification contract: `docs/CMP_MEASUREMENT_CREATE.md`. Shared creation from Project Details is merged in PR #72 (final b9effed, merge 55fb717), with green Android/Windows/iOS CI and owner APK acceptance. B1 contract: `docs/CMP_MEASUREMENT_EDIT.md`. Keep #60/#57/#73 open; leave the global tab and Android-only editor for B2.

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
