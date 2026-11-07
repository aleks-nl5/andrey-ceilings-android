# #106 implementation — PR #111

Work stays in `fix/shared-leading-decimal-input`, based on schema v15 after #109.

## Editing and commit

**Owner keypad revision:** [canonical comment](https://github.com/aleks-nl5/andrey-ceilings-android/pull/111#issuecomment-6711213696). Numeric editors filter invalid inserted characters, duplicate separators and disallowed signs before initial-zero normalization. A fully rejected insertion preserves the previous text and selection; accepted replacement maps caret and composition. Integer controls allow digits only. Fractional digit counts are still validated by each domain and are never silently rounded.

`shared/commonMain/input/DecimalTextPolicy.kt` distinguishes PARTIAL, COMPLETE and
INVALID lexical text, with explicit fractional precision, sign and whole-digit
limits. Commit-only normalization accepts `.5`/`,5` and signed `-.5`/`-,5` where
allowed. Domain parsers still decide positivity, bounds, units, geometry and save availability.

The [owner UX revision](https://github.com/aleks-nl5/andrey-ceilings-android/pull/111#issuecomment-6710030092) replaces the earlier literal-leading-separator display contract. `InitialDecimalEdit.kt::normalizeInitialDecimalEdit` inserts one display zero before a newly entered leading separator: `.` → `0.`, `,` → `0,`, paste `.007` → `0.007`; signed `-.`/`-,` → `-0.`/`-0,` where signed input is legal. This shared `TextFieldValue` transformation is independent of commit parsers and fractional precision.

The normalizer uses the previous selection (including whole-value replacement) or the changed text span to detect an inserted leading separator. It maps selection and IME composition endpoints around the zero and emits only the normalized value. Echo recompositions do not rewrite it. Cursor-only changes, edits inside an existing number, and deletion/backspace do not trigger a new zero insertion. Existing `2.5` stays `2.5`.

`DecimalInputField`, measurement create/edit `MeasurementLengthField`, full/quick `PriceEditorDialog`, and all legacy Android calculator decimal fields use this helper for typing/paste and separator actions. A comma replaces the current selection instead of appending behind the caret. Integer-only element quantities/ranges (`шт.`) and the four legacy piece-count fields use `allowDecimalSeparator=false`: no comma action, number keyboard, unchanged integral domain validation.

Only world-coordinate controls expose sign toggling. Price selects its initial amount once and retains the user's later caret/selection. `0.`/`0,`/`-0.`/`-0,` remain uncommittable PARTIAL text; neither Save nor a previous valid total can survive them. Inserting the display zero does not round, truncate, change bounds or make `0.007` a valid price. Leading decimals remain supported directly by domain APIs.

## Numeric field audit

All decimal editors above apply the initial display zero; the table describes unchanged commit rules.

| Visible input | Commit policy / existing domain checks |
| --- | --- |
| Element create/edit/link, including P4B1 roles: exact and min/max quantity | Positive integer thousandths, up to 3 decimal digits, existing MAX_MILLI and range rules; `шт.` must be integral and has no separator action. Derived area/perimeter modes have no numeric editor. |
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

- Common tests cover insertion/paste/replacement, reversed selection and IME composition ranges,
  unchanged deletion/caret events, both separators, partial text, signs, malformed input,
  price precision, integer pieces, bounds/overflow, measurement grid and circle
  even-mm rules. Existing parser regression suites remain enabled.
- Desktop `DecimalInputSceneTest` covers initial-zero display, selection, focus, separator
  replacement and recomposition in both themes, the production measurement length
  editor, both full/quick price dialogs and their partial Save guards, and integer controls. Existing element CRUD scene now persists `.007` as exactly
  7 milli; AREA scene clears and retypes a circle diameter.
- Android `DecimalInputTouchTest` uses the Compose input service for incremental
  typing, replacement/paste, clearing, selection and focus in both themes.
  `CalculatorNumericInputTest` covers the Android legacy text boundary.
- Baseline `54a33b8`: independent review PASS and Android #1172 / Windows+iOS #925 SUCCESS.
  The owner then requested the initial-display-zero UX above. New final-SHA review/CI
  and repeat owner APK acceptance are required; baseline acceptance does not cover this revision.
- Local and final-SHA CI results are recorded in the PR description. A local
  build is not evidence of Windows SQLCipher, iOS Simulator or owner acceptance.

## Owner APK acceptance (pending)

On the new final PR SHA, test light/dark themes: typed `.`/`,` displays `0.`/`0,`,
then `5` displays `0.5`/`0,5`; paste `.007` displays `0.007`; price rejects
`0.007`; signed `-.` displays `-0.`; circle clear → `.` → `0.` → `0.008`,
measurement grid/minimum rejection, comma-button insertion at a selected range,
backspace/paste, cursor/focus/IME, `0.` Save/total guards, hidden comma on `шт.`, and existing normal
`0.5`/`2,5` inputs. Keep PR Draft; owner decides merge and closes #106 afterward.

No schema, migration, SQLCipher/WAL/backup, pricing math, solver, immutable
estimate, PDF or P4B2 feature changes.
