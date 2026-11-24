> **24.11.2026 superseding owner decisions:** [P5D UX SPEC](P5D_UX_SPEC.md) and [technical audit](P5D_TECHNICAL_AUDIT.md), #164. P5D product design approved, implementation separately authorized. In conflicts use new contract (especially no independent choice UI, single approval per ceiling, typed discounts, PDF-only share). Historical discovery below retained.

# P5 / #95 — спільне UX-дослідження «Одна стеля» (після завершення P4)

> **Стан: DISCOVERY / PLAN ONLY, 19.11.2026.** Ніякого implementation approval чи готового UI-макета. Потрібно окреме погодження власником перед створенням вузьких реалізаційних issues/PR.

> **Пізніше погодження власника (23.11.2026):** цей документ — історія discovery, а не чинний макет. За конфлікту із [«Майстер насамперед»](architecture/MASTER_FIRST_UX_AND_DELIVERY.md) та актуальним [P5B UX spec](P5B_UX_SPEC.md) використовувати **пізніше явне owner-рішення**. Зокрема §6.1 «тап → відкрити параметри» замінено на «тап → підсвітити роботу у списку; відкрити параметри явною дією». Кроки планування тепер включають обов'язковий цілісний погоджений макет, фіксацію станів, незалежне UX-рев'ю та фізичне APK-приймання.

## 0. Вихідна точка та межі

- P4 завершено: [#44](https://github.com/aleks-nl5/andrey-ceilings-android/issues/44) closed/completed після owner-merged [PR #129](https://github.com/aleks-nl5/andrey-ceilings-android/pull/129), [аудит](https://github.com/aleks-nl5/andrey-ceilings-android/issues/44#issuecomment-7019985840). P4A–P4C4: live measurement pricing → transient quote → immutable vN → individual/consolidated frozen PDF.
- Канонічне UX tracking issue: [#95](https://github.com/aleks-nl5/andrey-ceilings-android/issues/95). Суміжні контракти: [#76](https://github.com/aleks-nl5/andrey-ceilings-android/issues/76) («одна стеля»), [#71](https://github.com/aleks-nl5/andrey-ceilings-android/issues/71) (POINT/LINE/AREA), [#70](https://github.com/aleks-nl5/andrey-ceilings-android/issues/70) (довільні кути), [#42](https://github.com/aleks-nl5/andrey-ceilings-android/issues/42) (hosts), [#114](https://github.com/aleks-nl5/andrey-ceilings-android/issues/114) (issuer). Це окремі workstreams, не приховані підзадачі редизайну.
- Не замінювати KMP source/measurement/price/estimate truth піксельними станами Canvas, не переписувати SQLCipher історію, immutable estimate snapshots, receipt/proof, фінанси чи правила округлення під UX.

## 1. Мета дослідження

Створити цілісну, зрозумілу майстру модель взаємодії: один замір/стеля як робочий простір, де можна швидко зафіксувати розміри, додати/знайти типізовані елементи й побажання, розмістити їх на кресленні зараз або пізніше, бачити актуальний KMP розрахунок та явно зберігати окремі пропозиції. Мінімум переходів/модалок, передбачувана робота з пальцем і точними числами на телефоні, повноцінна миша/клавіатура на desktop.

## 2. Почати з перевірених больових точок (#95 / PR #101)

1. LINE зараз вимагає багато етапів: вибір інструмента → дві точки → окреме застосування → сегмент/спосіб переміщення → два діалоги точних відстаней → збереження; спроєктувати коротшу й безпечну послідовність.
2. У normal/fullscreen інструменти розташовані й прокручуються по-різному; визначити зрозумілу контекстну палітру, не перекривати креслення на вузьких екранах.
3. Від першого дотику немає рухомого preview лінії; розрізнити «побудова», «попередній перегляд», «застосовано в чернетці», «записано в БД».
4. За маленького screen-space розміру лінії hit-test може стати нестабільним/NaN через нульову проєкційну довжину. Це окремий технічний дефект на етапі реалізації hit-testing; додати тест, не приховувати за UX.
5. Реальні end-to-end тести мають іти через production-контроли/жести, а не тільки виклики model API.

## 3. Користувацькі сценарії для спільного UX узгодження

- **А. Швидкий замір:** нова кімната → форма/контур → введення точних сторін (включно з дробовими) → збереження → повторне відкриття.
- **Б. Додаткові елементи:** знайти позицію з єдиного прайсу → ввести заявлену кількість/примітку → залишити нерозміщеною або поставити POINT/LINE/AREA → повторно редагувати без повторного введення.
- **В. Точне коригування:** select/drag/resize/anchor, numeric X/Y або стінні відстані для елементів; передбачувані pan/zoom/Back/Undo/Redo; normal ↔ fullscreen без втрати стану.
- **Г. Фінанси:** з того самого заміру відкрити live калькуляцію та transient пропозицію, розрізняти погоджені/обговорювані/невалідні роботи; explicit Save → immutable vN → PDF без зміни історії.

Для кожного сценарію: послідовність дій майстра, джерело істини, стани error/cancel/save/reopen, прототип на вузькому телефоні та desktop, тестовий чекліст і ризики.

## 4. Питання для обговорення з власником — не фіксувати рішення завчасно

- Який сценарій заощаджує найбільше дій: **контур**, **постановка елементів** чи **точне редагування**?
- Чи доречні контекстні вкладки «Контур / Елементи / Вартість», нижня панель/плаваюча палітра/інший патерн? Вибір тільки після реального мобільного прототипу.
- Чи додавати елемент зі списку без відкриття Canvas, а розміщення пропонувати необов'язковим другим кроком?
- Як точно виводити стан draft/committed/invalid/partially placed без зайвих сповіщень?
- Які мінімальні жести потрібні, щоб не конфліктувати з pan/pinch/selection на телефоні?

## 5. Порядок після спільного UX-рішення

1. Зафіксувати product journey та порівняти 1–2 прототипи **без змін бази/ціни/документів**.
2. Узгодити перший обмежений UX-slice (один робочий сценарій) і окреме developer-ready issue/acceptance.
3. Codex реалізує тільки погоджений slice у Draft PR від актуального `main`, з regression та Android/Windows/iOS CI.
4. Code review, owner APK smoke, виправлення і owner merge. Повторювати для наступних сценаріїв.

**Не відкривати implementation PR лише через цей документ.** Full UI redesign одним PR, нова фінансова модель, auto-save кошторисів, видалення історії, sync та зміна source-of-truth заборонені без окремих рішень.


## 6. Product decisions from owner — 19.11.2026, UX refinement after discussion

> This section supersedes exploratory UI alternatives above where they conflict. **Product design approved; no authorization to implement until a bounded Codex handoff is agreed.** The #95 tracking issue remains open.

### 6.1 One persistent «Стеля» workspace

- One continuously mounted canvas as primary workspace. «Контур / Елементи / Вартість» change contextual controls (including expandable bottom sheet), not screens nor draft/session. Preserve viewport, selection, input state and undo history across mode and normal/fullscreen transitions.
- Selecting any canvas element immediately shows its properties in the lower context panel. Clearing selection restores the element list. Avoid mandatory modal chains; exact numeric input still available.

### 6.2 A single implementation task for **all** POINT + LINE + AREA placement UX

- Owner explicitly requests **one delivery task for all three geometric element kinds**, not three independent implementation issues/PRs. Shared catalog selection, create/preview, select/deselect/hit-test, place/move/edit, wall-distance precision, contextual properties, partial/unplaced status, undo/redo, atomic Save/reopen. One acceptance matrix checks all kinds together. Codex may structure internal commits/test batches but not split acceptance by type.
- LINE drawing: two-endpoints via taps or drag; animated provisional rubber-band; when second point is accepted, line enters *in-memory draft* (not DB), remains selected, exact dimensions open in the bottom sheet. No extra «зберегти лінію в чернетці» confirmation.
- **Default smart snapping**: nearby relevant wall alignment/parallelism and optionally parallel to other line elements; project to exact world-mm without changing underlying wall, provide guide + snap reason. Hysteresis on/off thresholds for jitter, prevent accidental near-segment snap; user can temporarily disable. On short/overlapping lines, avoid NaN hit-tests. Preserve source declared quantity; visual placement count is not monetary quantity.
- **Polyline / routed line with turns** explicitly captured as **future extension**, *not* current stage, no speculative migration or silent concatenation of current LINE segments.

### 6.3 Better invalid-contour feedback (new explicit acceptance)

- Invalid entered dimensions must not yield only a disabled Save. Show **local, actionable, human-readable field errors** and visual diagnostics: problematic wall(s), vertex or corner in a contrasting style, reasonable hints on which measurement conflicts, while preserving latest valid preview or a clearly marked *diagnostic/incomplete draft*. No misleading authoritative area/cost for invalid geometry.
- Specific L-shape current implementation: `CeilingShapeTemplates` takes overall A/B and inner C/D; its require `C<A`, `D<B` currently fails with a generic English message, and `validateMeasurementCreate` returns `geometry=null`; create screen says drawing unavailable. Map these inequalities to affected input fields + relevant wall geometry. Validate and draw independently; never weaken exact mm closure/collision/positive-area contract.
- Other orthogonal and captured shapes: unclosed sides, self-intersection, invalid segment length, precision errors, overlaps/contradictions have accessible text as well as visual highlighting. CI screenshots/Compose touch tests cover an invalid L edit and correction.

### 6.4 Fast primary CANVAS and PRIMARY_PROFILE selection

- In «Елементи», **immediately beneath the drawing and at top of the element context panel**, always-visible compact fast selectors: **«Полотно»** and **«Основний профіль»**. Dropdown or searchable picker from existing *single* user-editable price catalog; show current choice and status, not duplicate hardcoded catalog items.
- Filter available candidates by typed roles and compatible units (CANVAS m²; PRIMARY_PROFILE м.п.) and show clear missing/retired/conflicting item state; do not quietly change an existing selection or invent zero price.
- Other POINT/LINE/AREA works via common «+ Додати». Keep primary choices in saved measurement/source binding and recompute dependent live estimate via the same KMP rules. For valid estimate readiness exactly one accepted canvas and one accepted primary profile are needed; historical frozen revisions remain untouched.

### 6.5a Owner staging update — 23.11.2026

In current P5B, ship **always-visible dynamic area and perimeter only**, as tiny icons + values in the existing transparent-gradient top header (not inside scrollable context sheet). Compute from current valid geometry before Save; no stale fallback on invalid drafts. In P5D, **add** live indicative cost as the third value in the same place, using a complete coherent in-memory draft-pricing read model; keep frozen estimates immutable. No cost placeholder in P5B. The original §6.5 goal is preserved but its implementation is **staged** per owner.

### 6.5 Real-time **in-memory draft pricing** is part of target UX

- Owner requests amounts update as dimension/element/quantity/primary selection changes, **before draft Save**. Do not fake this with current persisted-only `LivePricingReader.read`. Provide a validated pure-KMP draft projection/read-model shared with current pricing engine, bound to a coherent **draft revision + current catalog snapshot and explicit status**. Debounce UI only if necessary; correctness on every event wins over visual animation.
- Costs are **preview-only**, with clear pending/invalid/partial states, no DB-write, document number, persisted estimate, frozen revision or PDF. No display of stale amounts after invalid geometry, retired price, conflicting role/unit, stale catalog or calculation failure. Actual estimate Save keeps existing explicit DB-revalidation/atomic flow; do not change finance rules, precision, source of truth or historical documents.
- Keep support for unplaced but declared work, manual quantity independent of marker count, and price catalog changes surfaced as conflicts rather than silent repricing in a frozen estimate.

### 6.6 Vertex labels, A→B orientation and viewport/PDF fit

- All vertex letters must be laid out on the **exterior** of the actual polygon, including concave corners, with collision/edge avoidance, zoom/pan resilience and readable leader-lines or margins when necessary. Current `layoutMeasurementDrawingForProjectedPoints` tries screen quadrants without polygon-interior containment, leading to inward bottom labels. Pure layout logic shared by Canvas and relevant PDF drawing rendering; keep labels upright.
- Owner prefers **A at bottom-left, B above it**, making AB the left/upward starting side in the default canonical view, rather than currently top/horizontal. Implement as a **view-only transform** (or future explicitly chosen initial contour orientation) that preserves original vertex IDs, side mapping, wall anchor references, exact world-mm and undo/source identity. Need tests on rectangle, L, arbitrary orthogonal concave polygons and existing saved measurements.
- Owner suggests **longest wall horizontal** for fit. This can conflict with *AB bottom-left→up*; also on portrait phones and A4 portrait, longest-side-horizontal is not always the best aspect fit. **Design recommendation pending final review:** prioritize consistent default A→B view, aspect-preserving fit-to-visible-bounds, safe label margins; separately evaluate optional 90° display rotation based on viewport aspect ratio / explicit «Повернути вигляд» action. Do **not** silently reorder polygon vertices or rotate stored geometry. Current frozen drawings/PDF are immutable *data*; presentation changes on newly generated PDFs must be regression-tested and no previously exported PDF file rewritten.

### 6.7 One unified acceptance matrix, for later developer-ready issue

- Mobile narrow portrait, landscape, Android normal/fullscreen; Windows cursor/keyboard; iOS compile/host where available. Both light/dark themes and accessible semantics.
- Select canvas/profile → add each of POINT/LINE/AREA → draw/preview → snap/precise placement → move and reselect after other element move → edit numeric quantities → undo/redo → save atomically → reopen → ensure positions/labels/financial preview consistent.
- Invalid L input renders actionable wall highlight, no false total; correction restores valid preview; vertex labels remain exterior under zoom and concave L; default AB identity display; fit preserves labels; PDF pagination unaffected.
- At each significant mutation compare in-memory draft pricing with the canonical persisted calculation after explicit Save; no price changes to existing frozen revisions or PDF. Android/Windows/iOS CI, independent review, owner APK check before merge.

**Open UX review item:** confirm whether AB-left-up is the non-negotiable default, while any longest-wall-horizontal view should be an optional presentation control. No code yet.
