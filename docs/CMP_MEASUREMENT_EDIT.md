# CMP Measurement Editor — PR B1

Base: merged PR #72, main `55fb717`. Refs #73, #60, #57. B1 implements existing
measurement editing and fullscreen; B2 remains a separate owner-accepted step for
global Measurements, quick-create/calculator parity and Android-only removal.

## State and input

Existing records open in read-only Measurement Details. «Редагувати» opens typed
`MeasurementEdit(projectId, measurementId)` with the original version and captured
precision. `MeasurementCreateDraft` and its existing reducer/validation are reused;
no second geometry engine or mutable Canvas contour exists. Rectangle and L use
source parameters, measured orthogonal polygons use every source side and turn.
Incomplete/unsupported source geometry fails closed without migration or guessing.

Read-only zoom, pan, fit and wall selection do not write. Fullscreen is a separate
presentation state, carried between details and edit. Global host navigation is
hidden in fullscreen; system bars/insets remain available. Android reserves the
larger IME/system-navigation inset. Touch pinch/pan, mouse drag/wheel zoom,
Ctrl+wheel pan and focused Canvas keyboard +/-/arrows/Home use the same CMP code.
Back/Escape first leaves fullscreen. The ordinary host navigation/animations and
retained Project Details composition/list context remain in place.

Wall labels still come from MeasurementLabels. Selected wall chips and numerical
fields select the same contour edge. Contextual fields are shown by default;
«Усі розміри» expands the full input list. Rectangle opposite walls share source
parameters. L CD = A − C, DE = B − D and FA = B expose the relevant source fields
and an explanation; they never get independent numerical inputs. Drag pans the
view and never edits geometry. Exact wall dragging/constraint solver is PR C #70.

Undo/Redo groups text input until focus changes/Done/another operation. Shape,
turn and topology changes are distinct operations. History is bounded to 100
operations (saved history also has a 128 KB budget), restored locally and cleared after Save/discard. Invalid intermediate
text remains editable; only validated geometry is drawn and Save requires the
existing KMP validation. Captured precision cannot change during editing.

## Write boundary and navigation

MeasurementEditStore loads ownership/active-parent/source geometry from the real
repositories. Save repeats validation and uses one transaction with atomic SQL
`UPDATE … WHERE id/projectId/updatedAt/deletedAt/active-parent … RETURNING updatedAt`.
The read checks improve errors; SQL CAS is the concurrency boundary. The new
version is `max(now, updatedAt + 1)`, including equal/backward clocks. UUID,
createdAt and projectId are absent from SET. Frozen geometry, measured parameters,
notes, name and KMP totals are saved together. No-op Save does not update the row.
No driver/schema migration, DB reset, financial snapshot or PDF changes.

A Mutex rejects repeated Save, edits freeze during the NonCancellable transaction,
and the shared navigation guard keeps the editor visible through commit and
result delivery. Back/Escape/tab/quick-create/window close cannot hide an active
write. Success returns to the same measurement, then the same parent/list. Errors
retain the draft. A conflict never retries a stale update; explicit reload asks
before discarding local changes. Cancel/discard never writes.

Dirty Back/tab/window close and Cancel offer Continue / Discard / Save. Choosing
Save opens the saved measurement; a pending tab/window-close action is intentionally
not replayed after Save, keeping the transaction result visible. Discard resumes
the originally requested destination. Dialog/pending callbacks are not restored.

The local saveable snapshot holds baseline, version, draft and history, not Save
commands. An unlocked-session registry retains only in-flight/result-pending edit
models; Android's ViewModel holds the shared guard across Activity recreation.
A recreated screen waits for the real session before consuming its form snapshot
and reattaches to the same operation. After process replacement, result recovery
reads the record and reconciles an identical committed payload; it never writes.
If a different version is found, the draft is retained and reload is required.

## Verification and acceptance

Common/JVM proofs cover all three shapes × four precisions, real geometry edits,
area/perimeter/source round trips, read-only no writes, undo/redo, cancel/no-op,
restoration, ownership, stale/deleted/parent-deleted records, rollback and unavailable
storage. A paused real SQL transaction exercises immediate Back/tab/quick-create,
window-close, duplicate Save and Activity model reattachment. The same persistence
proof runs on Windows encrypted SQLCipher and iOS Simulator SQLCipher. Android
instrumentation runs corresponding real encrypted WAL CAS/reopen/slow-save proofs.
Desktop scenes cover read-only/fullscreen, input/history/dirty dialog and view tools.

Run results, final SHA, APK artifacts and cache/duration comparison belong in the
Draft PR. Mandatory CI remains Android unit/instrumentation/Debug/Release,
Windows Desktop/JVM/encrypted SQLCipher, iOS framework/Simulator. Workflows and
dependency versions are unchanged. PR #72 baseline final `b9effed`: Android run
41634796498 (12m30s), Windows/iOS run 41634796514 (5m02s / 10m09s). Gradle-home
hit on all three runners; Android clean AVD hit. Owner APK acceptance is pending
for B1; automated scenes are not a substitute.

Manual APK plan (light and dark): create one record for each shape/precision;
open read-only without changes; select every wall and use fullscreen/pinch/pan/fit;
edit dimensions/notes/shape/sides and check totals, Undo/Redo, Save/reopen; Cancel
and dirty Back/tab; rotate with a dirty draft and during Save; check IME/system
bars on a narrow screen; keep existing Android global editor/calculator and
Project Details list/scroll/FAB behavior. The owner tests and merges B1 before B2.
#60/#57/#73 remain open. Free angles, overlays #71, PDF and calculator rewrites
remain outside B1. iOS runnable host remains #42; framework/Simulator gates still run.
