# CMP «Елементи стелі» — P3B / #90

P3A PR #88 merged / #87 closed. Refs #76/#78. P3B is delivered as one Draft PR,
with owner acceptance pending. Pricing #44, Canvas #71, runnable iOS host #42
and cosmetic UI/UX remain separate.

## Source of truth and UI flow

The existing `MeasurementDetailsScreen` embeds the section after drawing/notes.
Android global Measurements and Objects, and Windows Objects, all use this same
screen through `ObjectsClientsStore.measurementDetails`. The shared details
projection exposes `room_measurements.uuid` only after checking active project,
measurement and local ownership. `ElementRoute` includes project ID, local
measurement ID and this verified UUID. Hosts do not implement parent lookup.
Fullscreen remains drawing-only; drawing height, geometry/editor, selected sides,
precision and geometry CAS are unchanged.

`MeasurementElementsStore` delegates every mutation to the P3A repository and
observes persisted records in stable createdAt/UUID order. It combines active
parent/project flows with elements so missing/deleted parents disable work. No
DDL, migration, seed, new catalog, financial/estimate/PDF or WAL/key change.

«Додати елемент» opens one CMP dialog: «З прайсу» / «Побажання». Catalog filters
use active group UUIDs, preserve group order and include empty categories. Search
uses the existing Unicode normalization. Loading, unavailable, empty and no-match
states are distinct. The picker uses a price-free projection; it never renders
sale price. Selection is explicit by item UUID. Inside the writer transaction,
Save checks the active parent and the selected title/code/unit/mode/group against
current catalog metadata, then calls P3A. A changed/deleted selection requires an
explicit reselection; no automatic replacement. Successful create retries skip
this metadata comparison to preserve P3A's original idempotency after rename/delete.

Wish defaults to Unknown with nullable unit and Discussing. Exact/Range require
a unit; custom nonblank units follow the shared domain. Edit exposes label/unit
only for wishes. Keeping, setting and clearing unit map to the typed
WishUnitChange API; Clear with Exact/Range is invalid. Catalog snapshot fields are
immutable. Deleted catalog selections remain visible and editable by snapshot.

Link opens the same picker and keeps original UUID/note/approval. Keep is enabled
only when unit/mode/quantity are compatible. Otherwise the user must explicitly
choose «Задати нову кількість» and confirm Replace. Unknown is not automatically
converted to derived, nor Range to Exact. Delete has a separate named confirmation
and uses the displayed ElementVersion with soft-delete CAS.

## Quantity input

`parseElementMilli` accepts comma/dot, 1..MAX_MILLI, at most three fractional
digits and integer pieces, without Double/rounding/scientific notation/overflow.
It converts whole and padded fraction separately; formatting trims only trailing
fractional zeros. The raw draft preserves `2,` and `2.` even while invalid.
Decimal keyboard plus explicit comma/dot insertion actions cover Android IMEs.
Zero/negative/equal or reversed range/overflow/fractional pieces disable Save with
feedback. Derived area/perimeter display only the basis, with no current geometry
numbers or sums. Approval remains separate from unplaced/0; no placement action.

## State, lifetime and conflicts

`MeasurementElementsModel` lives in a registry owned by the unlocked DB session.
Rotation reattaches to the same model, caller UUID, baseline version and in-flight
operation. Drafts are never serialized into saved state, routes, URI or logs and
are not promised across process death. Opening another route clears the prior
model; Android/Windows DB close clears decrypted state and cancels collectors.
No previous ceiling's list/draft is used as the initial state of another route.

Writes run off the UI thread and continue through configuration changes. Save
sets in-flight state synchronously before launching, blocks double taps, and
closes the modal only after repository success. An uncertain create result freezes
its payload and retains the same caller UUID for retry. RetryConflict/stale CAS
require explicit reload; local input remains until that decision. Reload warns
that it replaces the draft, performs a read and never replays a mutation.
ParentUnavailable, CatalogUnavailable, stale version, RetryConflict and unavailable
DB have distinct Ukrainian messages with no SQL, paths or keys.

Dialog Back/outside/Cancel and host Back/tab/route/window-close share the existing
navigation guard. Dirty input prompts Continue/Discard; a saving operation blocks
exit. Back first dismisses/prompts the modal, then the existing fullscreen handler
keeps its behavior. Discard may resume a requested tab/route change; no silent Save.

## Verification

Common tests cover decimal boundaries/round trips, intermediate input, three unit
intents and incompatible linking. `MeasurementElementsPresentationProof` drives
the production model/repository through CRUD, CAS/reload, Keep/Replace, derived
quantities, catalog disappearance, parent deletion, isolation and reopened state;
it is invoked by existing JVM, Android encrypted WAL, Windows SQLCipher and iOS
Simulator tests. Existing P3A migration/reopen/encryption tests remain enabled.
Desktop Compose scenes drive real actions in both themes; on Windows they use
real encrypted JDBC, and Linux uses SQLite. Required final-SHA CI run IDs and
results are tracked in the Draft PR. Linux cannot certify iOS/Windows or Android
emulator behavior; owner APK smoke remains pending until explicitly performed.

## Owner APK checklist (pending, light and dark)

- Objects → Kitchen: add six spotlights from the price picker; leave/reopen/restart.
- Repeat through the global Measurements tab and verify the same records.
- Wish Unknown without unit; edit 2,5 м.п.; range 4–6 шт.; clear unit with Unknown.
- Reject invalid numbers and fractional pieces; enter comma/dot on the real IME.
- Add area/perimeter basis; see no invented quantity, sum or placement action.
- Link compatible Keep; incompatible unit requires explicit Replace; cancel keeps wish.
- Edit note/approval; confirm delete; two ceilings/projects stay isolated.
- Dirty Back/outside/tab and rotation before/during Save; no duplicate or lost draft.
- Catalog rename/delete and concurrent edit: snapshot visible, clear feedback/reload.
- Scroll/IME/narrow layout, fullscreen/side selection, geometry edit; verify existing
  price CRUD, estimates/PDF and backup/restore.

No checklist item is considered owner-accepted solely because a scene/CI passed.
