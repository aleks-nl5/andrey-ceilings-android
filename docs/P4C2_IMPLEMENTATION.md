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

- Full shared JVM suite during implementation: **413 tests, 0 failures**. Focused exact-estimate rerun: **71 tests, 0 failures**; SQLDelight migration verification passed.
- Local current Desktop suite: **44 tests, 0 failures**; Android unit suite: **11 tests, 0 failures**. APK assembly and instrumentation compilation passed. Native execution is separately verified by CI.
- Production feature SHA `90e0380c46a98c686a9f3e7a14aeebf71cb85ad5`: [Android #1208](https://github.com/aleks-nl5/andrey-ceilings-android/actions/runs/42676426481) and [Windows/iOS #958](https://github.com/aleks-nl5/andrey-ceilings-android/actions/runs/42676427147) **SUCCESS**, including encrypted platform proofs. The subsequent change only strengthens a Desktop gesture test; current final-SHA check results are recorded in PR #117.
- [Independent static review](https://github.com/aleks-nl5/andrey-ceilings-android/pull/117#pullrequestreview-5831258722) on `90e0380c`: **PASS**, no confirmed blocking P1/P2. This is not owner APK acceptance or permission to merge.
- Common proof covers current geometry/price changes, v1→v2→v3, unchanged v1 payload, numbering, CAS, receipt retry, late rollback, history and deleted source. Physical Android/Windows/iOS lifecycle tests invoke the shared v16→v17 proof. Portable snapshot tests include v2+.
- Additional proofs cover concurrent-connection append/status races, fail-closed selective carry, import corruption, Android touch revision/Cancel/history, and Desktop comparison scenes in light/dark themes. A paused history read proves the saved card is published together with ready actions; pinning a revision no longer restarts its own load.
- The existing Windows drag test once failed because a projected small adjustment did not cross the gesture threshold. Raising `touchSlop` to 64 px reproduced the identical unchanged-polygon assertion locally. The helper now crosses the actual threshold within the same gesture and releases at the original exact world target. All geometry, Undo/Redo, no-write-before-Save and atomic persistence assertions remain. All four details/editor/fullscreen/phone modes and the full local Desktop suite passed.
- Linux has no `/dev/kvm`; Android emulator instrumentation and native Windows/iOS execution use the existing CI runners. Compilation is not physical acceptance.
- Owner physical APK testing in light/dark themes remains pending. Keep Draft; owner alone marks Ready, merges and closes the issues. Workflow-policy issue #118 remains outside this PR.
