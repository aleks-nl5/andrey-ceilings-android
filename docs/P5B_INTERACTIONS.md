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

POINT, LINE and AREA selection must stay exclusive. Creating a placement never derives a billed quantity from the new marker count. Existing LINE and AREA controls use the editor transaction already. POINT placement and catalog work creation currently also have separate write paths in the measurement details screen. The shared Add flow must stage any new work or marker in the editor and include it in the same Save and retry receipt.

## Acceptance mapping

- Exercise tap/tap, drag, snap, bypass, zoom and short LINE inputs through the production drawing. Verify both placement endpoints, selected UUID and Undo/Redo on the production model.
- Exercise the common Add, POINT reselect after LINE/AREA movement, exact properties, list/canvas synchronization and exit/reopen against real persisted data.
- Exercise the rectangle obstacle, 0.2 m circle, catalog AREA, Undo/Redo, circle properties after fullscreen and Save/exit/reopen in both themes and modes. Android's `AreaFeatureTouchTest` already provides physical SQLCipher-backed production UI coverage on the emulator.
- Use the platform matrix and owner APK checks in the P5B handoff for final acceptance.
