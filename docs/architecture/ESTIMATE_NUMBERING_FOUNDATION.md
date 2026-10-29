## Update — Android numbering cutover (PR #27)

**Implemented in code, CI on new HEAD pending:** the normal Android `EstimateDao.insert` now creates a legacy UI/PDF projection **and** a fresh numbered immutable `estimate_series` + frozen revision v1/lines in one nested SQLDelight transaction. The database has an additive version-2-to-3 migration (`2.sqm`) linking `estimates.seriesUuid` to the numbered series. Existing rows remain readable with `null` document number; there is **no forced renumbering**, copying or dropping old records.

- The list displays a full number with `v1`; PDF displays stored number and creation date, and uses the v1 name snapshot even after the UI label is renamed. The filename includes the full number and version.
- Duplicating via the Android list calls the same `EstimateDao.insert` path, allocating a **new** series UUID and number and copying historical prices/line items without consulting the current price list.
- Android `update` accepts name-only changes for numbered estimates; changes to stored financial and measurement values fail explicitly. Soft-deleting an estimate also tombstones its series, preserving permanent number reservation and immutable history.
- A signed `system.rounding` line is permitted and tested; other frozen price and totals must remain nonnegative.
- Android instrumentation checks real SQLCipher create/duplicate/rename/soft delete/reopen and signed rounding; shared JVM tests check coupled atomic writes, rollback, version-2-to-3 migration, and old unnumbered rows.

**Remaining:** check the Android/Windows/iOS CI on the latest HEAD, manually test the numbered UI/PDF/backup on physical Android, add a settings UI for templates, implement read-only preview diff and user-controlled v2/v3 revision history, then extend PDF export to select historical revisions. No automatic repricing or WAL change.

---


## Update — SQLDelight persistence foundation (stage 2, PR #27)

**Implemented, CI verification pending:** added additive schema migration `1.sqm` and `EstimateHistory.sq`. New structures do not modify the existing `estimates` UI table. Existing Android screens, pricing calculations, PDF export and backup code still use the original table/format.

- `estimate_number_settings`: template/prefix/width + serialized next local sequence.
- `estimate_series`: immutable `seriesUuid` (PK), `documentNumber` (UNIQUE), project/optional measurement relationship, frozen policy fields and soft-delete tombstone.
- `estimate_revisions`: revision UUID, series, parent UUID, version, snapshot of input/details/precision/rule version/currency/rounding/total, `UNIQUE(seriesUuid, versionNumber)`.
- `estimate_revision_lines`: immutable frozen line titles/codes/UUID, unit, quantity, unit price and line totals.
- `ImmutableEstimateRepository.createSeries` allocates the sequence, retries only UUID/number collisions, inserts series/revision/lines in **one** SQLDelight transaction and rolls back on failure. `appendRevision` requires the latest known revision UUID, rejecting stale confirmations. `softDeleteSeries` retains the number reservation. Project soft-deletion tombstones new series too.
- Added JVM tests for v1/v2 integrity, unchanged number following settings changes, collision retry, rollback, deleted series and additive migration; Android instrumentation checks real SQLCipher persistence and encryption across a reopen.

**Limitations / remaining gates:** Not yet used by Android estimate creation or duplication. Existing estimate records are not backfilled. No immutable revision history UI, no preview/diff, no PDF-by-revision, and no default DB-side prevention of direct ad hoc UPDATE outside the repository contract. Concurrency/backup and current-user test remain required before production adoption. Current database file is retained: additive migration updates schema version rather than resetting existing data.

**Next:** inspect latest Android/Windows/iOS CI; only after it passes plan Android integration of series creation and PDF, including how old `estimates.detailsJson` maps into typed frozen line snapshots. Avoid dual-writing partial documents or silently changing PDF history.

---

# Estimate numbering — KMP domain contract (stage 1)

Status: **implemented as a pure shared domain contract**, not yet connected to SQLDelight/Android UI. Started after merged PR #23. Related work: [#25](https://github.com/aleks-nl5/andrey-ceilings-android/issues/25) (document numbers), [#24](https://github.com/aleks-nl5/andrey-ceilings-android/issues/24) (immutable revisions).

## Invariants

- A document **series** owns a permanent `seriesUuid` (canonical RFC 4122 v4 UUID), a human-readable **full** `documentNumber` and versions `v1/v2/…` with independent revision UUIDs. The number is assigned **once** on creation and stored; rendering a number with changed settings later must never silently renumber old documents.
- The canonical full number must include the **entire 36-character hyphenated UUID** (all 128 bits, not a truncated alias). Human-friendly `prefix`, `year`, and locally sequential `sequence` are decorative components, **not** an offline global-uniqueness guarantee.
- An independent offline device cannot guarantee mathematically zero collisions or globally consecutive numbering. Our existing `newRecordUuid()` provides randomized UUID v4; the DB must detect deliberately injected or accidental collisions and fail/retry only when creating an entirely new series. Future globally short *strict* sequence numbers need a server-issued namespace/range.
- Each series uses a stored fixed number across all revisions. Confirming a recalculation creates a new immutable revision; duplicating a series generates a **new** `seriesUuid` and number. Soft-deleted series must keep their number reservation.
- The domain API never accesses the clock, DB, global counter or platform services. Android/JVM/iOS share one implementation.

## Stage 1 API

`shared/src/commonMain/kotlin/com/andreyceilings/shared/estimate/EstimateNumbering.kt`:

- `EstimateNumberPolicy(template, prefix, sequenceWidth)`: defaults to `{prefix}-{year}-{sequence}-{uuid}`, `AC`, sequence width 5.
- Template placeholders: `{prefix}`, `{year}`, `{sequence}`, `{uuid}`; `{uuid}` appears **exactly once**, other placeholders at most once. Any unknown, unclosed or unsupported characters fail validation. Current safe prefix alphabet is ASCII letters/digits/`-`/`_`, at most 20 characters. Maximum number length 128.
- `EstimateNumberContext(seriesUuid, year, localSequence)`: injected by caller. Year is a four-digit integer, sequence positive, UUID full v4. The UUID is normalized to lowercase.
- `EstimateNumbering.validate(policy)` returns errors for settings UI. `render(policy, context)` fails on invalid settings/input. `preview(policy)` is deterministic and **illustrative only**, not a reserved number.

Example:
```text
AC-2026-00125-12345678-1234-4abc-8def-0123456789ab
```

Unit tests exercise valid and invalid templates, full UUID presence, same local sequence on 10,000 simulated offline devices, intentional UUID collision, preview and policy changes. Kotlin-only local smoke check of the pure source and eight test methods passed; GitHub KMP/Android/iOS CI is still authoritative.

## Pending stage 2: persisted immutable series/revisions

Before enabling numbering in the Android UI:

1. Design a proper `estimate_series` + `estimate_revisions` + revision line snapshot schema, referencing the current `estimates.detailsJson` and `uuid` contracts. Do not conflate local `estimates.id` with document numbers.
2. Persist `seriesUuid`, `documentNumber`, a **stored rendering policy snapshot**, revision UUID, monotonically increasing revision number and immutable line snapshots in a **single SQLDelight transaction**. Unique indexes must include `seriesUuid`, `documentNumber`, and `(seriesUuid, revisionNumber)`; add guarded collision retry and explicit conflict handling for import/sync. One successful first create reserves the number forever.
3. Store local `sequence` in a serialized transaction only as an optional readable counter. It may have gaps and be equal on different devices. Do not use `MAX(id)+1` as a global ID.
4. Existing database data is currently *test data* and the user has explicitly declined migration work at this phase; **do not reset or silently drop any future production database**. Keep backup/restore behavior and Android APK unchanged until the schema change has tests.
5. Next UI stage: preview and validation in settings, number + revision in lists/search/details and PDF exports. Export and reprint only the selected frozen revision, never recompute from live prices.

## Separate tracks

CMP price-list UI is the first shared-screen migration. SQLCipher WAL / backup hardening is issue #26, a separate Android PR; keep `DELETE` in place until the backup/restore concurrency design and instrumentation tests are ready.
