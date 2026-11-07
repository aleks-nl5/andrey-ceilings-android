# P4A implementation design

Work continues in `feat/p4a-kmp-live-pricing`, Draft PR #107, for #104/#44.

- New `livepricing` commonMain package: pure source/catalog models, deterministic canonical content tokens, integer milli/cents pricing, explicit source-bound business-role classification, quote-bound billable overrides, typed local omissions vs fatal conflicts, and selective composition.
- Read adapter: one SQLDelight read transaction over active project, measurement, every element (including NONE/Wish), all placements, walls/receipts and active item/group metadata. No observer mixing and no writer, schema or initialization call. Each invocation returns a fresh snapshot; optional expected tokens fail closed.
- Draft adapter: only a validated, stable `MeasurementEditModel` state, against the exact persisted source basis, with staged geometry/placements/declared quantities; never saves the model.
- Existing v14 immutable revisions have no verifiable per-element provenance/leaf-composition metadata. The DB adapter rejects them with `PROVENANCE_MISSING`; pure composition accepts explicitly verified leaf sources with exact revision identity and frozen cents. It never guesses coverage from names or stable codes.
- Roles are not persisted in v14. External typed role bindings must cover every catalog-linked element and match the source token. Missing classification blocks finalization, including partial, since otherwise uniqueness could not be established. No roles are inferred from quantity mode or catalog naming.
- Captured geometry can exceed milli area precision. Such a derived quantity is visibly unresolved (`QUANTITY_PRECISION`), never silently rounded. Captured geometry itself and its precision remain unchanged.
- Tests: common domain matrix plus one shared physical-DB proof invoked by JDBC/Windows SQLCipher, Android SQLCipher and iOS SQLCipher suites; same final SHA Android/Windows/iOS CI. No P4B UI/ack, P4C persistence/PDF, #106 parser change or #70 solver.

## Integration contract

`LivePricingReader.read(projectId, measurementUuid, expectedSourceSignature?, expectedCatalogSignature?)` returns a fresh coherent source/catalog pair or a typed failure. Build previews only from a successful read. Retain the source basis when opening an editor and pass it to `withValidatedDraft(model, basisSourceSignature)`; source or role changes require a new binding. Capture draft state on its UI dispatcher. Always await suspending `closeReads()` (atomic admission close + NonCancellable drain of in-flight read/verify transactions) when ending/replacing the unlocked DB session: some JDBC drivers reopen after raw driver `close()`.

For composition, first define explicit ordered measurement UUIDs, source/catalog basis signatures and row scope and `compositionUuid`, obtain `selection.scopeSignature`, then build live previews with that scope and the same quote identity. Set their exact preview signatures in the selection. Scope calculation includes every selected live source/catalog basis and excludes preview signatures to avoid circular tokens. Overrides bind to the source, catalog, quote and scope signatures; copying an override to another quote without rebinding is invalid. Pass the current catalog to `previewComposition` for explicit live catalog recheck; distinct live catalog snapshots cannot be mixed. A pure function cannot discover a subsequent DB mutation: the caller must reread, and P4C must eventually recheck inside its write transaction.

`confirmedTotalCents` and `potentialTotalCents` remain separate. `IncludedPricingWork.selectedForDocument` defaults false for potential rows; only an explicit row selection (or explicit project-work potential inclusion) changes it. `selectedIncludedTotalCents` sums only the selected document rows, without mutating element approval. This describes a pure proposed composition, never a recorded user acknowledgement or permission to Save. Unresolved rows retain exclusion reasons; fatal conflicts block both full and partial readiness.

Content signatures use versioned length-prefixed canonical content, not a truncated hash. They include child placement/mounting data, active elements, captured precision, parent/receipt/wall tokens, catalog item/group versions, role bindings, overrides and composition. Treat them as opaque potentially large in-memory values; do not log production source tokens.

## Verification

The targeted JVM pricing, composition, coherent WAL read and shared DB proof passed before the full regression run. Required final Android, Windows and iOS workflow links and results are maintained in Draft PR #107. No owner APK check or independent review is claimed here. No runnable iOS application host is added: native framework and Simulator/physical SQLCipher proof are the CI scope.

Discrete pieces and комплекти require whole quantities; `послуга` retains the existing domain’s canonical milli Exact semantics, including explicit fractions. No entry parser was changed.

## Review amendments (07.11.2026)

Owner decision in PR #107 replaces optional-base readiness: every selected live ceiling must contain one valid financially included CONFIRMED canvas and primary profile; missing/invalid/discussing/excluded mandatory work blocks both full and partial readiness. Empty or extras/project-wide-only documents are not ready; actual zero-price bases are ready. `LiveEstimateLine.businessRole` preserves trusted role evidence for selection/frozen validation; unknown frozen roles never prove a base. `mandatoryProblems` is distinct from fatal integrity errors and omittable optional unresolved work. Source/composition tokens are now `p4a-v2` to invalidate old readiness/override bases.

Advisory potential rows remain separately priced but do not reserve document coverage. Mixed live DISCUSSING and a frozen row for the same provenance is allowed unless both are selected for the document. Regression tests cover both source orders and the explicit double-selection rejection.

`closeReads()` is a suspending lifecycle barrier: admission and in-flight counting share one CAS state; release is in `finally` after transaction completion/rollback. It waits even if the closing coroutine is cancelled. JDBC/physical Windows tests pause a real transaction, begin close, verify it remains suspended and new read/verify calls issue no SQL, then release the transaction (success/failure) before allowing session replacement.

Review regression verification: local targeted common/JDBC tests passed; full local/CI evidence for the review-fix head is maintained in PR #107. The amended contract is LIVE_ESTIMATE_P4A.md §0.5a.
