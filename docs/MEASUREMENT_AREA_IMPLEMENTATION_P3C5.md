# P3C5 implementation notes (PR #103)

Refs #102. `MEASUREMENT_AREA_FEATURES_P3C5.md` remains the acceptance contract.
The PR stays Draft until the owner tests the final APK and reviews the code.
Final platform results and their exact commit/run URLs belong in the PR.

## Domain and geometry

`AreaFeature` is used because a feature can be an unpriced, measurement-owned
annotation. Its optional `elementUuid` belongs only to an explicitly selected
compatible catalog AREA snapshot. A rectangle is a four-vertex polygon. No
annotation is encoded in `drawingJson`, POINT or LINE records.

`AreaShape` stores 3–16 polygon vertices or one circle center and a Long-mm
radius. Positions are bounded to ±1,000,000 mm; radius to 1–1,000,000 mm. Circle
diameter input must represent an integer-mm radius: odd-mm diameters are rejected
with an explanation. Creation requires entered numeric diameter; there is no
pre-filled diameter that can silently become a measured value. Reconfirming an
unresolved circle includes explicit radius remeasurement.

Polygon canonical area is the absolute integer shoelace sum (`twiceAreaMm2`).
Circle canonical area is `2 * ((radiusMm² * 3141593 + 500000) / 1000000)`:
pi = 3141593/1000000, half-up to whole mm² before converting to half-mm².
These bounds keep all canonical products in Long. Sum canonical areas first,
then `(sum + 1000) / 2000` gives milli-m², half-up once. Count units contribute
1000 per valid object. At most 1000 active features are accepted per measurement.
A valid tiny shape may have zero milli-m² and still has a placement status and
visible mm² area; it is not silently treated as absent.

Polygon validation proves every edge stays strictly inside the ceiling and
rejects self intersections, repeated vertices and backtracking. Circle validation
checks the distance to every complete wall segment, not sampled points. A
conservative 0.001-mm clearance margin rejects numerical tangency uncertainty.
Touching, clipping and automatic snapping are unsupported. Overlaps are allowed
annotations: no union, subtraction, net-ceiling area or financial quantities are
inferred. Base ceiling area/perimeter and historical estimates/PDF are unchanged.

Every defining point reuses P3C3 `WallAnchor` and `solveWallAnchor`; the circle
also needs its measured numeric radius. Free movement clears affected anchors
and stays approximate. Lost walls/invalid footprint preserve the last known
shape, physical anchors and geometry context. Unresolved is sticky until every
position (and circle radius) is remeasured and explicitly confirmed.

## Persistence and session

Additive `12.sqm` migrates v12 to v13. `area_features` has a direct measurement
owner, optional element owner, typed role/shape, bounded geometry, canonical area,
per-position mounting provenance, review/defer state, versions and tombstones.
`areaVersions` extends the immutable complete-result receipt. Historical
`9.sqm`, `10.sqm`, `11.sqm` and frozen pre-v12 databases are unchanged. The
historical test-schema reconstruction removes new AREA objects when reconstructing
v4; it does not add v13 guarantees to an old schema.

Draft APKs with v13 were uploaded before the final SQL audit reproduced a
receipt bypass: with `recursive_triggers=OFF`, `INSERT OR REPLACE` can replace an
existing receipt without invoking its DELETE guard. Frozen `12.sqm` and the v13
fingerprint remain unchanged. Additive `13.sqm` creates a BEFORE INSERT guard in
v14: duplicate receipt UUIDs and inserts without the currently applied pending
operation abort, including with foreign keys and recursive triggers disabled.
Legitimate full-set Save and identical API retries retain their existing behavior.
A populated frozen `13.db` preserves annotations, linked measured circles,
review/defer state, tombstones and complete receipt history. Android/Windows/iOS
SQLCipher tests inject failure after the new DDL, reopen unchanged v13, migrate,
compare every table/column, reject REPLACE/IGNORE/fake receipts, and save/reopen.
Unknown or hybrid v13/v14 guards fail source fingerprint preflight before DDL.

The existing ceiling operation is the only AREA writer. One writer lock precedes
live reads. Complete POINT+LINE+AREA sets, measurement/element/feature CAS,
canonical geometry, footprint, source anchors, ownership, reservations and explicit
quantity corrections are checked before one statement updates contour, walls,
placements, summaries and the receipt. SQL guards enforce this independently of
KMP, including with foreign keys disabled. Same UUID/payload reconciles; a different
payload fails. Existing outer-rollback proof and uncertain-commit handling remain.

SQLDelight invalidates AREA readers from the same operation. Element, measurement
and project tombstones clean up features without orphaning history. Desktop
schema detection, strict source fingerprints and Android backup preflight include
v13/v14. SQLCipher keys, WAL and portable backup envelope format are unchanged.

One existing `MeasurementDrawing` renders all overlays with one viewport and
one gesture arbiter. Selection precedence is POINT, then LINE, then AREA; ties
inside overlapping AREA fills choose the last painted UUID. Interior and edge
hit testing include a 24-dp accessible halo. Area CRUD, per-vertex/center measured
forms, whole/vertex movement, role/note/diameter properties, confirm-delete,
Undo/Redo, dirty exit, and saveable restoration use `MeasurementEditModel` in
normal/fullscreen. Catalog quantities only change through an explicit previewed
“Взяти кількість з креслення” action, committed with the same CAS operation.

This is the functional UI scaffold. UX redesign remains #95; live pricing is #44;
outline arbitrary-angle solving and a runnable iOS host remain #70/#42.

## Owner review corrections (06.11.2026)

Invalid explicit edits of an existing valid AREA fail before writing when the
ceiling contour is unchanged. The measured-vertex form checks the entire footprint,
including self intersections and concave cutouts, before staging. Automatic
unresolved after a genuine contour change remains sticky. An unresolved feature
needs new measured anchors; a free drag cannot silently replace its last geometry.
A changed circle radius inherits measured provenance only from explicit new
confirmation; an unchanged measured radius retains its provenance.

Standard Details now stages LINE/AREA transforms in the existing saveable
`MeasurementEditModel` using the session's existing `MeasurementEditStore`.
Release commits one draft transform; explicit “Зберегти креслення” submits the
complete contour+POINT+LINE+AREA CAS operation. Undo/Redo, dirty Back, write/retry
freezing and process-restored receipt reconciliation use the same model. Details
fullscreen remains a viewing surface, including the current draft overlays.
Editor normal/fullscreen remain editable. Both Measurements and Objects routes
supply the same unlocked-session store, including its existing shutdown drain.

A selected target owns its hit halo before unrelated overlays. Otherwise selection
uses POINT > LINE > AREA and the last painted filled AREA wins ties. Explicit
selection chips and “Зняти виділення” allow switching overlapping targets. Whole
LINE movement keeps length; an explicit endpoint changes length. Whole polygon or
circle movement preserves size; explicit vertex movement changes area. Only moved
coordinates lose active anchors; unchanged references and review state survive.
Two-pointer gestures cancel the object drag and keep viewport zoom/pan ownership.

Production Android touch tests and Desktop mouse scene tests exercise Details and
normal/fullscreen editor transforms, selection near another LINE's halo, history,
invalid transforms, explicit atomic Save and reopening. Android also closes and
reopens the physical encrypted database. Regression model tests cover invalid
measured vertices, concave footprint, declared capacity and radius provenance.
Final-SHA CI results belong in PR #103. Owner acceptance must be repeated.

## Details Save-and-exit navigation correction (06.11.2026)

The dirty Details dialog retains the original Back/Edit/host transition callback
until the existing atomic Save returns a confirmed result. A narrow `saveAndExit`
path uses the same writer and frozen uncertain-retry command. The committed exit
is delivered once, clearing its callback before invocation; the mounted Details
surface can also drain delivery after a suspended caller resumes. Navigation and
gesture changes remain blocked during the write and confirmed-delivery interval.

Rollback, invalid input, conflict and uncertain outcomes leave the draft and exit
intent in the dialog. Uncertain retry confirms the same immutable receipt without
another write. Continue explicitly cancels the exit intent; Discard keeps its
separate navigation behavior. No SQL schema, migration, encryption or writer is
added. Model regressions, actual Desktop dialog scenes and Android system Back /
Edit + physical dialog-button tests cover commit ordering, failure, conflict,
uncertain reconciliation, exactly one transition and no duplicate journal row.

## Verification and owner acceptance

Common geometry and transaction proofs are reused on JDBC and real Android,
Windows and iOS SQLCipher. They exercise whole-footprint geometry, canonical
rounding, reservations, shared history/restoration, duplicate/uncertain retries,
rollback, direct-SQL bypass, cleanup and physical v12 migration/reopen/fingerprint.
Production Android startup/release and portable restore coverage includes v12/v13/v14.
Android pointer injection and desktop Compose scenes use production area controls.

Test outcomes are recorded in PR #103 after execution. Local Linux scene tests
are not Android device or Windows acceptance. Windows/iOS and Android workflows
must both succeed on the same final feature SHA.

- [ ] Owner tested final Android APK in light/dark, normal/fullscreen.
- [ ] Owner checked Measurements and Objects routes, restart and backup/restore.
- [ ] Owner reviewed code and decides Ready/Merge.
