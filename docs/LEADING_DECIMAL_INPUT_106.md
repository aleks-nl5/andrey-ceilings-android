# Independent Codex handoff — #106: leading decimal input (.5, .007)

**Next active task after merged PR #109 (P4B1):** [Issue #106](https://github.com/aleks-nl5/andrey-ceilings-android/issues/106), separate Draft PR on `fix/shared-leading-decimal-input` based on `main` at merge SHA `977577f5b2739ea73c5d096009410ec1b7026615`. This is a short cross-platform bug fix before [P4B2 #110](https://github.com/aleks-nl5/andrey-ceilings-android/issues/110), which adds new per-quote decimal quantity entry. Work ONLY in this branch/PR. Read AGENTS.md, issue #106, existing parser/Compose UI, and actual current code.

## Business behavior (exact)

**Owner UX revision 07.11.2026:** [approved correction](https://github.com/aleks-nl5/andrey-ceilings-android/pull/111#issuecomment-6710030092) supersedes the earlier instruction to retain a literal leading separator in the UI. Users still do not type the initial zero: entering `.` or `,` displays `0.` or `0,` immediately, then typing `5` displays `0.5` or `0,5`. Initial paste `.007`/`,007` displays `0.007`/`0,007`. Signed `-.`/`-,` displays `-0.`/`-0,` only where negatives are already legal.

Apply this once to an edit that introduces a leading separator, including selection replacement and the comma action. Preserve selection/caret and map IME composition ranges around the inserted zero. Do not reinsert text after deletion, rewrite an existing number or normalize a cursor-only event. Deliver only the normalized value to the parent so its echo cannot revert the editor.

`0.`/`0,`/`-0.`/`-0,` remain PARTIAL, with Save/calculation disabled and no stale total. This is display normalization, not a numerical zero or a domain-parser change. Domain APIs still accept `.5`/`,5` and allowed signed/three-decimal values. Integer `шт.` controls hide the separator action and use a number keyboard; fractional quantities remain invalid.

Field-specific domain validation remains mandatory: sale-price max 2 decimals (so `.5` valid, `.007` invalid); quantities up to milli precision only where applicable; pieces integral; geometric step/bounds and signed coordinates preserved. `0.007m=7mm` only where precision/min constraints permit, not universal. Multiple separators, illegal signs, overflow and excessive decimals reject with explanation, never default zero or stale value.

## Implementation checkpoints

- Audit `ElementQuantityInput.kt::parseElementMilli`, `PointPositionInput.kt::parsePointMetres`, `PriceCatalogModel.kt::parseSalePrice`, measurement length/create/edit, LINE/AREA dimension fields, new P4B1 role editor quantity controls, and live/new quote input parser boundaries. Inventory current visible numeric fields in PR summary, indicating each precision/sign policy.
- Prefer a small shared KMP numeric-text lexical policy for *editable partial text* vs *committable decimal text*, while retaining each typed domain parser. No universal `Double` arithmetic for price/milli, no locale-specific fragile transformation, no coercion of partial/invalid text into the numerical value zero. Use the separate shared `TextFieldValue` initial-edit normalizer for the approved display zero.
- Review Compose text field `onValueChange`, `remember` keys, cursor/focus/IME, keyboard type and paste/backspace. Normalize an inserted leading separator exactly once; retain its separator and partial state. Do not reformat established numbers on each keystroke. Separate signed/unsigned lexical tests from domain commit validation.
- Unit/common + Compose scene regression for Android/Windows/iOS shared UI where runnable, including empty → `.` → displayed `0.` → `5` → `0.5`, comma typing/action → `0,`, signed `-.` → `-0.`, paste `.007` → `0.007`, selection replacement, IME ranges, backspace and unchanged nonempty `2.5`; `0.` trailing separator remains intermediate, `.007` price rejection, `шт.` fractional rejection, malformed `..5`/ `1..2` etc. Preserve existing `0.5` and `2,5`.
- No SQLDelight/SQLCipher schema/DDL, migrations, KMP money rounding/calc rule, geometry solver #70, new estimate writer, PDF or P4B2 UI. No unnecessary PR scope creep.
- Run same-final-head Android APK/unit/instrumentation, Windows SQLCipher/desktop CMP scenes and iOS Simulator CI. Owner Android APK smoke separately. Keep PR Draft until independent review + owner acceptance. Codex must not merge/close issue/mark Ready.

## Cross-stage dependencies

P4A #107 and P4B1 #109 are already merged. #106 fixes a widely used interaction before more inputs are added in [P4B2 #110](https://github.com/aleks-nl5/andrey-ceilings-android/issues/110). After #106 merge, create **a new branch and Draft PR** from updated `main` for #110, and only then implement the memory-only single-measurement individual quote composition. P4C immutable Save/frozen drawing/PDF and consolidated selected-room print remain strictly later; #44/#71/#76/#78 remain open.
