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
