# P6B implementation

P6B lives in Draft PR #180 for issue #136. The feature uses the #143A reservation ledger and the existing CMP document and SQLCipher infrastructure.

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

The owner will review the physical APK and refine UI/UX. Final independent review and manually agreed FULL runs are separate from this implementation.

## Integration baseline

The starting main revision was `6a5cac216f9bb0a96d7fb1c9e726fcc3ac29d127`. Android FULL #1383 succeeded. CMP FULL #1120 completed with a failed receipt because its iOS/Desktop job was cancelled before any steps ran. Its Windows job succeeded. The Apple baseline still needs a successful result before integration.

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
