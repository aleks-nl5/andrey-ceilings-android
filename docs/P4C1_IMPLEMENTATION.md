# P4C1 implementation — PR #115

Scope: explicit creation of an independently named individual estimate series and immutable v1, frozen-only inspection from measurement/project/global lists, and independent workflow status. No PDF, v2 editor, issuer profile or implicit saves.

## Storage and atomicity

Schema v16 adds `estimate_v1_frozen`, `estimate_workflow` and `estimate_save_receipts` under the existing `estimate_series`/`estimate_revisions` registry. No alternative counter is introduced. The v15 migration adds settings suffix/public-number columns and migrates only future numbering templates; existing document numbers and legacy revisions remain untouched. UUID-only templates receive a sequence-bearing public template. Fresh/migrated/imported schemas are checked against complete table/index definitions, including FK/UNIQUE/CHECK constraints.

`ImmutableEstimateRepository.saveIndividual` owns one SQLDelight transaction. Its host first registers session admission, then enters the physical outer transaction/Android restore lease. The writer claims `price_catalog_state` before reading the operation receipt, source, catalog or settings. `LivePricingReader.readInTransaction` reuses the coherent read body without opening a reader lease or a second physical transaction. Source/catalog drift, selection, roles, quantities, overrides, omissions, acknowledgement and exact discounted totals are checked before sequence allocation. The series, immutable revision, exact snapshot, initial DRAFT metadata and unique operation receipt commit together. A late failure rolls back the counter as well.

The presenter owns title, retained operation command, saving state and successful series ID across Activity/surface recreation. The session coordinator starts undispatched and finishes accepted work independently of the composable. An unchanged uncertain retry retains its UUID; a committed receipt returns the original IDs before consulting mutable source. Worker-side completion signaling lets Desktop close drain scheduled/active work without needing its blocked UI thread. While a Save is accepted, all financial/title inputs and user navigation/window close are locked. Read invalidations are deferred so the captured view stays stable through rotation and source refresh; failures unlock and apply the newest deferred read. Entire-process loss does not recover an unknown request ID; the saved list remains the recovery path.

## Exact immutable document

Authoritative amounts and quantities are Long cents/milli. Pricing and discount reuse `LivePricingEngine`, `IndividualEstimatePricing`, `BillableOverrideRules` and `QuoteDiscount`. The exact payload includes source drawing JSON, geometry, elements, placements, full walls/receipts, identities/versions, stable ordered row IDs, source/effective quantities, frozen price/title/unit/role/bucket, omissions, acknowledgement, discount and final amount. Legacy revision REAL total is only a compatibility projection; P4C1 readers never use it for money.

The versioned envelope binds the complete canonical document with an exact length-prefixed content proof and reconstructs the source signature on decode. This is corruption detection, not a cryptographic authenticity claim. SQLCipher remains the database protection boundary. List, detail and Android backup share normalized-value/provenance checks. Backup also audits operation receipts and treats legacy fuzzy-total history separately from exact v1; corrupt data never falls back to current pricing.

Status uses DRAFT/SENT/APPROVED/REJECTED CAS metadata, with independent version/timestamp bounds. It never rewrites a financial revision. New exact documents remain accessible globally after source measurement/project soft deletion; their display uses frozen names/drawing only.

## Numbering

Future public templates require `{sequence}` and `{suffix}` and exclude `{uuid}`. The suffix combines the optional validated settings label with an independent 10-character uppercase public token from a separate `kotlin.uuid.Uuid.random()` identity. The private full series UUID remains in the canonical internal number. Public numbers are persisted and locally UNIQUE, with bounded collision retry in the issuance transaction. Counter exhaustion fails before SQLite can promote an integer to REAL. Short offline numbers do not promise mathematical global uniqueness across independent installations.

Android's existing number-settings section edits the same persisted policy. Shared Save has no number/suffix override. Existing issued numbers do not change with settings.

## UI

The optional inline title starts from the saved measurement name. The Save footer follows the actual `quote-final-summary`; the existing viewport-based sticky-total handoff is retained. Readiness/errors and in-flight protection are explicit. Only a confirmed commit opens the shared read-only v1 dialog. All three contexts open the same stable series ID. The card renders frozen geometry/placements through the existing `MeasurementDrawing`, frozen rows/totals/omissions and status controls. The current Android Estimates tab reads only frozen v1 and shows no aggregate income across alternative proposals. Legacy records remain in a separate explicitly labelled test archive, without aggregates or influence on the current list/empty state. Desktop exposes the same frozen-only global Estimates route.

## Verification and acceptance

Tests are verification programs, not a claim that every platform has run. Final command results and same-SHA CI links belong in the PR description.

- `FrozenIndividualEstimateProof` runs through existing JDBC and physical Android/Windows/iOS SQLCipher suites: exact override/discount/zero price, one-shot receipt retry, distinct creation, late rollback, status CAS, corruption, stale source/catalog, historical freeze, partial acknowledgement and counter exhaustion.
- `FrozenEstimateMigrationProof` exercises real v15→v16 upgrade, interrupted-DDL rollback, UUID-only policy migration, legacy number preservation and reopen. Native adapters run the same proof.
- JVM concurrency/collision/schema tests cover competing connections, cloned counters, token retry/exhaustion and wrong/missing constraints.
- Existing native portable proofs now preserve exact documents/status/receipts; Android production backup/restore covers a saved exact v1.
- Android touch test drives the actual SQLCipher-backed Save and status card. Desktop scene tests cover footer/handoff accessibility. Existing legacy pricing, numbering, geometry and backup regressions remain required.

Local commands: `:shared:jvmTest :shared:verifySqlDelightMigration :desktopApp:test :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin`. Release compilation may be checked locally unsigned when no production signing credentials are configured; signed release and physical native proofs require their actual CI runners. Linux is not an iOS Simulator or Windows SQLCipher proof.

PR must remain Draft. Static independent review is separate from CI. Owner APK acceptance, Ready/Merge and closing #113/#44 are not performed by this implementation.
