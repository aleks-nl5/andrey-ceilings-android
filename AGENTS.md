# Agent instructions — Andrey Ceilings

## Current milestone (04.11.2026 — PR #86 P2 merge preparation; PR #85 already merged)
- **Price Catalog 2.0 P1 is merged:** [PR #81](https://github.com/aleks-nl5/andrey-ceilings-android/pull/81), final SHA `de8dddde`, merge `b4907ff`. SQLDelight v5 replaces only disposable test price rows; one shared KMP seed creates 5 editable groups / 22 items and a persistent marker. Historical SQLCipher data, immutable estimates/PDF, WAL and backups remain protected. The owner reports successful Android APK smoke; do not infer every granular checklist action was tested. Contract: `docs/PRICE_CATALOG_P1.md`.
- **Unicode P1 follow-up is merged and owner-accepted:** [PR #85](https://github.com/aleks-nl5/andrey-ceilings-android/pull/85), merge `83a5d21`, adds Unicode-safe global active group/item names and v6 migration. #82 and #80 are closed; umbrella [#78](https://github.com/aleks-nl5/andrey-ceilings-android/issues/78) stays open. Do not restore the old NOCASE-only rules or reseed deleted categories.
- **P2 implemented, pending merge:** [PR #86](https://github.com/aleks-nl5/andrey-ceilings-android/pull/86) implements [#83](https://github.com/aleks-nl5/andrey-ceilings-android/issues/83) by **evolving the existing** CMP price screen. UI «Категорії» are existing SQLDelight `price_groups`; the shared KMP store adds real group Flow/UUID filters, group CRUD/defaults and independent item quantity/placement parameters. No new price route, schema, options catalog, reset or startup restoration. Functional HEAD `d92bac4893c23cf65bbea1a8a9e6da5b48420871`: [Android](https://github.com/aleks-nl5/andrey-ceilings-android/actions/runs/41804457036) and [Windows/iOS](https://github.com/aleks-nl5/andrey-ceilings-android/actions/runs/41804456931) CI **success**. The owner reports having checked the APK; do **not** infer each granular smoke checklist item or both theme modes independently confirmed. Documentation-only changes after that SHA still require final-head CI verification before merge. The owner has explicitly deferred cosmetic UI/UX refinement; it is **not a P2 functional merge blocker**. See `docs/PRICE_CATALOG_P2.md`. Preserve the legacy store API, immutable UUID/code, price validation, quick-price and CAS. Leave #83 open until merge; owner alone authorizes merging. P3 measurements (#76), P4+ pricing (#44)/Canvas (#71), and runnable iOS host (#42) stay separate. No CI benchmarks/cache/time comparisons for scoped price work.
- **Follow-up UI/UX:** restore the previously approved compact alignment (category/unit ~60/40 and price ~35% right with explanatory text to its left), refine category-management entry placement and dialog spacing/focus after P2 is merged. Do not mix these cosmetic refinements into PR #86 or claim they are part of #83 acceptance.
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
