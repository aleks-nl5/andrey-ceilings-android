# P4C2 — Codex implementation handoff: versions from current measurement

**Accepted direction 09.11.2026** — [Issue #116](https://github.com/aleks-nl5/andrey-ceilings-android/issues/116), umbrella [#44](https://github.com/aleks-nl5/andrey-ceilings-android/issues/44). Base: main after merged PR #115, commit d97d096944e946335ed3f603a06c644bb4e9aac8, SQLDelight/SQLCipher v16. Codex implements **only** in branch feat/p4c2-measurement-sourced-revisions and its linked Draft PR. This is a documentation handoff, **not implemented code**. Old roadmap mentions of P4C2 as PDF are superseded: version history = P4C2, individual PDF = P4C3, selected-room consolidated PDF = P4C4.

## 1. Non-negotiable product rule

A new v2/v3 is calculated from the **current saved measurement and current active catalog** (one coherent source, checked in the Save transaction), never from historical v1 rows/price/geometry. Previous versions provide read-only history and comparison, **not** an implicit copy into the new draft. The user may selectively carry supported manual selection/quantity/discount settings only via an explicit, reviewed action; no silent transfer or fabricated matching. Updated measurements/prices never auto-create a revision. New revision requires deliberate Save.

An estimate has ONE permanent series UUID, canonical internal document number and settings-derived public number/suffix from initial v1. Follow-up revisions have distinct revision UUID, version label vN and parent pointer. **Append consumes zero new numbers and creates zero new series.** All earlier exact frozen revisions remain unchanged, readable without mutable source/catalog.

## 2. Code to reuse and pitfalls actually observed on main

- **ImmutableEstimateRepository.kt**: saveIndividual delegates to IndividualEstimateWriter. Historical appendRevision(ImmutableEstimateDraft) persists Double legacy quantities/REAL money, not authoritative cents/milli. **Never use it as the v2 financial writer.** Existing estimate_revisions already has UNIQUE(seriesUuid,versionNumber) and parentRevisionUuid; preserve legacy usage.
- **IndividualEstimateWriter.kt**: writer claims price_catalog_state before source/catalog/settings reads, validates LivePricingReader.readInTransaction, LivePricingEngine, IndividualEstimatePricing, QuoteDiscount, exact amounts and partial acknowledgement, then freezes complete source, rows, walls, receipts in one transaction. Extract reusable validation/frozen-builder so v2 does not fork or simplify money/geometric rules. Avoid nested SQL reader/writer leases and shutdown deadlocks.
- **FrozenIndividualEstimate.kt**: codec/contentProof validation, canonical source signatures and frozen Long money; distinguish codec formatVersion=1 from DB revision versionNumber. Preserve decoding existing v1; bind new payload to revision identity (series, revisionUuid, version, parent) so swapped but otherwise valid payload is rejected. Do not claim non-cryptographic contentProof authenticates against an adversary.
- **FrozenEstimate.sq**: estimate_v1_frozen and estimate_save_receipts are intentionally v1 only. Existing receipt has UNIQUE(seriesUuid), so new v2+ operations **must not** be inserted into that same receipt table. Queries selectFrozenV1* filter v1. Add multi-version typed read/write without duplicated canonical v1 copies or destructive conversion.
- **SavedIndividualQuoteScreen.kt** hardcodes v1 labels and reads one frozen v1 per series. **MeasurementElementsStore.kt** provides savedQuotes/savedQuote/setQuoteStatus, submitQuote and important registered in-flight Save/drain logic. **TransientQuotePresenter.kt** and MeasurementQuoteScreen.kt already own memory-only selection/discount/title/ack, guarded one-shot Save and the sticky-to-bottom total animation. Extend these, don't create an Android-only estimate editor.
- Strict schema admission: FrozenEstimateSchema.kt, DesktopPriceCatalogMigration.kt; Android PortableBackupManager and shared/native SQLCipher proofs must understand new structure/migrations and reject corrupt or partial data.
- One saved series appears through measurement Details, project card and global Estimates route. Preserve a shared navigation identity; no three copies.

## 3. UX contract

From the read-only saved quote (all three routes), **«Створити нову версію»** opens transient common CMP preview bound to existing series + expected latest revision/status version. If its current measurement/project cannot be read or belongs elsewhere, explain why creation is disabled; frozen version history remains viewable.

Load **fresh** measurement + catalog, default editable title to current measurement name. Show current public number and “Нова версія vN”; user can edit chosen work/quantity/overall discount, acknowledge the complete current PARTIAL omission set, and inspect a clearly separated **old saved vs new proposed** comparison (rows, quantities, totals and difference). Reuse mandatory CANVAS/PRIMARY_PROFILE and FULL/PARTIAL gates. Prices and geometry come from current source only.

Optional **selective carry**: old discount, old quantity overrides, old explicit inclusion/exclusion only after a separate user choice. Match stable measurement element UUID, catalog UUID, unit, role, current eligibility and valid source quantity; missing/mismatched/ambiguous rows must not be carried or guessed by title/code. Each imported manual quantity needs fresh confirmation. Never reuse old cents as today's price; manual price override/conflict solver remains [#35](https://github.com/aleks-nl5/andrey-ceilings-android/issues/35).

Bottom action **«Зберегти нову версію»** is after the real final summary (preserve animated sticky↔bottom handoff). During accepted Save lock all mutation, navigation, close/Back and asynchronous refresh as in P4C1. Only committed success opens **exact newly saved revision**. Back/Cancel, view comparison, temporary preview, changed source or failed Save do not write revision/number/status. Existing session lifecycle from P4B2 applies.

One row per series in global/project/measurement lists; row shows latest revision's title, total, vN and latest workflow status. Version history opens **exact revision UUID** with frozen drawing, rows, omissions, price and total, independent of current price/measurement; old version remains selected even after latest advances. Never sum multiple revisions as independent estimates or show legacy/test records as active proposals.

## 4. Atomic P4C2 append command

Design typed AppendIndividualRevisionRequest or equivalent with unique operation UUID, seriesUuid, projectId and measurementUuid, expectedLatestRevisionUuid and version, expectedStatusVersion, session/composition ID, title, selection, overrides, discount, exact expected amounts, source/catalog fingerprints and current partial ack/omissions.

Within ONE host-registered SQLDelight/SQLCipher writer transaction, after claiming the same price-catalog write lock:
1. Lookup operation receipt first. Identical command → return original (seriesUuid, revisionUuid, version), even if price/source subsequently changed. Same token/different command → hard conflict; no new revision.
2. Validate series not soft-deleted, project+measurement ownership, latest **exact-frozen** revision and version match the user's expected revision, current status CAS matches expected, source available. Concurrent append or status change must fail closed.
3. Coherently reload current measurement, geometry, walls, receipts, placements and active catalog via LivePricingReader.readInTransaction; re-run P4C1 selection, role, override, catalog unit, discount, omission and exact fingerprints in the writer transaction. Do not authorize save from an earlier read or historical frozen calculation.
4. Check version/number/long-money overflow. Generate new revision UUID, insert immutable estimate_revisions child + exact frozen payload and authoritative Long cents/milli + a unique new revision operation receipt. Preserve whole existing series identity/number. **No sequence allocation, no numberPolicy read to render new number, no update to old frozen rows.**
5. On successful vN creation, series-level current workflow resets atomically to **DRAFT** with version CAS increment: a newly revised offer must not inherit an old SENT/APPROVED label. Previously frozen money/drawing never change. An optional metadata row may record the preceding workflow status **at supersession**, but do NOT invent a full past status timeline or mislabel series-level status as every historical revision's status.
6. Any failure must roll back new revision, exact-frozen row, operation receipt, workflow update; count and previous versions unchanged. Error shows stale/invalid/ack required/DB unavailable/operation conflict with actionable recovery.

**Minimal schema recommendation:** additive v16→v17 (16.sqm), keep existing estimate_v1_frozen + v1 receipt untouched, add follow-up exact-frozen table keyed by revisionUuid with typed integer money and FK to estimate_revisions; add follow-up operation receipt table keyed by operationUuid and UNIQUE revisionUuid (NOT UNIQUE seriesUuid). Validate version >1 and its correct series/parent in transactional writer and reader. Provide unified exact v1/vN retrieval and latest selection. Update SQLDelight schema expectations, native migrations, WAL, backup/restore, corruption checks and import validation. Alternative design acceptable only if it preserves identical correctness and proves lossless v1 compatibility.

## 5. Required test matrix

- v1→v2→v3 on one series; identical permanent printable+canonical number, same counter, distinct revision IDs, strict sequential version/parent chain, exact money, frozen complete source.
- Modify saved measurement dimensions, wall/placed feature, selected line quantity and catalog unit price between revisions; new live version reflects changes, earlier frozen payloads byte-identical. Current-price edits or deleted source after Save do not modify historical version reads.
- FULL/PARTIAL with acknowledged omissions, selected potential work, discount, manual quantity override, valid zero prices, stale signatures, catalog/role/unit mismatch, invalid source, missing required main roles and overflow.
- Explicit-only selective carry, stable UUID matching and confirm; no auto import of old discounts/quantities/prices. Comparison old vs current needs no old catalog.
- Same operation retry, double tap, identical receipt under changed catalog, different command same token, two concurrent append calls, append/status race, interruption/late SQL error (rollback all), session close/restore, version exhaustion, corrupt parent/receipt/normalized amount/payload fails closed.
- Latest lists show exactly one row per series, vN totals not sum of versions, legacy/test archives excluded. Historical exact revision UUID view not rewritten on updates.
- Physical v16→v17 schema upgrade and fresh DB, interrupted migration rollback, constraint/index verification, encrypted Android/Windows/iOS reopen and portable backup/restore. No silent DB reset.
- Android touch integration create v2/compare/save/older v1/Back; Windows CMP scene layout/accessibility/Back/sticky-summary; iOS shared/native physical tests. Same-final-SHA Android and Windows+iOS CI, independent code review, owner APK check.

Commands already used in repository: :shared:jvmTest, :shared:verifySqlDelightMigration, :desktopApp:test, :app:testDebugUnitTest, :app:assembleDebug, :app:compileDebugAndroidTestKotlin. Add platform-native SQLCipher proofs to CI. Record actual results, not predictions.

## 6. Out of scope and delivery

NO PDF/reprint (P4C3), NO selected-room consolidated PDF (P4C4), NO arbitrary unit-price editor or catalogue conflict resolution (#35), NO issuer/company profile (#114), NO solver (#70), NO major Canvas UX redesign (#95), NO sync or autosave, NO migrating archived test estimates into current finances. Do not rewrite WAL/SQLCipher security foundations or modify v1 in place.

One feature branch + one Draft PR, Codex performs implementation and tests only there. Do not mark Draft ready, merge or close #116/#44 before independent review, same-final-SHA CI and physical APK acceptance by owner. Do not confuse this documentation-only handoff with completed functionality.
