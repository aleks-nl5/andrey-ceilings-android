# P7A — final acceptance and integration record

27.11.2026 · Libellaro · #49 · [PR #185](https://github.com/aleks-nl5/andrey-ceilings-android/pull/185)

Exact accepted pre-merge CI head `5045acf8c1cb3aab903f5dd456e33f2ac6f94e91` on `feat/p7a-work-calendar`; Android version 0.6.3, SQLDelight/SQLCipher v25 (additive v24→25). The owner merged PR #185 at `cb3632b3639793012d473ba28ece9d0f705549d9` without changing the tested production head.

## Verified CI

| Run | Evidence |
| --- | --- |
| [QUICK #298](https://github.com/aleks-nl5/andrey-ceilings-android/actions/runs/46061553788) | SUCCESS, all five jobs |
| [Android FULL #1393](https://github.com/aleks-nl5/andrey-ceilings-android/actions/runs/46061677242) | SUCCESS, attempt 1, all jobs and exact-head receipt; JVM, migration, SQLCipher instrumentation, isolated recovery, signing, production release open/recovery |
| [CMP FULL #1130](https://github.com/aleks-nl5/andrey-ceilings-android/actions/runs/46061700266) | SUCCESS, attempt 1, all jobs and exact-head receipt; Windows Desktop/JVM/SQLCipher, macOS JVM/Desktop, iOS Simulator SQLCipher |

Android FULL #1392 failed at the stale active-measurement assertion after deletion; corrected test distinguishes tombstone and surviving record. #1393 passes after this correction and the expanded Android v24→25 and production calendar backup/restore proofs. CMP #1129 succeeded on earlier head but is not final evidence.

## Acceptance and caveats

- Owner accepted physical UI/UX APK after QUICK #295 (`307cdd85`), including recurrence edit scope, calendar period scrolling and dashboard card outlines. Later changes fixed two independent review issues (frozen contact history and distant persisted exceptions), tests and version. No fresh owner physical test on final SHA asserted.
- Physical backup/restore and its performance investigation deferred explicitly by owner to settings redesign P8. Automated real encrypted migration/restore/recovery passed; do not mark physical scenario passed. No broad automated UI expansion.
- Earlier unpublished v25 draft before independent field/day override flags is not a supported upgrade source; never erase owner data without explicit consent. Supported v24→25 and existing backup guards retained.
- Detailed implementation: [P7A_IMPLEMENTATION.md](P7A_IMPLEMENTATION.md) in #185. Calendar core only; P7B external calendars, notifications and synchronization not implemented in P7A.

## Owner merge and follow-up

Owner explicitly directs completing P7A and preparing/merging documentation #186 without waiting for automatic post-merge FULL. [Android FULL #1394](https://github.com/aleks-nl5/andrey-ceilings-android/actions/runs/46067956751) and [CMP FULL #1131](https://github.com/aleks-nl5/andrey-ceilings-android/actions/runs/46067956960) on merge SHA were IN PROGRESS at verification, not passed. Do not cancel them; verify actual outcomes before the next production merge and investigate/fix or revert any real regression. This is a sequencing decision, not a change to CI/merge policy.

Keep issue #49 open. Merge docs-only #186 at owner's discretion. Next stage is [P7B technical contract](P7B_CODEX_HANDOFF.md), 36 approved decisions, one production Draft PR, strict reuse of accepted CMP UI practices. Start from latest main after documentation merge and separate owner authorization. No autonomous Codex run, Ready or merge.
