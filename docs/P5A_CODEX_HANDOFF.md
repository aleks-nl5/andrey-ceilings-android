# OWNER UPDATE — 20.11.2026: P5A Android UX acceptance failed; change action layout in THIS PR

**This section overrides older toolbar/control-layout suggestions below.** In owner-provided Android screenshots, large top/bottom blank space, tiny ceiling and non-useful fullscreen were rejected. Correct host insets, sizing and content-driven lower panel as specified in the latest blocking [PR #132 review](https://github.com/aleks-nl5/andrey-ceilings-android/pull/132#pullrequestreview-5994194778) and [issue #131](https://github.com/aleks-nl5/andrey-ceilings-android/issues/131#issuecomment-7042016930). In the SAME P5A correction, apply all five newly agreed action-control requirements:

1. **Back — existing floating left-arrow FAB, top-left.** Reuse `shared/src/commonMain/kotlin/com/andreyceilings/shared/ui/CeilingsFab.kt`, `CeilingsFabProfile.COMPACT` with the same theme-dependent white/`surfaceContainerHigh`, outline and shadow appearance as `ProjectDetailsScreen.kt`'s back arrow, and the left arrow present in `MeasurementDetailsScreen.kt`. Do not leave the text `TextButton("До заміру")`. Maintain existing `surfaceBack` and `MeasurementEditModel.requestExit`/navigation guard semantics: in fullscreen Back should exit fullscreen first; from editor with dirty source, ask Save / discard / keep editing as already supported. The button is an accessible 56dp target; icon content description comes from `actionLabel`, not a duplicate.
2. **Save — matching floating save-icon FAB, top-right.** Same `CeilingsFabProfile.COMPACT`, shared palette/shadow/touch geometry as Back, with a Save/disk icon and accessible label `Зберегти замір`. Place symmetrically on the right of the title and safe insets, keeping Save enabled/disabled state, `canSaveMountingDraft`, validation/overlay admission, uncertain/conflict semantics, atomic write and no duplicate clicks. **Not** a large text button. Disabled FAB must visibly communicate disabled (and not expose tap action), not just disappear.
3. **Undo, Redo, Fullscreen/Exit — icons ONLY.** Replace textual second-row `Скасувати дію`, `Повторити`, `На весь екран` with compact icon-only buttons in an efficient, minimally tall tool group (one row or appropriate overlay); make tooltip/semantics descriptions explicit for Android TalkBack/Windows. Controls must remain available and >=48dp effective touch area, dark/light theme. Fullscreen toggle icon changes to `Згорнути` on entry; Back FAB behavior still honored.
4. **Remove the separate `Відхилити` header action ENTIRELY.** It duplicates Back's exit journey and wastes horizontal space. **Do not delete the discard functionality inside the dirty-exit confirmation dialog** — this is required for safety. Keep explicit `Відхилити зміни` (discard), `Зберегти` (save), `Продовжити` there, and preserve stale/uncertain guards, same-retry token and source identities.
5. **Zoom/Fit controls — ICON ONLY, VERTICAL on the RIGHT SIDE OF DRAWING.** The current long line `Вписати креслення  −  +` above Canvas must disappear. Render an overlay/control rail beside/on the right edge of the drawing area: **Fit to bounds**, **Zoom +**, **Zoom −**, vertically stacked with comfortable gaps, consistent lightweight floating style and accessible labels. Keep drawing's interactive portion/pointer mapping unaffected; buttons consume their own taps without taking underlying Canvas drag. Avoid obscuring vertex names/dimensions/POINT/LINE/AREA handles: reserve an inset/safe drawing bounds or place rail outside actual canvas viewport while keeping the same camera projection. Fit is only deliberate action and should not overwrite user's zoom/pan on context/sheet/IME/fullscreen changes.

**Layout/acceptance:** One compact header with the two symmetric FABs, short icon tools; canvas dominates remaining space; lower panel content-driven and short in Cost; no phantom Android host header inset, and no global bottom nav beneath a focused editor. On real phone in portrait and landscape compare NORMAL and FULLSCREEN screenshots. Visual screenshot acceptance required; scene tests should assert screen positions/hit targets, semantics/action labels, Save/dirty/Back behavior, and that the icon rail does not occlude a wall or point. Run Android, Windows/iOS CI on final SHA and await owner's new APK verification. **No P5B/C/D scope** (snap, dynamic draft price, primary quick selects, vertex orientation).

---

# Codex handoff — P5A: one CMP «Стеля» workspace

**Implementation authorization:** [Issue #131](https://github.com/aleks-nl5/andrey-ceilings-android/issues/131), umbrella [#95](https://github.com/aleks-nl5/andrey-ceilings-android/issues/95). **Work only in Draft PR** on branch `feat/p5a-ceiling-workspace` based on `main` after owner-merged [#130](https://github.com/aleks-nl5/andrey-ceilings-android/pull/130). This file is the Codex starting point. Read `AGENTS.md`, `docs/P5_UX_DISCOVERY.md`, issue #131 and linked prior UX decisions. Verify the actual checked-out HEAD first.

## 1. Product journey and visual ownership

The **saved measurement editor** reached via either `Objects → Project → Measurement → Edit` or `Measurements → Saved measurement → Edit` becomes a single responsive Compose Multiplatform workspace named «Стеля».

Visual priority on a narrow phone:
1. Compact persistent action bar: back, room name (or «Стеля» with room name), Undo, Redo, Save, fullscreen/fit; explicit dirty/error state below or inline without jumpy layout.
2. **One persistent active canvas** at the center, preserving world-mm projection, overlay selection, gestures, viewport zoom/pan, and exact same controls across tabs/fullscreen.
3. One accessible segmented context selector: «Контур», «Елементи», «Вартість». It is **UI state**, not a navigation destination.
4. **One bottom context sheet** with collapsed and expanded (and optional compact resting) positions. It shows the correct controls for current context and active selection, scrolls independently, respects IME, insets, small phones and landscape. On desktop a side context panel is fine if model/logic shared; focus, pointer and keyboard must work.
5. Never render a duplicate/different Canvas in fullscreen. Fullscreen changes arrangement and available space, not editing identity, persisted geometry, gestures or save semantics.

### Interaction-state specification (implement, don't invent new domain states)

- `ContextMode = CONTOUR | ELEMENTS | COST`, selected mode stored with `rememberSaveable` keyed to the current measurement identity.
- `ContextPanelState` (compact/expanded) is ephemeral/saveable as appropriate; **not** stored in DB. Mode change and panel resize do not trigger source reload or confirmation.
- Selection from canvas:
  - select wall → mode CONTOUR + selected wall fields;
  - select any point, line or area → mode ELEMENTS + specific properties using **existing model APIs**; preserve interaction with multiple kinds and normal/fullscreen;
  - deselect element → mode ELEMENTS list (retained declared-vs-placed quantities), unless the user explicitly switches to another mode.
- Selection from panel should select the same UUID on canvas; do not implement a new persistent selection registry. Temporary UI selection state may be lifted to avoid state loss.
- Preserve the same original `MeasurementEditModel` instance and **one** edit token/reconciliation/receipt lifecycle, backed by existing `MeasurementEditStore`. Do not rebind `navigationGuard` for a second editor.
- Preserve old menu affordances and dialogs as needed during P5A: if line/area/point tooling isn't redesigned yet, **place existing working controls in the panel** and make sure users can actually reach every action. Avoid an empty attractive shell.
- For conditional support: `MeasurementCreateScreen` remains a separate safe existing creation route until P5C. Do not change creation flow or post-save routing; the newly created saved measurement must still open the new editor via existing edit entry.

## 2. Present source facts on main, verified at task creation

- `shared/src/commonMain/kotlin/com/andreyceilings/shared/measurement/MeasurementEditScreen.kt` has the two entry overloads (`store` and `model`). The loaded `MeasurementEditModel` is `rememberSaveable` via its Saver; `navigationGuard.bindEdit/bindSurface` and `reconcile()` must remain valid.
- It currently has `drawing(full)` lambda with `MeasurementDrawing`, `LinePlacementControls`, `AreaFeatureControls`, overlay presentation, old `WallAnchorEditor`, field picker and errors. Normal mode uses `CeilingsScreenScaffold` + `LazyColumn`; fullscreen uses a separate `Column`. **Refactor this asymmetry**, avoid maintaining independent UI branches.
- `MeasurementDrawing.kt` owns `zoom/pan` with internal `rememberSaveable`; ensure viewport state survives mode/fullscreen transitions. Consider small saveable state-hoisting of viewport, or single stable composable slot with responsive bounds. No change to world-mm persistence, layout-to-world inverse or gesture arbitration. Zoom-fit is **presentation only**.
- Existing `MeasurementSelectedFields`, `MeasurementWallPicker`, `WallAnchorEditor` are valid existing controls. Existing exact source/mounting details must remain reachable (point controls currently inline inside `drawing`, line/area controls stacked **ahead of** drawing, fullscreen cap `heightIn(max=180.dp)`; move UI access, do not remove features).
- `MeasurementEditModel`: `state` (dirty, saved, checking, saving, conflict, uncertain), `save()`, `undo()`, `redo()`, `requestExit()`, `reload()`, `displayPoints/Lines/Areas()`, selection, move/cancel and `reconcile()` already exist. Keep atomic contour + POINT/LINE/AREA Save.
- Actual routes: `shared/.../measurement/MeasurementsScreen.kt` and `shared/.../objects/ObjectsClientsScreen.kt` already invoke the same `MeasurementEditScreen`; do not create separate implementations for these navigations.

## 3. Implementation steps (sequential commits in this same P5A PR)

**Step A: extract a single workspace frame.** Keep the model and all existing screen-visible functionality. Build an adaptive layout where header + Canvas are stable, and mode + contextual panel can expand/collapse/scroll without scrolling Canvas offscreen. Responsive compact/expanded sizes; no layout measuring guess dependent on one device. Provide semantics/test tags for mode tabs, sheet states, Canvas, Save/Back, selected wall/feature.

**Step B: wire selection and controls.** Existing wall/point/line/area click events update the bottom panel immediately. Use model's selected line UUID, point UUID, area selected and UI wall index; avoid stale selection when reloading or changing measurement. Drawing the context selector must not clear undo stack. Put full contour fields (including L template and orthogonal add/remove/turn controls), name/notes, point mounting controls, line controls, area controls in accessible expanded UI. No new point/line/area creation gesture or snap yet.

**Step C: fullscreen & persistence safety.** Prefer a **single** rendering path. If different Compose layouts unmount Canvas, ensure source-independent `zoom/pan` state survives consistently and there aren't parallel pointer listeners. Restore relevant UI state after Compose recomposition, IME and recreation; use `navigationGuard` instead of extra BackHandlers. Keep Save/Exit conflict and uncertain retry semantics; no duplicate Save or loss after blocked navigation.

**Step D: cost context without fake math.** A legible read-only «Вартість» panel uses only actually verified saved-measurement information. It may offer the current existing calculator/quote route only through authorized navigation guard. If unsaved draft: say explicitly it is based on last saved state / ask to Save and return; do **not** print stale values as current. Real in-memory draft pricing arrives in **P5D**. No placeholder total with invented amounts.

**Step E: verified UI checks.** Add Android Compose instrumentation(s) on actual new screen and actual controls; desktop Compose scene tests including keyboard/scroll. Keep existing `OverlayDragTouchTest`, `AreaFeatureTouchTest`, `LinePlacementTouchTest`, `MixedDetailsTouchTest` and `MeasurementEditSceneTest` functional; update test selectors only for intentional UI changes, never weaken domain assertions. Include screenshot/semantics on narrow portrait/landscape if possible.

## 4. Hard no-go list

- No database schema migration, data deletion, SQLCipher/WAL/backup or legacy revision rewrite.
- No geometry/price calculation rule, unit/currency, rounding or immutable saved estimate/PDF change.
- No new POINT vs LINE vs AREA implementation issues: owner explicitly requested **all three handled together in P5B as one feature task**; P5A only rehouses their existing controls.
- No smart snapping, new LINE rubber-band or multi-turn polyline; no error-wall highlighting; no vertex exterior relayout or AB orientation; no canvas/profile fast dropdowns; no unsaved draft live price computation. These are later agreed P5B/C/D work.
- No Android-only replacement for shared CMP screen, no second Canvas, no parallel editor model/store, no duplicate persisted object. Do not auto-save draft or auto-generate estimate.
- No silent removal of existing controls; **full functional parity is mandatory**, even if temporary existing dialogs stay until P5B.
- No Owner merge; Codex works only in this Draft PR.

## 5. Proof and Definition of Done

Test these exact routes on **same new UI**:
- Objects → saved measurement → Edit; Measurements → saved measurement → Edit. Normal and fullscreen, return via Back.
- Contour mode → select wall → change decimal length → undo/redo → keep changes while switching modes → Save → reopen same persisted polygon.
- Add/select existing point → properties and anchors → edit/move; similarly existing line and existing area; all accessible in both modes and both layouts; select point after moving line/area.
- Zoom/pan → switch CONTUR/ELEMENTS/COST and compact/expanded sheet → fullscreen and back → verify viewport and selected object unchanged. Switching mode never writes to DB.
- Dirty → Back choices, failed validation, SQLCipher unavailable, conflict/reload, uncertain operation same UUID retry, duplicate Save disabled, IME and focus on small screen.
- COST: no stale unsaved money or false "saved" state; frozen quotes/PDF unaffected.
- Accessibility: text/buttons, screen reader semantics, tap targets, dark/light, keyboard desktop.
- **CI:** same final SHA Android + Windows/iOS checks; include exact workflow links, test counts and known host limitations in PR. Independent review then owner APK smoke then owner-only merge.

Start by posting a concise plan and source-level risk inventory into the Draft PR. Implement sequentially without requesting reconfirmation for already scoped steps. If you encounter a conflict with architecture or required source identity, report precise blocker instead of silently widening P5A.

**Status at handoff:** docs-only initial commit; implementation NOT done. Codex must implement and update PR and tests.
