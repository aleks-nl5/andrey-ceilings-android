# P4A — Codex implementation handoff

Continue **existing** `feat/p4a-kmp-live-pricing` / [Draft PR #107](https://github.com/aleks-nl5/andrey-ceilings-android/pull/107), issue #104 under #44. No other PR, Ready, merge or issue closure. Implementation is not owner acceptance.

Read AGENTS.md, [LIVE_ESTIMATE_P4A.md](LIVE_ESTIMATE_P4A.md), [implementation notes](P4A_IMPLEMENTATION.md), roadmap and [final owner instruction](https://github.com/aleks-nl5/andrey-ceilings-android/pull/107#issuecomment-6704739545). This supersedes mixed composition and print-dialog creation proposals.

## Entry points

- LivePricingModel/PricingMath/LivePricingEngine/MandatoryPricingRoles: pure single-measurement pricing, exact quantities/cents, typed roles/overrides, confirmed/potential and readiness problems.
- IndividualEstimatePricing: exact single-measurement live selection, inclusion/exclusion, full/partial readiness. Mandatory confirmed included base applies to **new individual Save**, never historical reprint.
- ConsolidatedEstimate: frozen-only selected rooms, exact revision provenance, complete frozen rows/drawing/details and SUM(saved subtotals). Separate token-only frozenSourceWarnings has no financial effect. Mixed ProjectPricingEngine removed.
- LivePricingReader / SELECT-only db/LivePricing.sq: coherent snapshot/token recheck. Await suspending admission/drain closeReads() before DB session replacement/close.
- LivePricingDraftAdapter: stable validated measurement draft against coherent basis; no Save.
- commonTest/livepricing, db/LivePricingProof, JVM LivePricingReaderTest/ConsolidatedApiBoundaryTest: pure/API/SQLite proofs and Android/Windows/iOS physical integrations.

## Constraints

v14 persists neither business roles nor sufficient immutable individual/drawing provenance. Require trusted typed role bindings or ROLE_UNDETERMINED; never infer by names/code/group/mode/order. Production frozen-history reader returns PROVENANCE_MISSING. Verifier fixtures are not a production adapter. Never fabricate drawing snapshots from current measurements.

No SQL writes/init, schema/seed, UI, numbering, writer, PDF, price changes, #106 parsers or #70 solver. Preserve geometry precision, measurement journal/CAS, SQLCipher keys/WAL/backup/restore and history/PDF.

## Review and delivery checklist

- Mandatory pair: missing/unpriced/deleted/discussing/excluded blocks full and partial; valid zero-price pair works; optional omissions need future acknowledgement.
- Lifecycle: reject admission, drain accepted physical read/verify after success/failure/rollback including cancelled closer.
- Frozen package: 2 selected of 3 (unselected missing ignored); selected missing/empty/cross-project/duplicate/ambiguous fail; alternate revision affects only its room; live changes preserve totals/drawing with separate warnings; exact cents/overflow; no live/catalog/projectWorks API; no current role checks on history.
- Local shared/JVM, Android unit/build/instrumentation compile, desktop compile, migration checks; fresh **same-final-SHA** Android instrumentation, Windows/JVM/physical SQLCipher and iOS Simulator CI. Record actual results in PR.
- Draft until independent re-review and owner acceptance; APK smoke owner-only.

## Deferred P4B/P4C acceptance

P4B: one-measurement calculator, memory-only individual selection/override/ack. Object print dialog selects rooms and ONE saved revision each; empty or selected missing blocks; unselected rooms never gate. Offer navigation to create in its measurement; no autosave in print dialog. Changed provenance warns and offers unchanged historical print or navigation; no silent latest.

P4C: explicit individual Save creates immutable revision/number after atomic recheck, freezing price/quantity/source/catalog and exact versioned drawing. PDF uses committed snapshot. Consolidated PDF packages frozen room sections/subtotals/drawings with summed total; no automatic consolidated series, source mutation/renumbering or historical repricing.
