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
- [ ] Establish whether SQLCipher `VACUUM INTO` truly yields a working encrypted consistent snapshot on installed library/Android versions. If not, use an explicitly verified supported SQLCipher backup mechanism. Never ship a plaintext backup.
- [ ] Replace direct live `.db` copy in **all** portable/automatic/manual export paths with the verified snapshot operation, including contention/failure cases.
- [ ] Restore through staging: close all DB handles; validate file and key; preserve last known-good state; ensure atomic replacement or rollback; never remove WAL/SHM from a live or reopened connection.
- [ ] Verify wrong password, disk-full, interruption, active reader/writer and checkpoint cases; retain database on failure.
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
