# CMP UI Foundation — Issue #63

> **Статус 03.11.2026:** стабільні спільні CMP UI primitives прийняті в PR #65, використовуються Objects/Clients/Project Details/Measurement Details. Продовжуємо повторне використання у [Measurement Editor PR A/B](CMP_MEASUREMENT_EDITOR_PLAN.md); не дублювати header/fade/FAB/insets. Раніші приклади є контрактами, а не новим запитом на міграцію.

New CMP screens use `shared/commonMain/ui/CeilingsScreenScaffold`. Android,
Windows and a future runnable iOS host share the same components; no Android
Context or window API enters commonMain. This is an architectural extraction of
PR #62's Objects/Clients and Project Details UI, not a migration of other screens.
The pre-change inventory and CI baseline are in
[the audit](architecture/CMP_UI_FOUNDATION_AUDIT.md).

## Scaffold contract

- `content` receives **item padding**. Keep the scrolling viewport full-size;
  pass padding to `LazyColumn.contentPadding`, or put it **inside** verticalScroll.
- `header`, `pinned`, `bottomNavigation` are measured before `content` is composed.
  Their actual heights determine the first frame's padding, including font/width
  changes. No guessed sticky height and no onSizeChanged/padding feedback loop.
- `pinned` shields the measured region behind its foreground actions. `overlay`
  receives the same padding for suggestions, a rail, or a floating action.
- The default insets are `safeDrawing.union(ime)`: use the larger bottom inset,
  never add keyboard and navigation heights together. Horizontal safe areas are
  applied to foregrounds and list item padding. Insets are consumed for standard
  descendant inset modifiers.
- A **nested scaffold** supplied with host-computed insets/padding must pass
  `WindowInsets(0, 0, 0, 0)` and disable gradients owned by the host. Reading
  `asPaddingValues()` in a second scaffold does not automatically subtract its
  parent's reservation. Each edge and safe inset needs exactly one owner.
- The scaffold supplies background/onBackground through Surface. It does not
  create list state, nested-scroll connections, animations, navigation or snapping.

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewScreen(
    darkTheme: Boolean,
    rows: List<String>,
    destinations: List<AppDestination>,
    selectedId: String,
    onSelect: (String) -> Unit,
    onQuickCreate: (String) -> Unit
) {
    val listState = rememberLazyListState()
    CeilingsScreenScaffold(
        contentPadding = PaddingValues(16.dp),
        header = {
            AppHeader(
                collapsedFraction = 0f,
                shadeOpacity = 0.5f,
                statusBarHeight = 0.dp,
                windowInsets = WindowInsets(0, 0, 0, 0),
                drawGradient = false // Scaffold is the single fade owner.
            )
        },
        pinned = {
            // Any normally measured controls; no explicit sticky height.
            Text("Заголовок", Modifier.padding(12.dp))
        },
        bottomNavigation = {
            AppFloatingNavigation(
                selectedId = selectedId, destinations = destinations,
                darkTheme = darkTheme, onSelect = onSelect,
                onQuickCreate = onQuickCreate,
                navigationInset = 0.dp // Scaffold owns safe/IME insets.
            )
        }
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = padding) {
            items(rows) { Text(it) }
        }
    }
}
```

The example's navigation arguments come from the host's
existing navigation contract. Do not add a second outer statusBarsPadding,
imePadding, nav inset, opaque top strip, or card-local gradient.

## Edge gradients and pinned content

`theme/CeilingsEdgeGradient` draws only. Its bounds include the safe inset and
fade extent chosen by the owner. TOP goes 50% → 0%, BOTTOM goes 0% → 50% using
Material 3 surface in light/dark/System. `intensity` is clamped to 0..1;
`collapse` contracts the top fade to `safeInset`, without moving it offscreen.
`CeilingsEdgeDefaults.FadeHeight` is 36dp. The scaffold's `topGradient` drawing
slot receives its measured foreground-plus-fade height; normally keep its
default. Use `{}` when the edge is owned elsewhere. Objects uses the slot to
retain its original at-rest extent and 180ms fade expansion without a second
gradient. `edgeAlpha` exists for legacy AppHeader
chrome with its previously stronger collapsed shade; new screens keep 50%.

A fade has no pointer handler. Its transparent tail must never enlarge a hit
region. `CeilingsPinnedContent` wraps normally measured foreground content with
an input shield **behind** children. Buttons keep their click/ripple/semantics;
empty occupied space does not activate the content below. Outside the bounds,
content receives input normally. Set `interceptInput = false` while undocked.
Do not put a full-screen hit target around a small pinned row.

Project Details computes the occupied union of its measured address and real
56dp button targets, independently of its visual 72dp fade extent. The address
motion, 1/2-line alignment, status-card anchor, 30% downward-release settle and
new-touch cancellation all remain in ProjectDetailsScreen.

## Circular FABs

`CeilingsFab` uses the accepted two-radial-gradient drawBehind shadow with no
native animated elevation, polygonal outline or extra graphics layer:

| Profile | Drawing area | Touch circle | Visible circle | Ambient/directional radius |
| --- | --- | --- | --- | --- |
| CREATE | 76dp | 56dp | 56dp | 34/33dp |
| COMPACT | 56dp | 56dp | 42dp | 27/24dp |

The shadow is outside the circular clip, the bounded ripple is inside the
visible circle, and the click target is at least 48dp. Outline uses the common
Material outline role; compact FABs share navigation's light/dark alpha,
while CREATE retains the accepted 0.45 alpha. The caller owns `progress`, colors
and `enabled`: Objects retains its 190ms fade and Details its 280ms docking.
`enabled = false` removes click semantics immediately during a visual fade.

## Integration and verification

- Objects/Clients now uses measured header/pinned padding, a single top fade,
  the shared pinned shield and CREATE FAB. Search, suggestions, filters, rail,
  tab-keyed fresh scroll state and retained mounted state on Details Back remain
  screen-owned. Android's old 16dp outer horizontal inset is moved into items and
  overlays so the fade spans the full window without moving cards or actions;
  `horizontalOverlayInset` preserves the existing Android/Desktop host spacing.
- Details uses the same scaffold and COMPACT FAB. Its single moving title and
  existing LazyListState saver are retained. At rest the host AppHeader owns
  the top fade; once docked, Details takes over at the existing 0.95 threshold.
- Android retains transparent system windows, bar icon contrast, Back/gesture
  bridge and external intents; its bottom fade uses the shared primitive.
  Windows retains Escape, native window resize and its existing bottom-bar
  appearance (no additional system fade). iOS UI smoke needs runnable host #42.
- Common tests cover both fade directions/themes/intensity, measured padding,
  nested inset opt-out and FAB geometry. Existing title/snap tests still run.
  Desktop JVM scene tests exercise actual first-frame/resize layout, short/long
  lists, restored scroll/resize, forward/reverse drag and release, safe/IME union,
  pinned hit bounds, foreground clicks, disabled actions,
  circular FAB/ripple drawing and gradient pixels, with existing dependencies.
- Existing CI remains unchanged: Android unit/emulator/Debug/Release, Windows
  JVM/Desktop/encrypted CRUD, iOS framework/Simulator SQLCipher. Timings and
  final-SHA results are recorded in the Draft PR, not inferred from a local build.

Owner APK smoke remains required on the final SHA: Light/Dark/System, short/long
lists, one/two-line addresses, forward/fling/reverse scroll and downward-release
settle/cancellation, pinned gaps/actions, FAB shadow/ripple, search/suggestions,
filters/rail, IME, Back/Edit, restoration and narrow/landscape resize. Windows GUI
smoke is pending availability. Headless JVM rendering does not replace APK smoke.

No database/schema/migration, SQLCipher/WAL/key/backup, business calculations,
immutable estimate revision/PDF, routing system, Gradle versions or CI workflow
is changed. Other screens move to the foundation in separate scoped PRs.
