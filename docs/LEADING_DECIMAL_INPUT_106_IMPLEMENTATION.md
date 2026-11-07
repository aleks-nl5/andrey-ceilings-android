# #106 implementation — PR #111

Work stays in `fix/shared-leading-decimal-input`, based on schema v15 after #109.

## Editing and commit

`shared/commonMain/input/DecimalTextPolicy.kt` distinguishes PARTIAL, COMPLETE and
INVALID lexical text, with explicit fractional precision, sign and whole-digit
limits. Commit-only normalization accepts `.5`/`,5` and signed `-.5`/`-,5` where
allowed. Editors retain raw text, selection and IME composition. Neither partial
nor malformed text becomes zero or the preceding valid value. Domain parsers
still decide positivity, bounds, units, geometry and save availability.

`DecimalInputField` preserves `TextFieldValue` across echo recompositions and
inserts a separator at the selection, including an empty field. It supplies a
decimal keyboard hint and an accessible comma action independent of the keyboard.
Only world-coordinate controls expose sign toggling. The existing measurement
length field uses the same separator helper. The price dialog preserves its
field value and no longer forces select-all after every pointer gesture; users
can place the caret and retain their selection.

## Numeric field audit

| Visible input | Commit policy / existing domain checks |
| --- | --- |
| Element create/edit/link, including P4B1 roles: exact and min/max quantity | Positive integer thousandths, up to 3 decimal digits, existing MAX_MILLI and range rules; `шт.` must be integral. Derived area/perimeter modes have no numeric editor. |
| Catalog full and quick sale price | Unsigned, zero allowed, at most 9 whole and 2 fractional digits. `.5` valid; `.007` invalid. Existing price storage and money math unchanged. |
| POINT world X/Y | Signed exact mm from metres, up to 3 fractional digits, coordinate bound and strict polygon interior. |
| POINT distances from wall | Same exact-metre parser; existing side bounds, nonnegative along distance and positive inward distance enforced. No sign toggle. |
| Wall mounting for POINT, LINE endpoints and AREA vertices/centre | Exact mm from metres, up to 3 fractional digits; existing wall solution/interior/distance constraints. No sign toggle. |
| Measurement create/edit template dimensions and orthogonal sides | Positive exact mm, max 1000 m and captured measurement grid. Existing exact trailing-zero suffix rule retained (`123.001000` is `123001` mm); no nonzero digit rounded. A bare trailing separator is partial and cannot save. |
| AREA circle create/edit diameter | Up to 3 fractional digits, 2..2,000,000 mm and even mm (whole-mm radius), then existing shape/interior checks. `.007` rejected; `.008` gives radius 4 mm. Clearing the edit field keeps it visible. |
| LINE optional width in mm | Unchanged integer-only field, 1..1,000,000 mm or blank. Decimal metres policy does not apply. |
| Existing Android calculator: shape dimensions and orthogonal side lengths | Shared lexical policy up to 3 fractional digits before the existing geometry engine and captured-grid behavior. No coercion of malformed text to zero. |
| Existing Android calculator: canvas/profile price | Shared catalog price parser, at most 2 fractional digits. |
| Existing Android calculator: optional work lengths / counts | Unsigned, up to 3 fractional digits for lengths, integral numeric values for pieces (including exact zero suffixes), zero allowed. Invalid/partial raw text invalidates calculation and Save. Raw option text is a memoization key, so the preceding valid estimate cannot remain available. Existing calculation/rounding rules unchanged. |
| Live measurement price preview | Read-only; no editable numeric field. P4B2 quote overrides remain out of scope. |

All newly shared controls/parsers live in commonMain for Android, Windows and
iOS; platform compilation must be confirmed by the final-SHA CI. There is no runnable iOS UI host in the current repository; Simulator CI
exercises common parser contracts and the existing native tests, not iOS IME UI.
Phone/contact text and read-only numeric displays are not decimal domain editors.

## Verification

- Common tests cover both separators, partial text, signs, malformed input,
  price precision, integer pieces, bounds/overflow, measurement grid and circle
  even-mm rules. Existing parser regression suites remain enabled.
- Desktop `DecimalInputSceneTest` covers raw text, selection, focus, separator
  replacement and recomposition in both themes, plus the production price dialog
  and its Save guard. Existing element CRUD scene now persists `.007` as exactly
  7 milli; AREA scene clears and retypes a circle diameter.
- Android `DecimalInputTouchTest` uses the Compose input service for incremental
  typing, replacement/paste, clearing, selection and focus in both themes.
  `CalculatorNumericInputTest` covers the Android legacy text boundary.
- Independent static review: PASS after fixing raw-option memoization and the
  disappearing empty circle-diameter editor. Reviewer did not run platform tests.
- Local and final-SHA CI results are recorded in the PR description. A local
  build is not evidence of Windows SQLCipher, iOS Simulator or owner acceptance.

## Owner APK acceptance (pending)

On the final PR SHA, test light/dark themes: quantity `.5` and `.007`, price
`.5` with `.007` rejection, signed coordinates, circle clear → `.` → `.008`,
measurement grid/minimum rejection, comma-button insertion at a selected range,
backspace/paste, cursor/focus/IME, invalid-text Save guards and existing normal
`0.5`/`2,5` inputs. Keep PR Draft; owner decides merge and closes #106 afterward.

No schema, migration, SQLCipher/WAL/backup, pricing math, solver, immutable
estimate, PDF or P4B2 feature changes.
