## Owner-approved area rounding — 17.11.2026 (current)

The owner reproduced INVALID_SOURCE with two rectangle sides entered to hundredths (4.12 × 3.34 → 13.7608 m²). The coherent geometry reader succeeds; the actual blocker was CANVAS QUANTITY_PRECISION. The owner explicitly chose: round captured area HALF_UP to 0.001 m² first, then calculate row cents. For example, at 100 UAH/m²: 13.7608 → 13.761 → 1376.10 UAH. This rule applies in both creation contexts and to current-source revisions. Original dimensions/drawing/area remain unchanged; manual quantities and perimeter retain their existing precision contract.

Affected new frozen documents use format 3 with an explicit versioned area quantity policy; existing format 1/2 payload bytes and content proofs remain unchanged. Schema v19 adds only a capability marker migration (18.sqm, no row changes), so older distributed clients reject unsupported documents rather than opening them as v18. Preserve per-revision status, explicit chosen pointer, operation receipts, exact Long money, backup and SQLCipher. UI shows measured area separately from rounded billable source quantity. Native v18→v19 lifecycle and encrypted snapshot proofs include legacy and format-3 documents.

Deletion and workflow-policy issue #118 remain deferred. Keep PR #117 Draft for independent review and owner APK re-test. Verification results are recorded in the PR; do not infer native success from compilation.

Windows CI also exposed JDBC core 0.2.13's `getDouble` implementation: it parses SQLite `columnText`, whose default REAL conversion drops low bits. The reader now obtains area/perimeter using explicit round-trip decimal precision before the existing exact equality checks. It does not add a geometry tolerance or disable walls/source validation. A JVM cursor adapter reproduces the native conversion and asserts a genuine scalar change still fails closed. SQLite storage types must be numeric before formatting; malformed TEXT with a numeric prefix is rejected instead of being coerced by printf. The physical Windows all-shapes creation proof remains enabled.

The Desktop scene harness now serializes UI continuation/render work, matching real hosts. ImageComposeScene defaults to Unconfined, which can resume a database completion on a worker concurrently with rendering and corrupt the lazy layout. The queued render-thread dispatcher keeps all comparison/selection/area assertions enabled.

Verification on `fdcb840`: full shared JVM **424 tests, 0 failures**, full Desktop **47 tests, 0 failures**, Android unit **11 tests, 0 failures**; APK assembly, instrumentation compilation and SQLDelight migration verification passed. The subsequent numeric-storage guard passed all **3 MeasurementCreationPricingTest** cases, instrumentation compilation and migration verification. Final native CI results are tracked in PR #117; compilation is not native acceptance.

At `f3cad04`, full local shared JVM **424/424**, Android unit **11/11** and APK assembly passed. Native [Android #1214](https://github.com/aleks-nl5/andrey-ceilings-android/actions/runs/44247664613) completed **SUCCESS**, including emulator SQLCipher/UI and signed release startup/recovery. The iOS job in [#964](https://github.com/aleks-nl5/andrey-ceilings-android/actions/runs/44247664575) completed **SUCCESS**. Windows failed the existing `AreaFeatureSceneTest`: the finish action remained disabled after synthetic corner taps. Its follow-up serializes scene continuations on the render thread and separates press/release by a frame, with explicit first/second corner assertions and viewport checks. The targeted both-theme case and full **47/47 Desktop** suite pass locally. Production geometry/money code and existing acceptance assertions are unchanged by this follow-up. New same-SHA native CI remains required.

---

# Owner APK corrections — 17.11.2026

The owner rejected APK acceptance and superseded the old series-status/latest-only contract. Two findings remain in scope; deletion is explicitly deferred.

- Schema v18 adds revision-specific workflow metadata and a separate CAS-protected chosen revision pointer. New revisions start DRAFT at version 1; prior revision statuses and explicit selection are untouched. Existing v16/v17 series status migrates only to the latest revision; earlier unknown statuses remain NULL and are shown as not recorded. No fifth selectable status and no financial payload rewrite.
- Shared lists expose every alternative, grouped under the permanent number, with independent status/amount and an explicit chosen marker. Exact historical cards allow status changes. Choosing any version requires confirmation and selection CAS; append never chooses a version implicitly.
- Schema/import/reopen checks cover both new tables, metadata storage classes, exact revision coverage and same-series frozen selection ownership. Native Android/Windows/iOS migration proofs include v17→v18 rollback/reopen. Portable backup proof includes statuses and selection.
- The fractional-area INVALID_SOURCE cause is now reproduced and corrected under the owner-approved rounding rule above; the former route-only hypothesis is superseded.
- Verification checkpoint: full shared JVM suite **417 tests, 0 failures** before subsequent test additions. Current focused JVM regression run: **15 tests, 0 failures**; final rollback/migration proof rerun: **3 tests, 0 failures**. Full Desktop suite: **45 tests, 0 failures**. Android unit suite: **11 tests, 0 failures**; instrumentation compilation, APK assembly and SQLDelight migration verification passed. Native execution results belong in PR #117. Previous final SHA 4e5c4a7 completed Android #1209 and Windows/iOS #959 successfully; those results predate these corrections.
- A real CMP stale-choice regression changes the pointer concurrently while confirmation is open. The stale command preserves the winning pointer, reloads current metadata and keeps a visible conflict message; all **4 FrozenRevisionSceneTest cases passed** after this UI follow-up.
- Independent review and owner APK re-test are required for the corrected implementation. PR remains Draft.

The record below describes the previous implementation and its historical evidence; the owner corrections above supersede its series-status/latest-only rules.

---

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
