# P4A — Codex implementation handoff

**Status:** implementation in existing feature branch `feat/p4a-kmp-live-pricing` / Draft PR #107; see [implementation notes](P4A_IMPLEMENTATION.md). CI/review/owner acceptance remain separate gates. One Draft PR for the entire P4A phase, linked to [issue #104](https://github.com/aleks-nl5/andrey-ceilings-android/issues/104) and parent [#44](https://github.com/aleks-nl5/andrey-ceilings-android/issues/44). This file is an entry point; the complete, precedence-setting contract is [LIVE_ESTIMATE_P4A.md](LIVE_ESTIMATE_P4A.md) (merged to `main` via PR #105), plus `AGENTS.md` and `docs/PROJECT_ROADMAP.md`.

## Goal

Implement a **deterministic read-only KMP live-pricing engine** in shared/commonMain using a coherent validated measurement snapshot and current active catalog, with precise source provenance, separately priced confirmed and potential work, typed missing/invalid conditions, and read-only project composition. Leave new Compose screens, estimate writes, schema migrations, PDF, preset templates, bidirectional drawing editor and price catalog changes out of scope.

## Read before changing code

- `shared/src/commonMain/kotlin/com/andreyceilings/shared/measurement/MeasurementElement.kt` and `MeasurementEditStore.kt`
- `shared/src/commonMain/kotlin/com/andreyceilings/shared/prices/PriceCatalogModel.kt`, `PriceParameters.kt`, `PriceCatalogStore.kt`
- `shared/src/commonMain/kotlin/com/andreyceilings/shared/estimate/` and calculation KMP geometry; source-backed precision and legacy saved revisions
- `shared/src/commonMain/sqldelight/com/andreyceilings/shared/db/BusinessSchema.sq` (schema v14), relevant SQLDelight repositories and transactional store conventions
- Unit and DB regression tests for measurement elements, point/line/area placements, catalog, immutable estimates, Android physical SQLCipher and desktop/iOS verification.

**Important known limitation:** `MeasurementElement` **does not yet persist a business role** distinguishing primary canvas, primary profile and additional work. Do not infer primary role from code, label, catalog group, position or quantity mode. P4A must model an *explicit, typed and verified classification* in pure inputs and issue a typed unresolved/error when trustworthy classification is unavailable; do not silently claim database-enforced 0..1 role uniqueness. Persistence of that role and its UI belong to a later authorized phase, **not** a v15 migration here.

## Implemented entry points

- `livepricing/LivePricingModel.kt`, `PricingMath.kt`, `LivePricingEngine.kt`: all-element pricing, typed roles, exact quantities/cents, confirmed/potential, local unresolved vs fatal, source/catalog/scope-bound overrides.
- `livepricing/ProjectPricing.kt`: explicit ordered live/frozen revision selection, omissions, typed origin refs, overlap checks, project-wide works once, content fingerprint/readiness.
- `livepricing/LivePricingReader.kt` + `db/LivePricing.sq`: one fresh read transaction, child/receipt/catalog versions, expected-token recheck, no write/initialization. Await the suspending `closeReads()` admission/drain barrier before DB replacement.
- `livepricing/LivePricingDraftAdapter.kt`: validated stable `MeasurementEditModel` against an explicit coherent source basis; no Save.
- `commonTest/livepricing/`, `commonTest/db/LivePricingProof.kt`, `jvmTest/db/LivePricingReaderTest.kt`: pure cases and shared physical proof wired into Android/Windows/iOS suites, plus concurrent WAL snapshot test on JDBC/Windows.

The v14 history adapter refuses unverifiable leaf coverage with `PROVENANCE_MISSING`; it does not claim old revisions are supported merely because they have a measurement UUID. Fine captured areas that cannot be represented exactly in integer milli remain `QUANTITY_PRECISION`, without rounding the geometry. Full validation/CI results are recorded in PR #107, not assumed by this checklist.

## Implementation sequence

1. **Model & pure engine (commonMain):** validated `LiveEstimateSource` containing **all active measurement elements including unplaced/NONE/Wish**, stable UUID identities, captured geometry, actual active catalog item+group with price/version tokens, and placement metadata for review only. Validate area/perimeter/exact/range/unknown quantity without treating overlays as additional billable rows.
2. **Business rules:** at most one explicitly classified CANVAS and one PRIMARY_PROFILE per measurement (zero is allowed only in an incomplete measurement; both readiness flags require a confirmed included base pair); additional works remain independent. For derived canvas/profile quantities, use verified area/perimeter once; a **per-composition explicit billable override** may change the priced amount but never mutate measured dimensions, `ElementQuantity.CeilingArea/Perimeter`, prices or database. Expose both measured and effective quantities, typed source/origin and safety checks.
3. **Pricing buckets and problems:** APPROVED valid catalog-linked rows contribute to `confirmedTotalCents`; valid DISCUSSING only to `potentialTotalCents`. Distinguish local `OMITTABLE_UNRESOLVED` (price/quantity/item missing, with identity/reason, no silent zero) vs fatal geometry/identity/duplicate-role/stale/overflow problems. Full estimate readiness vs controlled partial **candidate** readiness. P4A does **not** record user acknowledgement: explicit inclusion/exclusion confirmation is future P4B; immutable Save/PDF is P4C.
4. **Composition pure API:** standalone ceiling, all ceilings or explicitly selected subset of live previews and exact frozen source revision UUIDs. Retain original saved revision prices; reject cross-project, missing, overlapping/provenance-duplicate or stale sources rather than guessing/double-counting. Keep selected confirmed/potential totals separate and preserve local omissions.
5. **Transactional read adapter:** load measurement, all children, catalog items+groups and version/receipt tokens as **one coherent SQLDelight transaction**; do not combine unrelated hot Flows, leak stale values or modify database. Version/source signature must support later P4C revalidation.
6. **Tests/proofs and PR update:** pure commonTest, SQLDelight DB proof, Android SQLCipher and Windows/JVM/iOS CI where available. Validate geometry/price/rounding/units/negative/overflow/selection/status/overrides/duplicate placements/partial vs fatal/stale cases. Ensure unchanged schema v14 and immutable estimate history. Update this checklist with actual implementation details and tests before asking for review.

## Strict boundaries

- **No INSERT/UPDATE of estimate history, series, numbering, PDF, or preview drafts in P4A.**
- No new SQLDelight tables, migration, new price seed rows, preset library or business role DB writer in this PR.
- No automatic price lookup by title, fuzzy matching, default prices, implicit rounding, or double-counting source UUIDs.
- Preserve previously merged P3C5 Android/Windows/iOS interactions and their CI; avoid unrelated refactors.
- Decimal input bug [#106](https://github.com/aleks-nl5/andrey-ceilings-android/issues/106) is a **separate** shared KMP/CMP task, not scope creep in this PR. Geometry bidirectional editor remains [#70](https://github.com/aleks-nl5/andrey-ceilings-android/issues/70).

## Review correction checklist (07.11.2026)

- [x] Mandatory base pair per selected live ceiling; missing/invalid/discussing/excluded base blocks full and partial, zero prices valid.
- [x] Empty/project-only/extras-only/advisory-only documents are not ready; frozen roles need verified proof.
- [x] Close barrier read/verify concurrency + failure/cancellation regression passed on JDBC; physical Windows proof is wired into fresh CI; no new admission during drain.
- [x] Mixed advisory live/frozen same provenance allowed; selecting both is rejected in either source order.
- [ ] Fresh same-head Android/Windows/iOS CI and PR/issue evidence; repeat review and owner approval pending.

## Done when

- [ ] Pure pricing and selection APIs implemented with deterministic output.
- [ ] One coherent read adapter, exact versions/UUIDs, no writes.
- [ ] Confirmed/potential, partial/unresolved/fatal, override and provenance cases covered by automated tests.
- [ ] Android, Windows and iOS required CI results checked on the **same final SHA**; any platform execution limitation stated explicitly.
- [ ] PR description documents file changes, test evidence, remaining deferred P4B/P4C issues and manual APK validation status (if relevant).
- [ ] Independent code review and owner approval before marking Ready and merging.

**Codex:** work **on this existing branch and Draft PR**, not a competing branch or new PR; read issue #104 and complete contract before implementation. Stop with explicit documented blocker rather than inventing business logic if any source classifications cannot be proven without persistence changes.
