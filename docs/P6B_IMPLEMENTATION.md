# P6B implementation

P6B was implemented and owner-merged in PR #180 for issue #136. The feature uses the #143A reservation ledger and the existing CMP document and SQLCipher infrastructure.

## Storage and workflow

- The additive v23 → v24 migration adds consolidated series, revisions with save receipts, ordered members and workflow. The series references its existing type-specific number reservation. Each object has at most one series and one approved consolidated revision. Verification checks the schema, ledger links, exact individual source proofs, totals, receipts and version chain during encrypted open and restore admission.
- Each saved version captures the project title and address, document name, ordered exact individual references, the typed additional discount and checked UAH amounts. SHA-256 proofs cover the existing verified individual payloads without copying them into the consolidated snapshot. Historical PDF reconstruction follows the frozen references through individual tombstones.
- Confirmed series deletion tombstones its active versions. The next Save on the object continues the series at its next vN with its existing number. Draft equality compares title, typed discount and ordered references across the series, including tombstones. Creating from an older version carries that exact draft and records it as the source, while the immutable parent chain continues from the latest issued version.
- Approval, deapproval, replacement and confirmed PDF share handoff use the shared catalog writer transaction. Individual status actions capture an affected consolidated approval token before presenting their consequence confirmation. Project and ceiling deletion guards also apply to approved consolidated documents.
- The object financial summary reads bounded checked metadata in one transaction. It verifies the approved consolidated total against the exact covered individual versions, then adds only approved amounts from ceilings outside that manifest.

## CMP and PDF acceptance

- On a physical Android APK, open an object in both themes. Check compact newest-first versions, separate creation, approved-only initial selection, rejected manual selection, an unavailable carried item, both discount modes and draft Back/IME behavior.
- Check agreement between the object sum, frozen saved PDF and included individual amounts. Open exact historical individual content and return to the same consolidated version. Verify the aggregate and per-ceiling drift notices on an approved document after a source changes.
- Preview a draft, then save before PDF export, print or Share. After saving, change the live project and ceiling data and reopen the PDF. Cancel a chooser and verify workflow is unchanged. Select a share target and verify only draft documents become sent. Exercise replacement and deapproval with both checkbox settings.
- Check a current encrypted backup with versions and an individual tombstone, and an upgraded v23 backup. Confirm the restored version can still regenerate its detailed PDF. The PDF host actions use the existing Android exporter.

The checklist above describes the implementation acceptance scenarios; see the final owner and CI evidence below.

## Integration baseline

The starting main revision was `6a5cac216f9bb0a96d7fb1c9e726fcc3ac29d127`. Android FULL #1383 succeeded. CMP FULL #1120 initially failed its receipt after cancellation of the iOS/Desktop job; attempt 2 completed successfully. Both baseline workflows passed; #143 was closed before integration.

[Android FULL #1383](https://github.com/aleks-nl5/andrey-ceilings-android/actions/runs/45824544513) · [CMP FULL #1120](https://github.com/aleks-nl5/andrey-ceilings-android/actions/runs/45824544572)

## Local verification

- The final targeted JVM run reported 32 tests and zero failures. It covered consolidated persistence and workflow, object financial metadata, the current migration boundary and exact individual PDF sources. Windows-only test methods returned early on Linux.
- SQLDelight verified the migration against the generated fresh schema. The localization guard passed for the changed presentation files.
- The Android app and instrumentation sources compiled with `:app:compileDebugAndroidTestKotlin`. The final proof correction was rechecked in the targeted JVM run. Physical Android and native SQLCipher tests remain part of owner acceptance and the agreed FULL runs.

## Owner APK polish (26.11.2026)

Owner requested the compact consolidated create/read-only dialog (approximately half-height, expands for IME/exact source details), empty new title with default on Save, fixed footer with compact discount input and centred amount, copy/PDF/save/trash icons, visually separated version dropdown, swapped deletion actions and icon-based PDF toolbar. Shared ordinary text inputs request sentence capitalization; numeric, technical, phone, email and password inputs are not capitalized. PDF toolbar accessibility descriptions replace text selectors in existing tests. No pricing, workflow, ledger or schema changes. The physical UI appearance and IME behavior require renewed APK acceptance; QUICK first, FULL only after owner acceptance.

### Second physical APK feedback

Empty consolidated discount is zero in either mode, editable as blank and normalized to `0` on blur. Exact individual review uses an inset 78%-height scrollable dialog with compact bordered positions, muted secondary text and right-aligned fixed total. PDF actions share a single toolbar beneath the title, preserving accessibility labels. Compact ordinary text explicitly requests Text keyboard type and sentence capitalization for both Text and Unspecified defaults; phone/email/numeric options remain unchanged. IME and visual acceptance still require the physical APK.

### Shared discount and contextual popup polish

Individual quote editing now uses the same select-all and blank-to-zero-on-blur behavior and outlined discount-mode button as consolidated editing. Blank input is normalized to `0` for calculation, save commands and fingerprints; strict committed numeric parsing stays unchanged. Blank-to-zero display normalization does not invalidate an identical acknowledgement or pending intent. Individual mode switches retain the existing safe reset to zero; consolidated mode switching retains its existing typed value. Shared `CeilingsPopupMenu` defines the same rounded, elevated, outlined surface for version selection and all `ContextFeedbackIcon` explanations across the app. Existing transient quote test covers both discount modes, canonical saved text and invalid nonempty input.

## Final acceptance and integration (26.11.2026)

Owner merged [PR #180](https://github.com/aleks-nl5/andrey-ceilings-android/pull/180) on 26.11.2026 at `f96e8e4875e57032bafe8071a0d7f78c532b0b17`. P6B is accepted and integrated, schema v24. Owner physical APK acceptance 10/10; static review found no blocking production defect. [QUICK #245](https://github.com/aleks-nl5/andrey-ceilings-android/actions/runs/45877641887), [Android FULL #1385](https://github.com/aleks-nl5/andrey-ceilings-android/actions/runs/45881830423) and [CMP FULL #1122](https://github.com/aleks-nl5/andrey-ceilings-android/actions/runs/45881859607) all SUCCESS on exact pre-merge SHA `6788d89b95026fff6b901d22b511fa1c8ada0be4` (FULL attempt 1). Android instrumentation and signed-release open/recovery, Windows SQLCipher/JVM, macOS Desktop/JVM and iOS Simulator SQLCipher jobs succeeded. #143A baseline Android #1383 and CMP #1120 (attempt 2) succeeded; #143 is closed.

Owner explicitly directs closing P6B and proceeding without waiting for automatic main FULL. [Android FULL #1386](https://github.com/aleks-nl5/andrey-ceilings-android/actions/runs/45887037327) and [CMP FULL #1123](https://github.com/aleks-nl5/andrey-ceilings-android/actions/runs/45887037334) were IN PROGRESS on merge SHA at this checkpoint, not reported as passed. Verify their actual results before the next production merge; investigate/fix or revert any real regression. This is a one-time sequencing decision, not a change to CI/merge policy. #136 stays OPEN through P6C.

The preceding physical APK polish sections are historical feedback, superseded by final owner acceptance. No separate quantitative performance benchmark is asserted; successful workflow jobs are not a per-test report audit.

Next: P6C [#140](https://github.com/aleks-nl5/andrey-ceilings-android/issues/140), 35 approved product decisions: global Ceilings/Quotes registries, dashboard and personalization, central + and navigation (#141/#77 coordination). Technical audit on merged main and a bounded owner-approved implementation handoff are still required; no production implementation authorization in this record. P7 calendar #49, P8/#177, Firebase #174 and branding remain separate. Keep current CI scope: no routine pre-v21 historical-only tests, retain real migrations/restore, current DB/SQLCipher and transactional/domain/PDF proofs; no broad UI suite. Owner-only Ready/merge.
