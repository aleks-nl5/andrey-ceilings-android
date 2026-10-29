# Combined PR: SQLCipher WAL hardening + immutable estimate revisions v2/v3

Status: **DRAFT / incremental, not ready to merge**. Based on `main` after merged PR #28 (`07d60f5`). Tracks [#24](https://github.com/aleks-nl5/andrey-ceilings-android/issues/24) and [#26](https://github.com/aleks-nl5/andrey-ceilings-android/issues/26).

## Why one PR?

Both operations must preserve a consistent, immutable commercial history while the encrypted database is active. The shared storage tests and backup/restore acceptance gate should cover v1, v2, v3 together, including WAL, rather than allowing an untested combination to reach `main`.

This does **not** mean enabling WAL before ensuring a consistent encrypted snapshot. Implementation should be split into independently testable commits inside this branch.

## Ground truth

- Android SQLDelight / SQLCipher currently reports `journal_mode=delete` on the user's device.
- `estimate_series`, `estimate_revisions`, `estimate_revision_lines`, transactional v1 and `appendRevision()` already exist from PR #27.
- PR #28 displays only human-readable estimate numbers; full document and series UUIDs stay private.
- Existing portable backup uses `wal_checkpoint(FULL)` followed by direct file copy. **This is not a safe WAL snapshot under concurrent writers**.
- Existing restore writes the live DB file, and currently deletes WAL/SHM files around the replacement (including after reopening). This must be redesigned before WAL rollout.

## Implementation and review gates

### 1. Preflight and shared KMP preview — in progress

- [x] Separate feature branch based on merged PR #28.
- [x] Pure KMP price comparison, with no DB mutation, based only on immutable snapshot quantities and exact stable code/UUID.
- [x] Missing, ambiguous, invalid, unit-changed and historical adjustment lines block confirmation (no price fallback).
- [x] Cross-platform `commonTest` coverage for change/no-change/renaming/UUID mismatch/invalid data.
- [x] Read source revision and current price rows from the canonical KMP SQLDelight repository, preserving stable identifiers; JVM regression test confirms v1 is untouched.
- [x] Add Android read-only preview dialog from the estimate card; cancellation/closing makes no changes.
- [ ] Build and verify a source and price-list revision token for safe confirmation.
- [ ] Recalculate geometry/options from original measurement snapshot using the agreed KMP calculator, rounding policy and measurement precision.
- [ ] Preserve consistent `detailsJson`, line rows, quantities and totals in candidate snapshots.

### 2. Android encrypted WAL / backup correctness — blocked on passing proof

- [x] Isolated Android SQLCipher probe for `PRAGMA journal_mode=WAL`, reopen, and `VACUUM INTO` snapshot **with encryption and schema validation**; does not modify live journal mode.
- [x] CI finding (Android run `40937006127`): after reopening through the current `SupportOpenHelperFactory`/SQLDelight driver, `journal_mode` returned `delete` even though the prior connection used WAL. The test now re-enables and verifies WAL explicitly on every reopened connection; **future production driver must enforce this at its own open boundary**. Initial encrypted `VACUUM INTO` verification passed before the failing reopen assertion.
- [x] Emulator SQLCipher proved `VACUUM INTO` creates an encrypted, valid snapshot (including WAL). New shared backup helper rejects plaintext and verifies SQLCipher integrity. Full concurrency/restore stress tests still required.
- [x] Replace direct `.db` copy in portable/automatic `.stpb` and manual SQLCipher export with one verified `VACUUM INTO` snapshot helper. [ ] Add contention, disk-full and failure-injection tests.
- [x] Stage/verify imported SQLCipher image, retain a verified encrypted rollback snapshot and a Keystore-wrapped original-key marker; recover interrupted import before any production DB open. Offline WAL/SHM cleanup only after closing driver; never after reopening. **Runtime, crash and contention instrumentation tests are still required before WAL rollout**.
- [x] Android instrumentation regression for valid restore, corrupt imported image (original untouched), simulated interrupted replacement on cold reopen, and encrypted rollback from an active WAL with an uncheckpointed committed row. Tests additionally check two successive WAL snapshots preserve different committed versions. **New CI must confirm.**
- [x] Add deterministic restore fault injection after durable marker, after offline live-file removal, after incoming install and after key switch; each must restore original encrypted data and Keystore key. Add negative snapshot destination test. **Android CI confirmed in run 40948802226.**
- [x] Add isolated concurrent SQLCipher/WAL writer vs encrypted snapshot stress regression (60 commits, six verified point-in-time images, monotonic committed prefixes, final FULL checkpoint). **Android CI confirmed in run 40951062400.**
- [x] Add wrong `.stpb` password regression (no live DB/key mutation). Android run `40952735193` revealed a **real concurrent writer lock** under two independent `SupportOpenHelperFactory` / `AndroidSqliteDriver` handles, even after both returned `journal_mode=wal`; do **not** claim independent-driver WAL concurrency works. The revised isolated test bounds busy wait, accepts only explicit `SQLiteDatabaseLockedException` during overlap, releases the reader, retries the write, and verifies no lost rows, encrypted backup and reopen. The following run `40954437987` failed at `SqlCipherSeparateConnectionsTest.kt:54`, specifically `writer.execute("PRAGMA busy_timeout=250")`: this result-returning PRAGMA cannot be routed through SQLCipher's executeUpdateDelete path. The fix uses the SQLDelight query path for `PRAGMA busy_timeout`; it also replaces raw `BEGIN`/`ROLLBACK` with `database.transaction { ... }` to use the driver-supported transaction API. The earlier independent-driver `SQLITE_BUSY` finding remains a separate WAL rollout blocker. **Await the next Android CI.**
- [x] Identified underlying Android driver semantics (SQLDelight 2.4.1): `database.transaction {}` begins a non-exclusive **IMMEDIATE** write transaction, even for a read-only body; the observed SQLITE_BUSY against another writer is expected. SQLCipher's one-argument `SupportOpenHelperFactory` leaves WAL connection management disabled even if `PRAGMA journal_mode=wal` is manually set. Added explicit WAL-open-helper opt-in **only** for disposable instrumentation databases, plus a second independent reader/writer test without an IMMEDIATE transaction, WAL reopen and integrity assertions. **Await Android CI; production remains DELETE.**
- [x] Confirmed independent WAL reader/writer and reopen in Android emulator run `40958362029` (Windows/iOS CI `40958362103` also passed). Separate connection contention from SQLDelight `BEGIN IMMEDIATE` is expected; it is not a reader-only transaction.
- [x] Add instrumentation of cold-start fail-closed recovery when a pending restore marker has a **missing or corrupted** rollback image: assert live DB bytes unchanged, reintroduce a verified encrypted rollback, recover original key/rows, and reopen safely. **Await Android CI.**
- [x] Add Android internal-storage preflight during application initialization: warn without blocking reads when free storage falls below 128 MiB or the estimated backup headroom; do not change the database journal mode. Add an independent operation-time `StatFs` guard before portable backup/snapshot creation, import validation and staged restore, accounting for simultaneous SQLCipher images and leaving the existing database untouched on insufficient space. **Pending CI.**
- [ ] Simulate real ENOSPC (space can change after preflight), forced process termination and live restore write-quiescence before production WAL. SAF/external destination space cannot be guaranteed by internal-storage checks; stream write failures remain independently handled.
- [ ] Only **after** these pass: enable verified WAL mode in the sole production driver opening path, with explicit return value check and no plaintext fallback.
- [ ] Verify checkpoint, `SQLITE_BUSY`, listeners/Flow, Android CRUD and backup across reopen. Do **not** change Windows/iOS journal mode without platform tests.

### 3. Immutable versions and Android UI

- [ ] `preview` read-only, with per-line Before / After / Delta and blockers. Cancel must not write anything.
- [ ] Explicit confirm: atomically validate source revision UUID plus price-list token; append a new immutable v2/v3, never mutate v1; reject concurrent/stale previews.
- [ ] Version timeline including immutable dates and totals, with specific-version view and PDF generated from that **version's saved snapshot**.
- [ ] Preserve same public document number across versions, with version suffix v1/v2/v3; keep private UUID off printed documents.
- [ ] Duplicate creates a new series/number, not a copy of history. Deletion retains reservations and revisions.
- [ ] Consistent backup/restore and reopen with v1/v2/v3; old test DB migration is not a requirement. No automatic recalculation, sync, Room or Go.

## Merge criteria

- [ ] Android Debug/Release/APK + Android SQLCipher instrumentation CI green.
- [ ] Shared KMP and Windows/JVM CI green.
- [ ] iOS Simulator framework and test CI green.
- [ ] Manual APK: create v1, change/rename/delete a price, preview, cancel, confirm v2/v3, export each PDF, backup/restore, reopen and check WAL diagnostics.
- [ ] No regression in current clients, projects, measurements, price list, estimate numbering and existing PDFs.
- [ ] **Owner explicitly approves the final PR for merge.** Never merge this feature branch automatically.

## Non-goals

Network synchronization, bulk or automatic recalculation, migration of disposable historical test DBs, new Windows/iOS UI and printing full UUIDs.
