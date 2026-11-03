# CMP Measurement Editor — PR A: creation

Implementation from `main` `99a49a9` (merged documentation PR #69). Related: #60,
#57; future free-angle geometry #70 and typed ceiling features #71. This is a
create-only increment; owner APK acceptance and final-head CI are tracked in the
Draft PR. Neither issue is closed by this increment.

## Audit and boundaries

The audit covered AGENTS, README, current roadmap, the editor plan, UI/measurement
foundations, the sides/drawings architecture, current issues #60/#57/#70/#71 and
the actual Android and Windows hosts. Existing `MeasurementsScreen` /
`AddMeasurementDialog` combine Android form state, platform preferences and the
legacy DAO write callback. `OrthogonalSidesEditor` already submits lengths and
90° turns to KMP. Those Android consumers remain intact for PR B, including their
calculator entry points. No compatibility layer, old archive recovery, automatic
reset, migration, schema or driver change was added.

`MeasurementGeometryCodec` and `FrozenPolygonGeometryContract` already preserve
the exact source dimensions and vertices (v2 templates / v3 measured polygons).
`MeasurementDrawing` already renders a proportional shared contour and full
wrapping legend. `MeasurementDetailsStore` already verifies ownership through
reactive repositories. The new editor reuses these boundaries rather than
copying the old dialog or creating another calculation engine.

## Shared form and geometry

`MeasurementCreateDraft` is the single input draft for fields, validation,
drawing, totals and persistence. `MeasurementCreateIntent` updates it. The
outline input is a sealed variant: template parameters or orthogonal measured
sides. Orthogonal turns belong only to the latter variant; a future explicit
measured-angle/diagonal contract can introduce another variant without pretending
that v3 LEFT/RIGHT can encode arbitrary angles.

Template fields come from `CeilingShapeTemplates.parameterKeys`. Decimal dot and
comma are accepted. Text is parsed directly into Long millimetres, with bounds
and the captured precision grid checked before building the contour. Off-grid
values are rejected with a correction message, not silently rounded. Empty,
zero, negative, nonnumeric, nonfinite and sub-millimetre values cannot save.
Rectangle minimum 0.1m and L-shape minimum 1m² retain the existing verified input
policies. All area/perimeter computation and contour checks use the existing
KMP polygon contract. No side is invented to close a measured polygon.

New Android drafts capture the current application preference once. Form restore
keeps that precision even if a later default differs. Windows currently has no
settings screen and uses its existing shared default of 1cm; adding a Windows
settings host remains outside this increment. Every supported precision is
tested. Saved geometry carries its fixed precision and opening details never
rewrites it.

`MeasurementLabels` is the sole contour-order naming rule: A…Z, AA… and AB, BC,
…, DA, with Z–AA / AA–AB for compound names. Template parameter display names
refer to the corresponding edges (the L overall width is BC + DE). The form,
validation, Canvas and full legend share the rule. Display labels never replace
identities; polygon input rows have independent IDs that survive removal of
other rows. Canvas hides colliding labels only; the complete legend remains.

## Storage and navigation

`MeasurementCreateStore` validates again at the write boundary, then checks the
active parent and calls `MeasurementRepository.createReturningId`
inside one SQLDelight transaction. Geometry, source dimensions/sides, notes,
bounding dimensions and KMP totals are written together. Existing repository
UUID and createdAt/updatedAt generation is retained. Failure rolls back; missing
parents and unavailable/locked/failed storage do not report success. Native
exception text is not exposed because it can contain SQL or private paths.
SQLDelight generated invalidation updates the parent's existing Flow list.

Each creation draft captures one shared v4 record UUID. The repository's returning
API accepts it as an optional argument (all existing callers keep their default
UUID generation), and a by-UUID lookup uses the existing unique column. An
explicit retry after restore reuses an identical committed row; changed or
deleted content is rejected rather than duplicated or overwritten. This adds a
query, not a table/schema migration, and covers restoration during a write.

`MeasurementCreateModel` gates repeated saves with a Mutex and a completed ID,
freezes inputs during a write, and rejects writes after Cancel. An atomic write
already started finishes even if the screen is dismissed; it is never retried
automatically. Form restoration restores inputs only, not commands or save
effects. Unsaved form contents live in Compose's local saveable registry, never
in the ID-only route snapshot.

The typed `MeasurementCreate(projectId)` route opens only under its actual
ProjectDetails parent. Repeated '+' taps are idempotent. Successful creation
replaces the editor with read-only MeasurementDetails, so Back returns to the
same parent. Late completion cannot reopen an editor already left by the user.
Android Back and Windows Escape use the existing shared navigation. The parent
stays composed but unplaced/inactive beneath either child; its scroll/title
state and the established address/FAB/30% snap rules are preserved. Android's
editor reserves the larger of IME or existing bottom-navigation padding.

## Extension boundaries (#70/#71)

The draft's `outline` is separate from drawing projection/presentation. A future
typed feature collection belongs alongside that outline in the measurement
document, not inside polygon vertices, field state or painted symbols.
`MeasurementDrawing` draws only the outline; `layoutMeasurementDrawing` provides
the common world-to-viewport projection for a future overlay. No empty feature
tables/catalogs or speculative CAD framework are introduced. Feature placement
must not silently change contour area or calculator prices.

Read-only details and the editable creation screen are explicit consumers of
the same geometry contract. Presentation (`compact` today, fullscreen later)
does not grant editing capability. Future selection, wall/feature anchors,
context actions, undo/redo, zoom/pan and calculator edits must submit intents to
one validated draft. They must not retain independent mutable Canvas geometry.
PR B owns numeric edit/fullscreen foundation, PR C real Snap OFF/solver, PR D
typed overlays/calculator integration; none is claimed here.

## Verification and acceptance

Common tests cover all three shapes/four precisions, exact decimal parsing,
invalid fields/closure/turns/topology, source-side round trips, 4/6/26/27/128
labels, draft snapshots and typed route Back/Cancel/restore/double taps.
SQLDelight tests cover repeated Save, ownership, UUID/time, failed insert rollback,
deleted parent, closed database, restoration without a write and reactive 0→1→N
parent lists. The shared persistence proof runs on Windows native encrypted
SQLCipher including actual INSERT RETURNING and on iOS Simulator SQLCipher.
Android instrumentation uses the production encrypted WAL driver and the same
shared model/store. All shapes/precisions reopen from real encrypted databases.
JVM Compose scenes exercise both themes, resize, form restore, disabled/error
states and Cancel; the existing readonly/list/docking scenes remain enabled.

Required final-SHA CI: Android unit/instrumentation/Debug/Release, Windows
Desktop/JVM/native encrypted writes, iOS framework/Simulator. Compare durations
and cache logs with PR #68 (`51ae870`): Android 10m19s, Windows 4m19s, iOS 17m55s;
Gradle-home hits on all runners and Android clean AVD hit. No test or CI gate is
removed or weakened. Actual run IDs, results and APK links belong in the PR.

Owner manual acceptance remains **pending**: create from a specific object; all
forms and polygon turns; drawing/area/perimeter; save/reopen; Back/Cancel and
repeated Save; light/dark, keyboard/scroll/narrow screen; existing Android global
Measurements/editor/calculator regression checks. Headless scenes and automated
instrumentation do not replace that acceptance. Merge is owner-only.
