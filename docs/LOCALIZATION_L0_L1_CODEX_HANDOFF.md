# Codex handoff — Localization L0/L1 before P5B

> **Authorized by owner, 22 Nov 2026.** Start from [issue #154](https://github.com/aleks-nl5/andrey-ceilings-android/issues/154), which is the authoritative **scope + acceptance**. Work **only** in this Draft PR / branch `feat/l10n-l0-l1-foundation`, based on latest `main`. Do not merge. The owner will do independent review and APK acceptance. **This initial commit is planning only; no production implementation or successful CI has been claimed.**

## Entry points and priorities

Read, in this order:

1. `AGENTS.md` top localization contract and the newest owner decisions, `docs/architecture/LOCALIZATION_STRATEGY.md`, `docs/PROJECT_ROADMAP.md`, issue #154;
2. `build.gradle.kts`, `shared/build.gradle.kts` (Kotlin 2.4.21, Compose 1.12.2, AGP 9.3.1 observed on 22 Nov), `app/build.gradle.kts`, `desktopApp/build.gradle.kts`, relevant iOS hosts;
3. `app/src/main/java/com/andreyceilings/app/AppRoot.kt`, `app/src/main/java/com/andreyceilings/app/ui/screens/SettingsScreen.kt`, the shared CMP root/navigation, `shared/src/commonMain`, `ci/impact.py`, current workflow gate tests;
4. Official current documentation: https://kotlinlang.org/docs/multiplatform/compose-multiplatform-resources-setup.html , https://kotlinlang.org/docs/multiplatform/compose-resource-environment.html and https://kotlinlang.org/docs/multiplatform/compose-localize-strings.html .

The latest **merged PR #152** makes automatic FULL execute **after merging to main only**; FAST/TARGETED during PR. Older roadmap/AGENTS text suggesting unconditional FULL upon Ready or before every owner merge is historical. Owner explicitly does **not** want Branch Protection/required checks or auto-merge. Verify actual workflows, do not bypass tests.

## L0 (implement first, commit separately if appropriate)

- Create a reproducible **inventory and checked-in baseline** of hardcoded user-facing strings across shared Android desktop iOS plus PDF/notifications/accessibility; classify current UI, domain code, test/log, seed catalog, persistent user data, technical IDs.
- Document precise counts by surface/path, scan methodology, known false positives/limitations, and prioritization for future L2/L3. Make inventory script and unit tests deterministic (no flaky locale, no dependence on host file order).
- Distinguish *source-code UI literals* from **database-managed price item/group names**. Never auto-translate user content.

## L1 (small real vertical slice)

- Configure resources in `:shared`: `implementation(compose.components.resources)`, `src/commonMain/composeResources/values/strings.xml` with **Ukrainian defaults**. Verify `androidLibrary { androidResources.enable = true }` with AGP 9.3.1 and generated accessors; prove Android + JVM + iOS compile. Use actual supported resource APIs, not inferred API syntax from old snippets.
- Ship shared `AppLanguagePreference` state (SYSTEM / EXPLICIT("uk")), validation/normalization of BCP-47, deterministic fallback Ukrainian for unsupported system locales; Android/desktop/iOS platform preference storage, no DB schema changes. Unsupported translation languages remain **unavailable** in product UI.
- Connect resource locale environment to Compose application root; only a handful of common chrome/text/semantics plus one parameterized/plural example need move in L1. Preserve focus, editor drafts, viewport and Save/Back; use proven platform implementations, avoid uncontrolled global locale side effects or changes to numeric parser.
- If this slice includes a user-facing language setting, permit **System / Українська** only and integrate narrowly with the existing Settings entry point; do not redesign Settings #145 or fabricate English/Polish translations.
- Add baseline-aware CI guard to reject newly introduced **user-facing** hardcoded literals in new/changed UI files, not old baseline or true technical constants, without forcing FULL on unrelated PRs. Cover changed files, renames/deletes, escaping, comments, whitespace and exceptions with tests.

## Nonnegotiable protection

- Ukrainian labels are **presentation**; ID, wireId, UUID, prices, quantities, dates in storage, proof, status and enum codes are **not language labels**.
- Names of all price **items and editable groups, including seed catalog**, belong to the user and must **never** be translated or mutated on locale change, nor in individual/consolidated PDF lines.
- Do not change financial math, currency, rounding, SQLCipher/SQLDelight/WAL, backup/restore, immutable estimate revisions, PDF snapshots, keys, known measurement/geometry behavior or Android precision/decimal input `.5`.
- No broad L2 screen migration, no second supported locale, no P5B/P5C/P5D feature changes, no host promotion to full Windows/iOS releases.

## Acceptance / report in this Draft PR

- Machine-readable L0 baseline report; small real shared localization path on Android/Windows; iOS target compile + adapter tests; locale preference persistence and fallback tests; resource key+format/plural tests.
- Test that locale switching leaves **price catalog exact names**, unchanged frozen financial values/doc row names, backup/restore and live source untouched.
- Test baseline guard positive+negative examples. Avoid removing or silently skipping security/integration tests.
- Give results with **exact SHA** and explicitly list any unrun platform suites. Existing PR FAST/TARGETED and post-merge FULL policy applies; manual FULL may be requested when shared/platform risk needs it.
- Independent code review, owner APK check, then owner decides Ready and merge. **Never merge automatically**.

## What is next (do NOT do it in this PR)

After L0/L1 acceptance, resume one unified **P5B** POINT/LINE/AREA UX implementation from [#95](https://github.com/aleks-nl5/andrey-ceilings-android/issues/95) / [#71](https://github.com/aleks-nl5/andrey-ceilings-android/issues/71), under its own developer-ready handoff and Draft PR. P5C/P5D and Android-first Settings/Calendar/Object remain separate.
