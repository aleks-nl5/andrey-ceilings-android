# CMP UI Foundation audit — #63

Base: `main` at `bc49d4aa12094e5ff2d1966e9ed1945f4471e4f2`, after accepted PR #62.
Reviewed Issue #63, README, current roadmap, AppChrome, both Objects screens,
CeilingsTheme, Android AppRoot, Desktop Main and both CI workflows before code changes.

## Consolidate

| Existing implementation | Shared contract |
| --- | --- |
| AppHeader drawBehind, Objects sticky background, Details docked background, Android bottom fade | Theme-aware edge gradient primitive: top 50% → 0%, bottom 0% → 50%; no input handling. Exactly one owner per edge. Legacy screens retain their host shade intensity. |
| Host full-screen Box and screen content/foreground Boxes | Compact scaffold with content, header, pinned, bottom navigation and overlay slots. Measure foreground before composing list padding. Never pad the whole scrolling viewport vertically. |
| Fixed 97dp Objects controls and 72dp Details touch shield | Pinned slot measured from rendered content; intercept only that occupied area, behind foreground actions. Visual fade never expands the hit region. |
| Create FAB and Details' copied two-radial-gradient shadow | Shared shadow profiles preserving 76/56dp create and 56/42dp details geometry, existing alpha animation and bounded circular indication. |
| Repeated host padding arithmetic | Safe/IME inset contract, explicitly zero for nested scaffolds whose host already supplies content insets. |

## Keep screen/host specific

- Objects: tab-keyed fresh LazyListState on switching, retained mounted list on
  Details Back, search/focus/suggestions, filter rows, alphabet rail, swipe/editor
  state and 190ms FAB visibility behavior.
- Details: 280ms title docking, measured address wrapping/alignment, invisible
  layout placeholder, status-card anchor, downward-release/idle 30% settle and
  new-touch cancellation. None of these belong in the scaffold.
- Hosts: Android transparent system windows, icon contrast, predictive Back and
  gesture bridge, platform external actions; Desktop Escape, window and DB session.
- Routing, database, calculations, PDF and all other screens stay outside scope.

## Compatibility decisions

The host owns bottom navigation and its inset; nested screen scaffolds must not
add them again. The Objects screen owns its full-height top gradient, so AppHeader
must render title/actions without another gradient on that route. Details owns
the top gradient while docked; AppHeader keeps the established fading title at
rest. The visual fade and pointer interception are separate: only the measured
pinned content shields input. Windows currently has no bottom system gradient;
preserve that host choice rather than introduce a new visible effect.

## CI baseline (verified successful final PR #62 SHA `2ff27364`)

Durations below are job wall time; runner queue is excluded.

| Platform | Run | Job | Slowest step |
| --- | --- | --- | --- |
| Android | 41518605040 | 11m54s | SQLCipher emulator 4m41s (shared build 2m08s, release 1m34s) |
| Windows | 41518604824 | 3m25s | Desktop + JVM/encrypted CRUD 1m48s |
| iOS/macOS | 41518604824 | 9m48s | framework link 7m13s (Simulator 1m00s, JVM/Desktop 41s) |

The Android clean AVD cache hit (initialization skipped); Gradle cache outcomes
must be checked in logs for the final comparison. Existing workflows already
combine Windows JVM invocations and retain build caching/cancel-in-progress.
No new Gradle invocation, SDK download, dependency, tool version or workflow
change is needed for the foundation. Final branch CI timing belongs in the PR.
