# PR #28 — Android number-format settings via shared KMP SQLDelight

**Stage:** UI for configuring the format of **future** estimate document numbers after merged PR #27. [Issue #25](https://github.com/aleks-nl5/andrey-ceilings-android/issues/25).

## Contract

- **Single source of truth:** `estimate_number_settings` in the encrypted SQLDelight business database. The same `ImmutableEstimateRepository.numberPolicy()` / `configureNumberPolicy()` is consumed by Android, Windows/JVM and iOS through KMP. UI and Android DAO are thin adapters.
- **Future documents only:** the new setting is applied by `createSeries()` when allocating a brand-new estimate. Existing `estimate_series.documentNumber`, `seriesUuid`, original format snapshot, `estimate_revisions`, and PDFs remain unchanged. Clones receive new numbers using the currently saved policy.
- **Supported placeholders:** `{prefix}`, `{year}`, `{sequence}`, `{uuid}`. A full v4 UUID must appear exactly once. No unbounded free-form tokens or unsupported filename characters.
- **UI:** four presets (standard, year/prefix/UUID, prefix/sequence/UUID, UUID-only); manually editable template and ASCII prefix; minimum padding width 1–12 for the local sequence. Invalid configurations are displayed before submission and fail validation in the KMP repository as well. A preview uses an illustrative UUID and sequence 125, **does not allocate or reserve a number**, and is not a real issued number.
- **Saving:** only an explicit `Зберегти формат` writes the policy. `Скасувати` abandons unsaved edits. Errors leave the previously saved policy intact and visible. The setting is loaded again when the Android database session changes (e.g. restore).
- **Offline uniqueness:** local sequence is not globally sequential; the canonical identifier retains full UUID, `UNIQUE(documentNumber)`, UUID collision detection/retry. Neither the prefix nor sequence is by itself a guarantee of global uniqueness.
- **No schema migration:** PR #27 already added `estimate_number_settings` and persisted a default row on the first document allocation. The editor reads the default even before the first estimate exists. No new tables and no changes to SQLCipher `DELETE`, backup, calculation or PDF rendering.

## Verification

- Shared JVM tests: policy persistence, valid presets and custom settings, rejection of invalid policy, immutable old number, current-policy new number, sequence retention after deletion, and preview without reserving.
- Android emulator SQLCipher instrumentation: settings saved and read after reopening; old and new format numbers coexist; invalid attempts leave saved policy untouched; portable encrypted `.stpb` backup/restore preserves settings and both series.
- CI required on **last PR #28 HEAD**: Android Debug/Release + instrumentation, Windows JVM and iOS Simulator.
- Physical Android acceptance: open Settings → numbering; change prefix and template and preview, save, create new estimate with new number, clone it, export PDF, relaunch app, backup/restore; verify earlier estimate's number is unchanged.

## Out of scope

Manual repricing preview and immutable v2/v3 UI/PDF; CMP shared settings screen migration; global server-issued short sequences; SQLCipher WAL (issue #26). These remain separate development stages.
