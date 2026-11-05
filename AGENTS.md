# Agent instructions — Andrey Ceilings

## Current milestone (05.11.2026 — P3C4 handoff; NOT implemented)

- **Accepted baseline:** P3C3 [PR #99](https://github.com/aleks-nl5/andrey-ceilings-android/pull/99) merged into `main` as `0d7f0d005d85ef01879a47ecf71f63756d0c150b` on 05.11.2026; schema v11. Owner retested final APK `b106215c` after DB migration/startup fix; Android and Windows/iOS final SHA gates successful. Prior P3A/P3B/P3C1/P3C2 also merged.
- **Current authorized task:** P3C4 [#100](https://github.com/aleks-nl5/andrey-ceilings-android/issues/100) / [implementation contract](docs/MEASUREMENT_LINE_PLACEMENTS_P3C4.md): **linear segments** on the existing shared CMP canvas, endpoint physical wall anchors, geometry-derived lengths, separate declared quantities, one atomic contour+point+line Save and safe SQLDelight/SQLCipher v11→v12 migration. **No implementation code has been delivered yet.**
- **Already created for Codex:** feature branch `feat/p3c4-linear-ceiling-features` and one Draft PR, initially docs-only. **Continue in the same branch/PR**; do not create another duplicate PR. Update PR with real code/tests/migration and truthful final-SHA links after actual implementation.
- **Next, separate:** P3C5 area/obstacle/void features (#71), then P4 live KMP pricing/new estimates (#44); full UX redesign #95 only after functional architecture. Solver #70, platform hosts #42, wider product #76 and price umbrella #78 remain separate open items. No automatic geometry subtraction.
- **Key invariants inherited from #99:** persistent `ceiling_walls` segment identities; measured two-wall or along/inward distances; typed exact vs approximate; points reviewed or sticky unresolved after geometry change; immutable ceiling operation receipts/results; CAS, recovery, source-version fingerprinting, v9/v10→v11 production compatibility and no reset. **Retain all of these** when extending with LINE. See [P3C3](docs/MEASUREMENT_WALL_ANCHORS_P3C3.md).
- **No auto-merge or premature closures:** Only the owner accepts APK and merges after actual Android/Windows/iOS final-SHA CI; do not mark manual checklist complete before confirmation.

## Read before making changes
- Review `README.md`, `docs/PROJECT_ROADMAP.md`, and the relevant GitHub Issue and architecture notes. Check the real code before trusting older documentation.
- Implement only the requested issue scope. Keep unrelated refactors and platform migrations out of the PR. If requirements are unclear, document the tradeoff instead of silently changing behavior.

## Architecture and data safety
- Prefer shared Kotlin Multiplatform logic and Compose Multiplatform UI in `shared/src/commonMain`; Android, Windows and (when runnable) iOS hosts should be thin platform adapters.
- Use existing SQLDelight/SQLCipher repositories, reactive Flow, domain models and navigation state. Do not create duplicate Android-only business logic or mock production data.
- Preserve UUID / soft-delete semantics, SQLCipher encryption and WAL, backup/export/restore, immutable estimate snapshots, price calculation precision, numbering, and PDF behavior. Change these only when explicitly scoped and tested.
- Never expose credentials, DB keys, private records, or unredacted personal information in routes, logs, build artifacts, PR bodies or tests.
- Treat downloaded Draft APK schemas as distributed contracts. A future SQL definition change needs a real migration; do not reuse a persisted schema version with incompatible guards. Preserve known source lineages explicitly when correcting an existing transition.
- Do not reintroduce Go, Room, or gomobile, or assume an iOS runnable UI exists. Refer to issue #42 for iOS host status.
- Keep platform-specific capabilities (e.g. Android gesture bridge, Keystore, PDF, storage permissions) in platform modules, with shared contracts where appropriate.

## Implementation and verification
- Prefer small, focused changes with matching `commonTest`/JVM tests and platform checks. Preserve currently working behavior on Android and Windows.
- The repo uses JDK 21 and Gradle 9.7.0 in CI (no checked-in Gradle wrapper). Example local checks, when tools are present: `gradle --no-daemon :shared:jvmTest :app:testDebugUnitTest` and `gradle --no-daemon :desktopApp:compileKotlin`.
- GitHub Actions `Build Android APK` and `Verify CMP KMP platforms` are mandatory before merge: Android build, unit/instrumentation tests, Windows/JVM, and iOS Simulator. Never claim a platform or smoke test passed unless it actually ran successfully.
- Android UI changes require owner-performed APK smoke on the final PR SHA in both light and dark themes. CI cannot replace this acceptance step.

## GitHub delivery contract
- Work on a feature branch and open a **Draft PR** into `main`, linking the issue; do not push directly to `main`.
- PR description: scope, behavior, key files, migrations/invariants, tests run and their results, CI links/status, manual APK smoke checklist, known limitations and follow-ups.
- Keep the PR Draft until the owner accepts the implementation and final-SHA checks pass. Do not auto-merge, close related issues, alter branch protection, or mark manual tests complete on the owner's behalf.
