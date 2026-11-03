# CMP Measurement Foundation — stage 1 (#60, #57)

This stage adds read-only measurement details reached from a project's actual
1:N measurement list. Android and Windows use the same KMP route, repository
flows, codec, Compose screen and Canvas renderer. iOS compiles/tests the shared
code; runnable UI and live predictive preview remain #42 and #56 respectively.
See [the pre-change audit](architecture/CMP_MEASUREMENT_AUDIT.md).

## Data and geometry

`measurement/MeasurementGeometryCodec` reads/writes the existing measurement
v2/v3 JSON using the already-present kotlinx-serialization-json dependency.
It preserves Long millimetres without a Double conversion, template parameters,
ordered vertices, v3 measured lengths/RIGHT/LEFT turns, captured precision, area
and perimeter. The existing FrozenPolygonGeometryContract remains the sole
geometry validator; no calculation rules or rounding have changed.

Parsing rejects unknown/missing fields at every structured level, duplicate
keys (including escaped equivalents), unsupported versions, non-integer or
out-of-range millimetres, invalid numeric types, malformed JSON and geometry
that cannot reproduce its saved totals. Input length/nesting/vertex counts are
bounded. Unknown fields in template dimensions are rejected by the existing
shape contract. Supported shapes come from CeilingShapeTemplates plus polygon;
no second template engine is introduced.

`FrozenPolygonGeometryJson.readMeasurement/writeMeasurement` remain the Android
editor/calculator entry points and delegate to KMP. Their estimate/PDF snapshot
methods retain the original JSONObject path and schema. Emulator tests verify
both directions against that old geometry writer/parser, and common wire fixtures
cover the existing rectangle/L/polygon v2 and measured-side v3 encodings.

`measurementGeometryForViewing` has three explicit outcomes:

- Captured: valid v2/v3 whose shape matches the database row.
- HistoricalRectangle: only an empty-object drawing plus valid saved rectangle
  dimensions. Draw those dimensions without inventing a frozen precision,
  re-rounding them to current settings, or recalculating stored totals.
- Unavailable: malformed, unsupported, mismatched or incomplete geometry. Metadata
  remains readable; corrupt nonempty JSON never silently falls back to a rectangle.

Opening or restoring never writes/replaces JSON, timestamps or totals. No SQLDelight
schema/migration, SQLCipher/WAL/key/backup, estimate revision/PDF or numbering change.

## Shared drawing

```kotlin
val view = measurementGeometryForViewing(
    row.shape, row.drawingJson, row.lengthMeters, row.widthMeters
)
MeasurementDrawing(view, Modifier.fillMaxWidth(), compact = false)
```

`MeasurementDrawing` uses CMP Canvas, Material colors/typography and Compose
text measurement/drawText; it has no Android Canvas/Paint/Context dependency.
The pure `layoutMeasurementDrawing` projects saved world coordinates at a uniform
scale. V3 labels use actual measured lengths; v2 labels derive from world edges,
never screen pixels. Vertices are painted as dots and sides retain their ordered
numbers. Compact/full Canvas heights are 155/235dp.

Inline labels are placed inside the viewport with collision checks against
other labels and vertices. Labels that cannot fit are omitted **only on the
Canvas**; a normal wrapping text legend always contains every side and dimension.
This keeps narrow and dense contours readable without clipping or hidden lengths.
Invalid geometry gives an explicit message rather than an empty/crashing Canvas.

The legacy Android `MeasurementDrawingPreview` remains in use by the existing
editor/global list/calculator. It has active consumers and is intentionally kept
until owner-verified visual parity and the later editor migration. New measurement
details on both Android and Windows already use the single shared renderer.

## Reactive details and typed navigation

`MeasurementDetailsStore.observe(projectId, measurementId)` reads the existing
MeasurementRepository and ProjectRepository by-ID flows. Both rows must be active,
and the measurement's projectId must match the route. Deleted/missing/wrong-owner
records become Missing; failed/closed session reads become Unavailable. Geometry
work runs on the existing shared Default dispatcher. Hosts supply only the current
unlocked store or null, never fake rows or platform-specific DB rules.

The state is Loading / Loaded / Missing / Unavailable. The screen resets its
collector for a new route/session, so a previous record cannot flash while loading.
Loaded details show name, parent object/address, shape, stored area/perimeter,
shared drawing and notes. There are no edit/create/delete callbacks in this screen.
CeilingsScreenScaffold provides measured pinned heading, a CeilingsFab Back action
and one top fade; the outer host keeps ownership of bottom navigation/insets.

```kotlin
val project = AppNavigationState().selectTab(AppRoute.OBJECTS).openProject(projectId)
val details = project.openMeasurement(projectId, measurementId)
check(details.back() == project)
check(AppNavigationState.restore(details.snapshot()) == details)
```

MeasurementDetails carries only positive numeric projectId/measurementId. Opening
requires the current parent route, duplicate taps are idempotent, and DB ownership
is checked again by the store. Snapshot adds a canonical
`MEASUREMENT_DETAILS:projectId:measurementId` token after the parent token; existing
ProjectDetails snapshots still restore. Malformed IDs/duplicates/foreign parents
fail closed, and pending quick-create commands are excluded from snapshots.

Project Details stays composed but unplaced/inactive while viewing a measurement:
its list and title state remain, it contributes no pointers/semantics, and pending
snap is cancelled. Back places that same screen again. Its 30% status-card anchor,
address/FAB motion and calculations are unchanged. Objects search/filter/tab/list
state is saveable for process death; an actual tab switch still mounts a fresh
list at the top and invokes the existing host-header reset.

## Verification and next stage

Tests cover previous Android wire compatibility, exact-mm round trips, all shapes
and precisions, bad JSON/geometry, historical dimensions, projection bounds and
label collisions. JVM Compose scenes render both modes/themes at narrow/wide
sizes, resize them, exercise invalid/removed states and Back, and save/restore
nested navigation with search/scroll before switching tabs. Repository tests use
real SQLDelight flows for ownership/update/deletion/unavailable DB and no-write
assertions. Android, Windows and iOS encrypted tests also read details through the
new shared store. Existing 0/1/N, tab/header and status-card snap tests remain.

Final CI links, timings and cache comparison against final PR #65 belong in the
Draft PR. Owner APK smoke remains pending until actually performed: global editor
create/edit/save/reopen and calculator, measurement v2/v3/historical/invalid
viewing, Light/Dark/System, narrow/landscape, Back/Escape/restore, parent docking/
30% snap, search/filter/scroll context, 0/1/N and deleted/unavailable records.

Next separate stage: CMP Measurement Editor with all current templates/measured
sides, validation/save via KMP, project-prefilled '+' route, retained global
Measurements tab, and removal of the legacy editor only after complete parity.
No forward create/edit route, reparenting, new calculation rule or legacy editor
rewrite is included here. Issues #60/#57 stay open for that work; #56/#42 are separate.

## JDBC write limitation discovered in PR #66

PR #66 exposed a pre-existing Windows SQLCipher JDBC failure for INSERT RETURNING
while seeding the encrypted readonly proof. That PR used non-returning create APIs
in the fixture and explicitly left the production write compatibility issue open.

The separate [#67 / Draft PR #68](https://github.com/aleks-nl5/andrey-ceilings-android/pull/68)
repairs the Windows/JVM adapter and switches this encrypted measurement test back
to the production returning-ID APIs. Geometry, encrypted reopen, ownership and
deletion assertions remain. See [root cause, adapter and native test coverage](WINDOWS_SQLCIPHER_RETURNING.md).
This does not migrate or implement the measurement editor.
