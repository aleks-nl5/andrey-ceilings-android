# Room → SQLDelight cutover gates (PR #22)


### 2026-10-29: Real-device first-launch JNI failure and regression coverage

An upgrade installed over an existing encrypted Room-v2 database crashed during
`CeilingsDatabase.get() → LegacyRoomDatabaseImporter.readSnapshot() →
SupportOpenHelperFactory.readableDatabase` with
`java.lang.UnsatisfiedLinkError: no implementation for SQLiteConnection.nativeOpen`.
The legacy importer opened Zetetic's `SupportOpenHelperFactory` **before**
`createEncryptedPrototypeDriver()` (which loaded `libsqlcipher`). The old
Room bootstrap had called `System.loadLibrary("sqlcipher")` but this initialization
was missing in the new SQLDelight-first startup path.

**Fix:** explicitly load `System.loadLibrary("sqlcipher")` in the legacy
importer *before* constructing/opening the SQLCipher helper. The legacy file
and Android Keystore passphrase are not deleted or replaced if initialization
fails. This is a startup-order bug, not evidence of lost records.

**Why original CI missed it:** the legacy migration instrumentation test set up
its old file with `System.loadLibrary("sqlcipher")` earlier in the same Android
test process, masking the missing initialization. A new **debug-only,
non-exported ContentProvider in a dedicated Android process** tests the
migration's first native call with no prior SQLCipher JNI load; the test still
checks the imported IDs and preservation of the original encrypted file.
The provider accepts only disposable test database filenames and is not
included in Release APKs.

**Verification result (2026-10-29):** CI on `4fa4632` passed Android (`40868367460`), Windows and iOS (`40868367549`). The user then confirmed on an actual Android device that the updated APK installed, launched successfully, imported the legacy database, and that automatic backups, database export, exported-file verification, and database import all work. Do not extrapolate this report to untested concurrency, crash recovery or all editing operations.

**Additional real-device acceptance report (2026-10-29):** The user confirms that **create and delete** work for clients, projects, measurements and estimates. For **price items**, create, edit and delete all work. Editing clients, projects, measurements and estimates has **not yet been implemented in the UI**, so it must not be marked as tested, broken or migrated functionality. These confirmations are from a physical Android device; no Windows/iOS business UI parity or device-to-device synchronization is implied.


## Current implementation — 2026-10-29

**Android runtime cutover implemented in PR #22, under CI verification.** The older implementation checklists below are retained for historical context and follow-up requirements. The live Android application now uses `:shared` SQLDelight via UI-compatible Kotlin data adapters; Room runtime and its KSP compiler were removed from `app/build.gradle.kts`. The Windows/JVM and iOS simulator tests use the **same common SQLDelight schema**, with platform SQLCipher drivers. No online cross-device synchronization is provided.

- SQLDelight encrypted live filename: `andrey_ceilings_sqldelight.db` (separate from legacy `andrey_ceilings_secure.db`).
- On first launch with only an existing Room-v2 file, `LegacyRoomDatabaseImporter` reads the old encrypted database through Zetetic's AndroidX SQLite support helper **without Room runtime**, atomically imports all five tables and their IDs into a separate encrypted SQLDelight file, verifies table counts/integrity, then publishes the new file. The original is retained for recovery.
- A migration failure stops database startup; there is no fallback to empty plaintext or silent deletion. If a legacy schema isn't v2, migration reports an error pending an explicit versioned adapter.
- UI methods and `Flow` observers now resolve to shared SQLDelight repositories. `BusinessChangeTracker` replaces Room InvalidationTracker for automatic change-triggered backups.
- `PortableBackupManager` snapshots/verifies SQLDelight files and converts encrypted legacy-v2 `.stpb` payloads before restore; the old `.stpb` password-protected envelope remains supported.
- Existing SQLCipher passphrase remains under Android Keystore. Desktop key management, iOS real-device tests, and cross-platform encrypted-file interchange are independent requirements.
- Unit tests and Android instrumented checks cover schema, wrong key, encrypted headers, JDBC lifecycles, ID-preserving import and rollback. Additional backup/invalidation integration coverage was added and needs a final green CI.
- Still required before declaring production rollout complete: physical Android APK smoke test; explicit manual restore/Worker and WAL failure testing; crash-safe migration verification on a non-test database; native dependency/license checks.

**Do not confuse shared repository logic with device-to-device synchronization.** SQLDelight supplies a unified local SQL API; exchanging changes between Android, Windows and iOS requires a separately designed sync layer.

---



**Status, 2026-10-29:** in progress; Android production still uses Room. This document is an implementation checklist, **not** evidence that Room is removed. The active PR branch is `architecture/cmp-kmp-migration-v2`; do not modify `main` or merge without approval.

## Confirmed CI issues and corrections

- Windows SQLCipher failed because SQLDelight `JdbcSqliteDriver` creates a new physical JDBC connection between standalone statements. Its one-time `PRAGMA foreign_keys=ON` was lost. A dedicated Windows JDBC driver now initializes every connection; subsequent Windows encrypted CRUD/cascade and transaction tests passed.
- Android build run [40850655184](https://github.com/aleks-nl5/andrey-ceilings-android/actions/runs/40850655184) failed before tests: emulator lacked KVM permissions, boot took almost nine minutes, and the Android `package` service was unavailable. The Android workflow now enables read/write access to `/dev/kvm` before starting the emulator. [40854881292](https://github.com/aleks-nl5/andrey-ceilings-android/actions/runs/40854881292) passed after that change.
- The new full Android business-schema test originally lived under `shared/src/androidInstrumentedTest`, whose execution is **not** established by the current workflow. A corresponding JUnit Android test now lives in `app/src/androidTest`, executed by the existing `:app:connectedDebugAndroidTest` task. Do not count it verified until that run finishes.

## Room schema / SQLDelight parity

The five Room entities in `app/src/main/java/com/andreyceilings/app/data/db/Entities.kt` match the SQLDelight tables in `shared/src/commonMain/sqldelight/com/andreyceilings/shared/db/BusinessSchema.sq`:

| Domain | Room table | SQLDelight table | Referential rule |
| --- | --- | --- | --- |
| Clients | `clients` | `clients` | No declared parent FK |
| Projects | `projects` | `projects` | `clientId` indexed but NOT an FK |
| Measurements | `room_measurements` | `room_measurements` | `projectId` indexed but NOT an FK |
| Prices | `price_items` | `price_items` | Unique (`category`, `code`) |
| Estimates | `estimates` | `estimates` | `projectId` references projects with ON DELETE CASCADE |

**Do not silently add cascading FKs to projects or measurements.** That would change existing Room behavior.

Room DAO compatibility work now includes:
- `clients`: case-insensitive name ordering and lookup, count, find, insert/update/delete, generated insert ID.
- `projects`: updatedAt descending, count, find, change status + timestamp, generated insert ID.
- `room_measurements`: global and project lists ordered by createdAt descending, count, generated insert ID.
- `price_items`: category/title ordering, count, and Room-style INSERT OR REPLACE with ID 0 treated as new.
- `estimates`: global/project lists ordered by createdAt descending, count, generated insert ID, project-delete cascade.
- Shared SQLDelight `Flow` list observables and a JVM notification test, replacing the API shape of Room's `Flow` observers.
- `INSERT ... RETURNING id` requires and now explicitly selects SQLDelight's SQLite **3.35 dialect**; do not replace with a separate `last_insert_rowid()` query because Windows uses short-lived JDBC connections.

**Note:** these contracts are only a foundation. Verify precise Room replacement semantics against instrumented tests and user-facing flows before switching the live application.

## Android production callers that still use Room

- `MainActivity`: Room singleton opening, `openHelper`, demo price seeding.
- `AppRoot`, `ObjectsScreen`, `MeasurementsScreen`, `EstimatesScreen`, `PriceListScreen`, `CalendarScreen`: Room DAO/entity types and `Flow` observables.
- `SettingsScreen`: Room-based counts/diagnostics, checkpoints, portable backup import/export, `Room.databaseBuilder` verification, and `Room.invalidationTracker` trigger for database-change backups.
- `PortableBackupManager`, `AutomaticBackupWorker`: Room-backed file checkpoint/snapshot and recovery.
- `CeilingsDatabase`, `Daos`, `Entities`, `DemoPriceList`: Room runtime/compiler-specific implementation.
- `app/build.gradle.kts`: `room-runtime`, `room-ktx`, KSP compiler and `room.schemaLocation` remain intentionally enabled until the replacement compiles and tests pass.

## Critical migration and backup contract

The encrypted live Room file is named `andrey_ceilings_secure.db` and currently has Room schema version **2**. The SQLDelight generated schema currently begins at version **1**. **Opening the existing live database using the prototype driver without a versioned conversion is NOT safe**; a SQLite schema table match alone does not establish migration compatibility.

Existing backups are encrypted `.stpb` envelopes containing the SQLCipher database bytes and database passphrase. Restore replaces the file/Keystore-wrapped passphrase only after integrity checks. This contract must remain usable; never substitute an empty DB, silently generate a new key, or introduce a plaintext fallback. In particular:
1. Design a deterministic Room-v2 → SQLDelight migration (copy-on-write into a new encrypted file or explicit SQLDelight schema-version adaptation) that preserves IDs, row counts, estimate foreign keys, and current SQLCipher key semantics.
2. Perform pre-migration encrypted backup, verify both old and new snapshots, and ensure crash/restart rollback. Test wrong key, truncated file, corruption and WAL sidecars.
3. Replace `PortableBackupManager` and Settings' Room-based verifier/checkpoints with SQLDelight/SQLCipher-aware implementations before changing the app's live database.
4. Replace Room's invalidation tracker with SQLDelight table listeners (and test automatic backup scheduling), not just UI Flow.
5. Make restoration of legacy Room-format `.stpb` files explicitly supported or clearly versioned and migrated, with a non-destructive failure path.
6. The developer-approved test-data reset is an optional, explicitly signposted **development-only** fallback. Never bake destructive reset into production.

## Next implementation stages

1. **Complete cross-platform API gates:** run JVM, Windows native SQLCipher, Android emulator (with KVM), and iOS simulator tests for generated IDs, reactive notifications, CRUD, encryption, FK cascade, reopened DB, wrong key. Confirm actual test execution, not just compilation.
2. **Introduce an app-independent data abstraction:** consume shared SQLDelight repositories + `Flow` from UI/feature layer. Separate UI/domain models from Room annotations. Do not show two unrelated writable databases in production.
3. **Move encrypted-file backup/restore and change-trigger scheduling:** add SQLDelight-compatible diagnostics, WAL checkpoint, atomic replacement and legacy import tests.
4. **Implement the Room-v2 file conversion or explicit safe test-data reset**, guarded by version detection, preserving key and backups as appropriate.
5. **Switch the Android entry point and feature screens** to SQLDelight with parity tests for every CRUD workflow, PDF estimate, demo prices and background Worker.
6. **Only after all references are gone:** remove Room, KSP room-compiler, schema args and Room migration classes; keep Android SQLCipher and existing Go/AAR intact. Check APK size and startup performance.
7. Continue iOS/Windows UI and security hardening; test backup portability separately. Cross-platform SQLCipher file compatibility is NOT yet established.

## Non-negotiable gates

- No Room deletion while any screen, worker, diagnostic, or restore path depends on it.
- No replacing `main`, production database file, or live key during migration POC testing.
- Keep Kotlin 2.4.21, Gradle 9.7.0, SQLDelight 2.4.1, current SQLCipher libraries; do not downgrade to pass a test.
- Do not claim final migration success until CI on the **same final commit** passes Android, Windows and iOS.
