# P4B1 implementation, schema v15 and review handoff

Issue #108, existing Draft PR #109, branch `feat/p4b1-measurement-live-calculator`, merged P4A baseline #107. Owner acceptance is pending. The P4A document describes its historical v14 boundary; this document records the P4B1 integration.

## Persisted role and write boundary

`MeasurementElement.businessRole: BusinessRole?` is a property of a selection in one measurement. `CANVAS`, `PRIMARY_PROFILE`, `ADDITIONAL_WORK` are explicit choices. Wishes remain null. New repository create/link calls require the typed role; editing a legacy record may preserve null while its other fields are corrected. There is no heuristic or global item role, and no default additional-work role.

`14.sqm` upgrades v14→v15 by ADD COLUMN and two partial UNIQUE indexes. No table rebuild, data backfill, key/WAL changes, estimate rewrite or catalog reseeding. The nullable CHECK permits only catalog selections; canvas requires ceiling_area/м² and primary profile requires ceiling_perimeter/м.п. Existing quantity CHECKs continue to apply. Each index covers active rows only and reserves one main slot per measurement across all SQLite writers. Soft deletion releases the slot and retains the tombstone's role. Frozen `14.db` retains distributed v14 data for SQLDelight comparison.

`MeasurementElementRepository` uses its existing write-lock-first transaction, UUID retry payload comparison, ownership and updatedAt CAS. It checks role compatibility/duplicates inside that transaction and returns typed failures; SQL CHECK/UNIQUE constraints remain the final concurrent-write guard. A changed role also verifies the active catalog's selection metadata. Role edits never convert quantity, change catalog identity or rewrite source geometry. Placement/version/receipt behavior is unchanged. Historical fixture adapters are test-only: they project a null role and execute the old role-less statements against frozen physical schemas, without altering their DDL or the production migration under test.

## Coherent reads and session lifetime

The reader obtains role with the element, geometry, overlays, walls/receipts and active catalog in one DB snapshot. `PricingReadResult.Loaded.buildPreview()` builds source-signature-bound role bindings exclusively from those persisted elements. The pure P4A engine and its explicit-role fixture API are unchanged; null roles remain visible ROLE_UNDETERMINED blockers.

`selectPricingInvalidations` lists all source/catalog table dependencies. Its Flow supplies invalidations only; no observed values enter pricing. Every event first emits Loading, then reads a fresh transaction and emits Loaded or Unavailable. No prior success is substituted after read failure. Only the visible pricing subscriber runs this observer; route/session changes discard its state.

The store registers scheduled reads in its existing operation drain. Reader admission occurs inside an outer SQLDelight transaction, so Android's physical DB gate is acquired before reader admission. Android close, already under its exclusive restore gate, closes admission and awaits `closeReads()`; queued old-session operations cannot enter the closed wrapped driver. Desktop closes admission, drains reader transactions and scheduled store operations before JDBC close. This avoids both connection reopen after close and a restore-gate/reader-drain lock inversion.

## CMP user flow

Details → «Елементи стелі» → «Додати елемент» → «З прайсу»: choose the item, explicitly choose a role, quantity/approval, Save. Wish → «Прив'язати до прайсу» additionally requires a compatible quantity or explicit replacement and a role. Existing/legacy catalog element → «Редагувати» assigns or changes the role in the same dialog. Incompatible choices show the corrective reason and disable Save; no implicit unit/mode conversion occurs.

The information card appears in normal measurement Details before the elements and as a compact fullscreen summary. It displays confirmed and separate advisory potential amounts in integer cents, incomplete totals, unresolved row reasons and concrete future-readiness blockers. Role-less entries identify the element and its correction. A valid zero-priced base pair is allowed. Unresolved rows never receive synthetic zero prices; placements never create another financial line.

This slice deliberately displays **saved** data, explicitly labelled «За збереженим заміром». During element editing, dirty geometry/drag, save/check/uncertain/conflict/error it hides amounts and requests Save/discard. Loading/error states reserve card space and never display a previous amount. The card also checks the displayed measurement/element versions against its read basis and suppresses an out-of-date snapshot while the source observer catches up; successful element Save starts a fresh pricing read before closing the editor. No quote Save affordance, series, numbering or PDF is added. P4B2/P4C remain separate.

## Verification evidence

Local Linux checks below passed on the implementation source. Same-final-SHA remote CI evidence is maintained in PR #109; platform tests are not considered passed merely because their code exists.

- SQLDelight generation + migration verification (including 14.db→15): passed.
- Common/JVM: **366 passed**, Desktop compile/scenes: **34 passed**, Android unit: **10 passed**. Final local run including display-basis guards: `:shared:jvmTest :shared:verifySqlDelightMigration :desktopApp:test :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin` — BUILD SUCCESSFUL. JDK 21, Gradle 9.7.0, Android SDK 37.0/build-tools 37.0.0. Debug APK and instrumentation Kotlin compiled. Physical Android was not run locally (ADB has no device/emulator); Windows/iOS require their CI hosts.
- `BusinessRoleProof` covers explicit persisted pair, separate potential, Wish link, role/CAS/UUID retries, compatibility, SQL duplicate guards, geometry/price changes, soft delete and unchanged estimate counts.
- `BusinessRoleMigrationProof`: populated physical v14, interruption rollback, all original columns/history preserved, legacy null blockers, manual classification and close/reopen. Wired into JVM, Windows SQLCipher, Android SQLCipher and iOS Simulator SQLCipher suites.
- JVM includes two competing writer connections and coherent role+catalog WAL read; reactive model and close checks. CMP scenes exercise explicit main-role choices in both themes and hide stale amounts during draft/loading/error. Existing POINT/LINE/AREA tests remain mandatory.
- Android physical portable envelope/restore/reopen proof validates typed roles and amounts. JVM/Windows and iOS additionally export a coherent physical database snapshot and verify unchanged roles/preview on reopening the copy; native host backup UI remains outside #108 / tracked in #42. Owner device/process/UI smoke remains separate.

## Owner acceptance still required

Keep #109 Draft. Run same-final-SHA Android APK/instrumentation and Windows/iOS CI, review independently, then owner smoke normal/fullscreen, light/dark, rotation, scroll/IME, new canvas/profile and discussing work, price/contour change, restart and portable restore. Do not mark owner smoke complete, merge, or close #108/#44 on the owner's behalf.
