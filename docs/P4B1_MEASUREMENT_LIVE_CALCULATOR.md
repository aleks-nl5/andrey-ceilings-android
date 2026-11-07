# P4B1 — production CMP measurement live calculator & explicit persisted role binding

**Owner authorized next stage 07.11.2026:** after [merged PR #107](https://github.com/aleks-nl5/andrey-ceilings-android/pull/107) (read-only P4A). Primary, self-contained Codex implementation issue: [#108](https://github.com/aleks-nl5/andrey-ceilings-android/issues/108), parent [#44](https://github.com/aleks-nl5/andrey-ceilings-android/issues/44). Continue this very branch `feat/p4b1-measurement-live-calculator`, same Draft PR. Read actual main, AGENTS.md, docs/LIVE_ESTIMATE_P4A.md and full issue before work.

## Business boundary (not negotiable)

`Measurement -> Calculator`: **exactly one measurement**, its validated geometry, active elements (NONE / unplaced / POINT / LINE / AREA and Wish), trusted current catalog, confirmed and potential totals. This is a read-only info view; it does **not** insert estimates, revisions, numbers or PDFs. The measurement is allowed to be incomplete. Missing/invalid confirmed main CANVAS and PRIMARY_PROFILE blocks readiness to create an individual estimate, but not measurement editing or viewing pricing diagnostics.

`Measurement -> Individual estimate`: deliberate Save in its own measurement, *future P4C*. A saved document is a frozen, immutable set of prices, rows and a versioned drawing. Future P4B2/P4C implements composition controls, explicit partial acknowledgements, Save and reprint.

`Project -> Consolidated print`: a future checkbox room selector, where **only selected** measurements require a previously saved individual estimate; unselected room without an estimate does not gate printing. Exactly one selected frozen revision per selected room. Consolidated financial total is SUM of saved document subtotals; no live prices, geometry recomputation, automatic creation or writing during print. No consolidated PDF in this PR.

## Production integration gap to close in P4B1

In merged P4A, `BusinessRoleBindings` are supplied externally and fail closed without trusted roles; SQLDelight/SQLCipher v14 `measurement_elements` has no role field. Resolve this by explicit, source-owned **persisted business role per catalog selection** (CANVAS / PRIMARY_PROFILE / ADDITIONAL_WORK). Wish/legacy unclassified entries remain null until deliberately classified; no inference from UUID/code/title/group/QuantityMode. This is a per-measurement role, not a global price catalog property.

- Add new schema/migration v14 -> v15 (or document an equivalently safe schema design). Preserve legacy rows/UUID/createdAt/updatedAt, WAL/SQLCipher keys/backup/restore and frozen estimate history. Never reset DB or infer roles in migration.
- Provide clear in-measurement role assignment/change UX integrated into existing catalog-linked element workflow. Do not add a second calculator catalog picker. Enforce exactly 0 or 1 active canvas and profile per measurement; incompatible unit/quantity mode cannot assume role, including under concurrent writes and idempotent retries. Additional works remain unlimited. Preserve point/line/area placements and CAS.
- Ensure the coherent transactional `LivePricingReader` loads elements + **persisted role basis** + catalog; the UI passes trusted `BusinessRoleBindings` bound to the *same sourceSignature*. Unclassified catalog elements show ROLE_UNDETERMINED and block financial readiness, no default fabricated classification.
- Embed a compact **live calculation information area within the measurement** in shared CMP using existing project measurement navigation. Show confirmed amount, separate advisory potential amount, incomplete/unresolved reasons, canvas/profile readiness, price/catalog/DB/stale errors. Do not show old totals as current after failure. Avoid UX jumps and loss of edit focus. Respect both themes, normal/fullscreen states, scrolling, rotation, keyboard.
- Readings after geometry/element/approval/price changes refresh from coherent source. Changed geometry updates area/perimeter-derived main quantities, never silently rewrites explicitly declared manual Exact work quantity. Placement count is diagnostic, not extra billable quantity.

## Explicitly excluded

Estimate Save/number/revision writer, frozen drawing persistence, PDF/reprint, object consolidated print dialog, P4B2 individual billable quantity override UX/partial acknowledgement, SQLCipher migration bypass, numeric-leading-dot [#106](https://github.com/aleks-nl5/andrey-ceilings-android/issues/106), wall solver [#70](https://github.com/aleks-nl5/andrey-ceilings-android/issues/70), new status/presets, runnable iOS app host. Do not add a working-looking but inert «Створити кошторис» button. Future P4B2/P4C handles this action end-to-end.

## Independent acceptance gates

1. Existing v14 fixture -> v15 with legacy role null; manual assignment persists after restart + portable backup/restore; bad/unknown schema fails safely, no reset.
2. Role selection is per element/measurement, validated and CAS/idempotent; duplicate canvas or primary profile is impossible under competing writes; incompatible item rejection; wish->linked assignment; role deletion/stale state.
3. On-device CMP summary with validated paired approved main items; zero sale price truly 0, no synthetic lines; potential never adds to confirmed. Unknown/Range/Wish/deleted/catalog drift visible.
4. Same geometry/price snapshot yields reproducible totals; update geometry/prices/elements triggers refresh; error/close/restore never exposes stale successful result. No estimates/series/revisions/number writes from info view or role UI.
5. Check normal/fullscreen measurement, existing point/line/area selection, light/dark, scroll/IME, single-source CMP architecture.
6. Migration verification, common/KMP/JVM tests, Android instrumented SQLCipher physical, Windows SQLCipher physical and iOS Simulator SQLCipher, same-final-head Android APK + Windows/iOS CI. Owner separately smoke-tests APK.

Keep this PR Draft until owner review; Codex may implement & run tests, update checklists and docs, but **must not merge/mark ready/close #108 or #44** without owner instruction.
