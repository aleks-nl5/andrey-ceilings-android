# P4C2 implementation — issue #116 / Draft PR #117

The feature branch now contains implementation, not only the original handoff. Owner acceptance is still pending. PDF and manual unit price editing remain outside this PR.

## Persistence and identity

`16.sqm` adds `estimate_revision_frozen` and `estimate_revision_receipts`; existing v1 tables and payloads are untouched. The v1 canonical codec omits the new optional identity field, preserving the old content proof. Follow-up payloads use document format 2 with series/revision/version/parent identity; the envelope format remains 1. Revision version is independent from codec format.

The shared writer validates the same coherent current measurement/catalog, role bindings, selection, confirmed overrides, FULL/PARTIAL acknowledgement, discount and exact cents/milli for both initial Save and append. Append claims the catalog writer lock before receipt/latest/status/source reads, checks ownership and exact latest/status CAS, preserves numbering policy and permanent document/public number from v1, and allocates neither a series nor a sequence. The new child, exact values, receipt and workflow reset to DRAFT commit together. Supported retries return the original revision before consulting mutable source/catalog. The legacy Double append API rejects exact individual series.

Unified queries select one latest row per series and an exact revision by UUID. Readers bind payload identity to the normalized row and receipt. Full import/reopen audits cover parent chains, duplicate operation namespaces, receipt commands, numeric storage classes and exact amounts. Android portable backup validation includes v2+; Desktop probes distinguish complete v16/v17 and reject partial schemas.

## Shared UI and lifecycle

The common saved document opens an explicit revision, exposes history, and labels workflow as the current series status rather than historical approval. A new revision starts a fresh memory-only presenter from the current source/catalog. Comparison shows saved/current rows, billable quantities, prices and totals. Old quantity, selection and discount decisions transfer only by explicit actions with stable identity/unit/role/current eligibility checks; quantity and discount require fresh ordinary confirmation. Selection carry validates the whole proposed set before applying any decision. Partial acknowledgement never transfers.

Save stays after the final summary and keeps the existing moving total behavior. Accepted saves freeze editing and refresh, and open the exact returned revision UUID. Revision presenters remain owned by the DB session across surface recreation; registered writes drain before DB close. Android and Desktop navigation guards include revision saves from all saved-document entry points. Back/Cancel does not write status, number or revision.

## Verification record

- Targeted shared estimate JVM tests: **68 tests, 0 failures**; SQLDelight migration verification passed during implementation.
- Full shared JVM suite: **413 tests, 0 failures**; SQLDelight migration verification passed. This full run preceded the final session/source hardening; the focused final rerun is recorded in PR #117.
- Android unit tests: **11 tests, 0 failures**. New Desktop comparison scene passed in light/dark themes and a narrow viewport. Its scrolling/wait assertions were then hardened; final rerun, full Desktop suite, APK and instrumentation compilation results are recorded in PR #117.
- New common proof covers current geometry/price changes, v1→v2→v3, unchanged v1 payload, numbering, CAS, receipt retry, late rollback, history and deleted source. Physical Android/Windows/iOS lifecycle tests invoke the shared v16→v17 proof. Portable snapshot tests include v2+.
- Added separate concurrent-connection append/status tests, fail-closed selective carry and import corruption cases, Android touch revision/Cancel/history test, and Desktop revision comparison scene in light/dark themes.
- Linux environment has no `/dev/kvm`; Android emulator instrumentation and native Windows/iOS execution require the existing CI runners. Compiling instrumentation is not a physical test pass.
- Same-final-SHA CI, independent review and owner physical APK acceptance are required and must be recorded as actual results. Keep Draft; owner alone marks Ready/merges/closes the issues.
