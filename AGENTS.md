# Agent instructions — Andrey Ceilings

## Current milestone (03.11.2026 — after PR #81 merge)
- **Price Catalog 2.0 P1 is merged:** [PR #81](https://github.com/aleks-nl5/andrey-ceilings-android/pull/81), final SHA `de8dddde`, merge `b4907ff`. SQLDelight v5 replaces only disposable test price rows; one shared KMP seed creates 5 editable groups / 22 items and a persistent marker. Historical SQLCipher data, immutable estimates/PDF, WAL and backups remain protected. The owner reports successful Android APK smoke; do not infer every granular checklist action was tested. Contract: `docs/PRICE_CATALOG_P1.md`.
- **Post-merge follow-up:** [#82](https://github.com/aleks-nl5/andrey-ceilings-android/issues/82) fixes Unicode-insensitive duplicates in **active group names and active item titles globally across the catalog** (SQLite NOCASE is ASCII-only), including cross-platform v5→v6 migration/collision safety tests. Titles are UI labels, not identity; keep stable item UUID/code. Keep implementation issue [#80](https://github.com/aleks-nl5/andrey-ceilings-android/issues/80) open pending that correction and verification; umbrella [#78](https://github.com/aleks-nl5/andrey-ceilings-android/issues/78) remains open. **Next UI stage P2:** [#83](https://github.com/aleks-nl5/andrey-ceilings-android/issues/83), shared CMP group editor and per-item quantity/placement parameters; explicit validation if unit changes, clear duplicate-title errors, no silent quantityMode changes, no option toggles. Owner approves UI/UX and merge. P3 integrates catalog choices into measurements (#76); P4+ live pricing (#44) and Canvas (#71). No CI benchmarking/time/cache comparisons for the scoped price work under owner decision.
- Measurement foundation PR #66/#68/#72 and B1/B2 (#73/#75) predate merged P1; consult the linked issues and `docs/CMP_MEASUREMENT_EDITOR_PLAN.md` for history. Do not interpret archival 'current B1' notes as the next price-catalog task. UI price follow-up is #83, and integrated ceiling work remains #76/#44/#71.
- The owner confirmed **all existing database records are test data**. No old drawing/legacy Room/Go DB JSON/archive compatibility requirement for PR A/B; do not add migration shims for old test data. Do not automatically destroy the database or regress new geometry records, encrypted storage, existing estimate/PDF semantics, or approved precision rules.
- Historical Markdown/old Draft statuses are archival; use current roadmap and current code over historical checkpoints. 
- Measurement creation contract: `docs/CMP_MEASUREMENT_CREATE.md` (PR #72 merged). B1 editing contract: `docs/CMP_MEASUREMENT_EDIT.md`; B2 was merged before PR #81. Refer to current issue states instead of historical 'keep open' instructions, and do not reintroduce retired Android-only editor behavior.

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
