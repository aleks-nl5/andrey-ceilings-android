# P4A implementation design

Continue `feat/p4a-kmp-live-pricing`, Draft PR #107 / issue #104. The [final owner contract](https://github.com/aleks-nl5/andrey-ceilings-android/pull/107#issuecomment-6704739545) replaces earlier mixed live/frozen project composition. See [full contract](LIVE_ESTIMATE_P4A.md).

## Separate read-only APIs

- `LivePricingEngine.buildPreview`: exactly one coherent measurement, all active elements (including NONE/unplaced/Wish), typed source-bound roles and current catalog. Placements validate the source, never add charges. Confirmed and potential remain separate; integer milli/cents, checked arithmetic and once-per-line half-away rounding.
- `IndividualEstimatePricing.previewSelection`: transient selection for a **new individual estimate of that same measurement**. Binds project/measurement/composition/source/catalog/scope/preview tokens, tracks inclusion/exclusion and full/partial readiness. Mandatory CONFIRMED CANVAS + PRIMARY_PROFILE must be valid and financially included. Optional omissions require future acknowledgement; selection never changes approval. Zero-price pairs are valid.
- `ConsolidatedEstimateEngine.buildPreview`: only ordered selected rooms and exact frozen individual revision references/store snapshots. Exactly one verified available individual revision per selected room. Empty/missing/ambiguous/duplicate/cross-project/stale/unverified inputs fail closed; unselected store entries are ignored. No live preview, catalog, project-wide works or overrides. Copies saved rows, drawing version/payload, details, currency and subtotal; total is **SUM(saved subtotals)** with overflow checking. Never multiplies quantity/price, rebuilds drawings or revalidates historical mandatory roles. Mixed currencies fail closed without conversion.
- `frozenSourceWarnings`: separate optional comparison of saved/current provenance tokens for selected room sections only. Live changes/unavailability do not block otherwise valid historical printing, alter amounts or select another revision.

`FrozenRevisionVerification` is a trusted immutable-store adapter receipt bound to ownership, exact revision and full content including drawing. It is not cryptographic authorization: callers must not manufacture proofs. Pure fixtures model that future adapter; **v14 production history cannot issue it**. Its reader returns `PROVENANCE_MISSING`, without inferring frozen drawings or individual leaf provenance from measurement UUIDs.

## Reader and integration

`LivePricingReader` / SELECT-only `LivePricing.sq` read project, measurement, all children, geometry/walls/receipts and active catalog/groups in one fresh transaction. No independent Flow mixing, writer-lock UPDATE, initialization or cached success after failure. Child-only changes invalidate tokens. `LivePricingDraftAdapter` accepts a stable validated edit draft against an exact coherent source basis; no Save.

Await suspending `closeReads()` before session replacement/driver close/restore: one CAS state closes admission and tracks accepted read/verify calls; finally releases after physical transaction completion/rollback. NonCancellable drain also handles cancelled closers. New admission starts no SQL. Reader does not own driver close; raw JDBC close can reopen a connection.

For an individual selection, compute its scope token, build live preview with that scope and quote UUID, then bind expectedPreviewSignature. Scope binds exactly one measurement, source/catalog and included keys. Overrides retain captured source and effective billable quantities; changed source/catalog/scope invalidates their basis. Preview/readiness tokens never authorize writes: P4C needs atomic source/catalog/selection/override/ack recheck before explicit Save.

## Verification and limits

Common tests cover live quantity/unit/money/roles/override/readiness and frozen selected-room aggregation. JVM tests cover API boundaries, coherent WAL snapshots and physical close-during-read/verify success/failure/cancellation. Shared LivePricingProof is wired into Android, Windows and iOS SQLCipher suites: coherent reads, stale rejection, zero estimate writes and post-close rejection. Final-SHA CI evidence/counts belong in PR #107.

Schema stays **v14**. Missing trustworthy role bindings give ROLE_UNDETERMINED without inference. Captured area not exactly representable in milli gives QUANTITY_PRECISION. Unsupported live custom units remain unresolved; historical frozen units are preserved.

P4B owns memory-only calculator/individual selection/ack UX and selected-room print dialog with explicit revision choice. Selected missing estimate offers navigation to its measurement; **the dialog never creates/saves estimates**. P4C owns explicit individual Save in that measurement, immutable rows/source/catalog + versioned drawing, atomic commit/numbering and PDF/reprint. Consolidated output is a document package, potentially one-off; no automatic consolidated series or source mutation/renumbering. UI/writers/migrations/PDF/#106/#70 remain outside P4A.
