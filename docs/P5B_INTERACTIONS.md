# P5B interaction contract

The saved-measurement editor uses one `MeasurementDrawing` in both modes. It owns hit testing, transforms, pointer capture and double-tap detection. `MeasurementEditScreen` owns panel visibility and routes selection to `MeasurementEditModel`. The model owns pending tools, selection, geometry validation, history and the CAS Save through `MeasurementEditStore`.

| Input | Idle drawing | Placement in progress |
| --- | --- | --- |
| Single tap on an overlay | Select the hit. Normal opens its context. Fullscreen keeps the current panel state. | The active tool receives the projected world position. |
| Double tap on the same overlay | Fullscreen opens the selected properties. Enter and the canvas accessibility action use the same route. | Treat each tap as tool input. |
| Tap on empty space | Clear the selection. A wall hit selects the wall separately. | Supply a tool coordinate. An invalid coordinate leaves the tool active. |
| Drag one pointer | Move the selected overlay within its hit target. Otherwise pan. Commit the completed move as one undo step. | LINE owns the drag and previews the endpoint. Other tools use tap placement and preserve panning. |
| Move a mouse with no buttons down | Leave selection and viewport alone. | Update the LINE preview after its first endpoint. |
| Two or more pointers | Cancel an in-progress overlay move and pan/zoom. | Cancel the drag preview and pan/zoom. Keep any first endpoint committed by a prior tap. |
| Back | Close the fullscreen panel, then leave fullscreen, then use the existing dirty-exit guard. | Cancel unfinished placement before applying the idle sequence. |

A short projected LINE uses point distance when both projected endpoints coincide. The selected overlay owns drag in its hit zone. For taps, POINT remains reachable over a selected AREA. An AREA that was already selected keeps tap priority over a nearby LINE. LINE snaps to a nearby wall direction only if the snapped segment remains strictly inside the ceiling. The endpoint starts with raw input each time, so bypass immediately restores it.

POINT, LINE and AREA selection must stay exclusive. Creating a placement never derives a billed quantity from the new marker count. Existing LINE and AREA controls use the editor transaction already. The shared Add flow stages new catalog work and POINT in the editor. Existing LINE and AREA writers handle their draft overlays. Save uses one SQLDelight transaction. The first journal applies the contour, existing overlays and catalog-linked additions. For new POINTs, the existing guarded insertion uses the resulting contour. A second journal records those POINT versions and applies any measured anchors. Both journals commit or roll back together. Each retry checks the original request and the resulting versions before returning the saved measurement.

## Acceptance mapping

- Exercise tap/tap, drag, snap, bypass, zoom and short LINE inputs through the production drawing. Verify both placement endpoints, selected UUID and Undo/Redo on the production model.
- Exercise the common Add, POINT reselect after LINE/AREA movement, exact properties, list/canvas synchronization and exit/reopen against real persisted data. `WorkspaceAddPointTouchTest`, `LinePlacementTouchTest` and `OverlayDragTouchTest` use the production editor and Android pointer injection. `WorkspacePlacementDraftTest` checks rollback, CAS, lost-response retry and current pricing.
- Exercise the rectangle obstacle, 0.2 m circle, catalog AREA, Undo/Redo, circle properties after fullscreen and Save/exit/reopen in both themes and modes. Android's `AreaFeatureTouchTest` already provides physical SQLCipher-backed production UI coverage on the emulator.
- Use the platform matrix and owner APK checks in the P5B handoff for final acceptance.

## Owner UX correction: direct grip editing & wall-parallel snapping (22.11.2026)

- The above original gesture table documents the **current baseline**, not final approved P5B. **Selected LINE:** endpoint grip hit-test wins over body (nearest endpoint on short segments); a drag moves **only the gripped endpoint** continuously in 2D, changing length/angle (left/right and extend/shorten). Body drag translates line without modifying length/angle. No prerequisite `lineMoveTarget` chip. Live preview, cancel/multitouch, boundary validation, one undo step, exact world-mm internals and stale mounting review.
- **Selected AREA Polygon/rectangle:** vertex grip wins over body; direct drag reshapes, keeping polygon valid and **rectangle right-angled**; interior/edge body drag translates. No prerequisite `moveVertex` chip. Circle has no vertex: preserve center/diameter numeric handling. Avoid false selection of overlapping POINT; retain existing hit-test priority and selection invariants.
- **Wall parallelism:** current `previewLineCreation` picks only nearby walls (~300 mm entering/450 mm held), and is wired only during creation. User requires real angle-driven snapping to wall directions **independent of distance from room wall**, in **creation and end-grip edits**. Maintain hysteresis and explicit bypass; preview a concise guide, reject invalid/outside segments, never shift the fixed endpoint. Test >450 mm from every wall and unsnapped free angles. New preview is not a DB commit.
- **No user-facing cartesian coordinates:** remove user-visible «координати», X/Y, axes, raw positions and equivalent a11y copy; keep exact internal positions and expose wall distances, length, width/diameter and physical mounting properties through inline editor.
- The UI gesture tests must call **the production Canvas pointer path**, not synthetic model move-target setters. Preserve pan/pinch, normal/fullscreen, Undo/Redo and Save/reopen.
