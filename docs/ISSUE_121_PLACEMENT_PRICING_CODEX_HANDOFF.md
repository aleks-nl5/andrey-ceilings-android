# Issue #121 — виправити INVALID_SOURCE після розміщення елементів на кресленні

**Джерело правди:** [issue #121](https://github.com/aleks-nl5/andrey-ceilings-android/issues/121), цей handoff і чинні `AGENTS.md` / P4A–P4C контракти. Цей документ — завдання незалежному розробнику Codex; початковий Draft PR містить тільки handoff, **ще не виправлення коду**.

## 0. Старт і межі

- База: **актуальний `main` ПІСЛЯ злиття PR #120** (18.11.2026, merge SHA `17650881737f290e0da9e07c4015faa4da8616bc`, SQLDelight + SQLCipher schema v19).
- Працювати **тільки в гілці `fix/issue-121-placement-live-pricing` / пов'язаному Draft PR**. Усі діагностика, тести, код, документація та CI fixes у цьому PR; не створювати конкурентну гілку або ще один PR. Не зливати і не міняти на Ready без власника.
- Завдання: прибрати **хибний блокер live pricing**, який з'являється при допустимому розміщенні на кресленні, без послаблення захисту від дійсно несумісних джерел.
- **PDF, формат frozen v1/vN, редагування друкованого документа, P4C4, прайс 2.0, UX редизайн, загальна реорганізація схем і зміна продуктового контракту кількості — НЕ входять у #121.** Не торкатися PR #120 та issue #119. Залишити #44 відкритою до P4C4.

## 1. Спостереження власника та рівень достовірності

**Підтверджено на Android APK:** після **переміщення вже розміщеної** *люстри* або *точкового світильника* на кресленні власник отримав текст:

> «Розрахунок недоступний: дані заміру несумісні; перевірте елементи та креслення».

Раніше власник також вказав на сценарій із *перешкодою*, але цей case ще потрібно ізолювати. Для POINT **Add працює**, а збій починається **після Move**; точний момент на осі draft/Save/reopen не визначений. **Люстра — лише протестований приклад, а не ознака, що дефект стосується тільки світильників:** інші **точкові**, **лінійні** та **площинні** типи могли не перевірятися. Так само не оголошувати їх зламаними без доказу.

Завдання Codex: **самостійно відтворити першопричину на synthetic data**, окремо перевірити всі класи елементів та описати, які саме гілки реально відтворили симптом. Якщо явне відтворення через вже існуючі production APIs не вдається, додати цільову діагностику та тест із реальними UI/DB переходами, не вигадуючи причини.

## 1A. Нове точне відтворення — ОСНОВНИЙ ПРІОРИТЕТ (власник, 18.11.2026)

Власник **повторно перевірив два незалежних типи товарів з POINT placement: люстру і точковий світильник**.

**Відтворювана послідовність:**
1. На вже збереженому замірі додати люстру / точковий світильник до переліку елементів і **розмістити точку на кресленні**.
2. Перевірити калькулятор: **усе правильно, сума рахується**. Зберегти необхідне розміщення через наявний UX.
3. Виділити **вже розміщену** точку, **перемістити її на інше допустиме місце всередині того самого незміненого контуру**. Перевірити стан розрахунку при drag/draft і **після завершення переміщення та явного Save** (власник не деталізував момент Save у другому проході — тест має розділити ці моменти).
4. Спостереження власника: **лише після Move** калькулятор показує «Розрахунок недоступний: дані заміру несумісні…». Обидва товари відтворюють симптом. POINT Add сам по собі **не** є дефектним.

**Наслідки для пріоритетів:** перш за все перевірити **Move після Add**, не лише Add. Якщо synthetic сценарій через прямий `PointPlacementRepository.movePoint()` лишається здоровим, **не робити висновок, що bug не відтворюється**: екран Details використовує спільний `MeasurementEditModel.finishPointDrag() → stagePoint(PointMountingEdit) → MeasurementEditStore.update() → CeilingEditRepository.save() → ceiling_operations` при збереженні креслення (замість старого direct point-only mutation). Доказ цього маршруту є в `MeasurementDetailsScreen.kt` та `docs/MEASUREMENT_AREA_IMPLEMENTATION_P3C5.md` (issue #102). Перевірити **обидва writer paths** окремо та Android gesture.

### Пріоритетна технічна гіпотеза з код-аналізу (НЕ підтверджена runtime test)

На **не зміненому контурі** `CeilingEditRepository.save()` формує `drawingJson == oldDrawingJson`. В поточному `BusinessSchema.sq` trigger `ceiling_operation_validate` / операційний apply **безумовно збільшує `room_measurements.updatedAt`** (around lines 871–875), але **деактивує/оновлює `ceiling_walls` тільки якщо змінився `drawingJson`** (around lines 876–881). При успішному point drag Save без редагування стін можливий стан `ceiling_walls.active = 1`, їх `geometryVersion = old measurement.updatedAt`, а `measurement.updatedAt = new version`. Далі `LivePricingReader.readInTransaction()` явно повертає `PricingReadResult.Unavailable(INVALID_SOURCE)`, якщо `wall.geometryVersion != measurement.updatedAt`. **Це сильна конкретна гіпотеза, яку слід першою довести RED тестом.**

Перший регресійний тест: snapshot до Add → після Add → після stage Move до Save → після atomic Save без зміни контуру → Reopen, з порівнянням measurement version, drawing JSON, active wall IDs/coords/versions та `LivePricingReader.read()` на кожному кроці. Коректне рішення повинно зберегти узгоджений version contract, **не відключаючи перевірку** та не створюючи нові wall IDs, коли геометрія не змінена; окремо перевірити, що в SQL `ceiling_wall_update_guard` дозволяє обрану CAS-операцію та схемні/migrated variants синхронні. Не робити blindly `wall.geometryVersion = updatedAt` без перевірки всіх downstream readers, контурної прив'язки та вже створених операцій receipts; мінімізувати зміну schema.

Окремо: прямий `PointPlacementRepository.movePoint()` використовує `point_position_operations` та point/element versions і сам по собі не змінює `room_measurements.updatedAt`. Якщо він працює, а атомарний `CeilingEditRepository.save` — ні, це точна локалізація cross-path defect. Перевірити після line/area-only edits, note-only save, confirmation без змін контуру і дійсної зміни контуру.

---

## 2. Критична різниця між двома станами UI

- **Точний напис «Розрахунок недоступний: …»** у `shared/.../measurement/MeasurementPricingUi.kt::MeasurementPricingSection` показується при `MeasurementPricingState.Unavailable(PricingProblemCode.INVALID_SOURCE)`. Звичайний екран заміру бере цей стан через `MeasurementElementsStore.observePricing() → observeQuotePricing() → LivePricingReader.read()`.
- **`LivePricingEngine.buildPreview()`** може додати `fatalProblems(INVALID_SOURCE)`, але при `PricingReadResult.Loaded` це **інший UI стан** («Розрахунок потребує перевірки; суми недоступні»). **Не змішувати ці дві гілки** під час root-cause аналізу. `LivePricingDraftAdapter.withValidatedDraft()` — окрема гілка для transient editor; перевірити лише якщо вона реально бере участь у відтворенні.
- `LivePricingReader.readInTransaction()` явно повертає `INVALID_SOURCE`, коли active `ceiling_walls` за кількістю/порядком/координатами/версією не відповідають `MeasurementGeometryCodec.read(drawingJson)` та `room_measurements.updatedAt`. **Пріоритетна гіпотеза, але не доведена причина:** під час point/area placement могли змінитися пов'язані версії/тригери або читатися некогерентні wall rows. Перевірити фактичну дельту до/після, а не просто прибрати перевірку версій.
- Ізолювати `PricingReadResult.Unavailable`, `PricingReadResult.Loaded` із fatal та `MeasurementPricingState.Loading` / `STALE_SOURCE`. З'ясувати, чи проблема відбувається у persisted reader, UI invalidation (flow), writer transaction або editor overlay.

## 3. Обов'язкова матриця діагностики

1. На **чистій тестовій БД** створити проект+замір із валідним captured contour та активним прайсом. Додати погоджені **CANVAS** (captured ceiling area) і **PRIMARY_PROFILE** (captured ceiling perimeter) з коректним catalog snapshot і цінами. Зафіксувати `LivePricingReader.read()`, `buildPreview()`, source signature, `room_measurements.updatedAt`, `ceiling_walls.geometryVersion`, active wall order/coords, ціни, кількості й totals **до** placement. Тестове наповнення, жодних особистих даних.
2. **POINT:** окрема catalog-linked позиція (напр., «люстра») з `PlacementKind.POINT`, `шт.`, `QuantityMode.MANUAL`, `ElementQuantity.Exact(1000)`, роль `ADDITIONAL_WORK`, валідний point строго всередині контуру. Дії: Add → перегляд калькулятора → Move → перегляд → Remove → перегляд; окремо перезапуск/reopen. Перевірити також APPROXIMATE vs EXACT/needsReview/unresolved, але **не** прирівнювати попереджувальний стан до пошкодження джерела.
3. **AREA / OBSTACLE:** annotation `AreaRole.OBSTACLE`, `elementUuid=null` (без каталогу); окремо прив'язана до catalog area позиція, polygon та circle, Add → Save → reopened → Edit/Move → Delete. Перевірити роль `VOID_MARKER` / `GENERAL_AREA` якщо ці шляхи є в UI. Не припускати, що obstacle автоматично віднімає площу полотна або створює фінансовий рядок — зберегти чинний контракт.
4. **LINE:** catalog-linked елемент `PlacementKind.LINE`, валідні відрізки, Add/Edit/Delete, пов'язана quantity/mounting. **Інші POINT-категорії** — перевірити загальну логіку незалежно від назви товару.
5. Для кожної гілки: **rectangle, L-shaped, довільний polygon**, принаймні позитивні кейси + негативні межі (поза контуром/торкання стіни, invalid shape, deleted element, stale contour/source, відсутній catalog selection, дубль primary role, несумісна одиниця). Виміряні геометричні значення повинні лишатися незмінні при *самому лише* placement.
6. Порівняти **збережений** details calc, fullscreen/edit preview, нову transient quote та quote save readiness. Якщо помилка лише в одному entrypoint, локалізувати відмінність UI/підписки/транзакції. Перевірити обидва маршрути: через глобальні «Заміри» й через «Об'єкт → Замір», якщо обидва доступні.
7. Для кожного кроку зафіксувати, де саме перша неправильна зміна: writer → SQL triggers/snapshot → pricing reader → engine → UI. У PR коротко описати root cause та чому валідна дія порушувала конкретний інваріант.

## 4. Точки входу коду

- `shared/src/commonMain/kotlin/com/andreyceilings/shared/livepricing/LivePricingReader.kt`: `readInTransaction()`, валідність `activeWalls`, версії, coherent SQL transaction.
- `shared/src/commonMain/kotlin/com/andreyceilings/shared/livepricing/LivePricingEngine.kt`: `validatePlacements()`, роль і quantity перевірки, serious fatal vs placement warnings.
- `shared/src/commonMain/kotlin/com/andreyceilings/shared/livepricing/LivePricingDraftAdapter.kt`: лише коли є підстава.
- `shared/src/commonMain/kotlin/com/andreyceilings/shared/measurement/MeasurementElementsStore.kt`: `savePoint()`, `observePricing()` / `observeQuotePricing()`; `MeasurementDetailsScreen.kt`, `MeasurementPricingUi.kt`, `MeasurementEditModel.kt`.
- `shared/src/commonMain/kotlin/com/andreyceilings/shared/db/{PointPlacementRepository,CeilingEditRepository,AreaFeatureRepository}.kt`.
- SQLDelight: `LivePricing.sq`, `PointPlacements.sq`, `AreaFeatures.sq`, `BusinessSchema.sq` + **актуальні** triggers/migrations за версією; `ceiling_walls` / `geometryVersion` / `room_measurements.updatedAt`. Якщо проблема schema-related, proof **fresh + migrated** database (schema v19) і обґрунтування чи треба нова міграція; не скидувати таблиці, не ламати SQLCipher/WAL/backup.
- Existing proofs: `LivePricingProof`, `PointPlacementProof`, `AreaFeatureProof`, `AreaFeatureTouchTest`, `LivePricingReaderTest`, SQLCipher Android tests, Desktop scene tests, iOS shared tests. Додати потрібні coverage у правильні тестові рівні.

## 5. Безпека й семантика, які не можна послаблювати

- Не видаляти/не bypass `activeWalls`, `validatePlacements`, CAS, signature/version, strict geometry bounds, deleted/tombstones, transaction receipts, unique IDs, role validation і frozen-quote invariants. Якщо є неправильне представлення валідного стану — коригувати writer/projection або узгоджено весь контракт із тестами; **не робити fail-open**.
- **Одна стеля = одне джерело поточного live calculation.** Розміщення — атрибут існуючої позиції/примітка, а не прихована друга платна позиція. Без зміни цін чи quantities через суто рух point/line/area, якщо користувач окремо не підтвердив зміну кількості за наявною функцією.
- Зберегти поштучні точні кількості `шт.` та `м²`/ `м.п.` і затверджену політику `13.7608 → 13.761 м²` (HALF_UP) без перерахунку frozen кошторисів. Money і billable milli — тільки exact `Long`; не користуватися `Double` для фінансових сум.
- `Unresolved` / needsReview / approximate можуть бути коректними станами з **типізованими попередженнями чи обмеженням готовності**, але не мають автоматично перетворювати всякий валідний замір на фатально некогерентний. **Справді невалідні** placements відхиляються.
- Не модифікувати frozen v1/v2/v3, status, selected revision, printable number, існуючі PDF/legacy export. Не міняти схему без доведеної причини. Жодних приватних даних, повних UUID, DB key у діагностиці/logcat.

## 6. Обов'язкові тести

1. **RED → GREEN root-cause test:** відтворити exact `Unavailable(INVALID_SOURCE)` через production writer та reader (не з fabricated in-memory source), показати відповідну некогерентну SQL row/умову. Після виправлення `PricingReadResult.Loaded` і відсутність несподіваних fatal. Якщо конкретний реальний кейс не вдається відтворити, в PR явно вказати межі доказу, а не заявляти bug fixed.
2. **Portable commonTest** для POINT/LINE/AREA/OBSTACLE та no-element annotation (із перевіркою скасувань, tombstone, quantity/placement summary); SQLCipher persistence. Різні види контуру, валідна/невалідна геометрія.
3. **Android instrumentation**: реальні production Compose жести в Details / Edit; перевірити видимий calc та Save/new quote; після reopen/restore. Довести, що помилку не приховали одним лише текстом.
4. **JVM/Windows desktop**: transaction coherence, WAL reader, scene tests для UI/drag; **iOS/macOS** shared SQLCipher/geometry/proofs за наявними target suites. Врахувати schema/migration parity, backup/import/export, збереження кошторису.
5. **Regression financial**: до/після placement підтверджені total cents і immutable snapshot v1/v2 не змінилися; усі негативні guard tests лишилися GREEN.
6. Всі залежні тести оновлювати разом зі зміною коду згідно `AGENTS.md` / [#118](https://github.com/aleks-nl5/andrey-ceilings-android/issues/118). Заборонено `@Ignore`, disable CI, видалення assertions, збільшення timeout замість усунення race/bug.

## 7. DoD / інтеграція

- Наявний нормальний workflow може Add/Move/Save/Reopen/Remove люстру, інші підтримувані POINT/LINE/AREA розміщення і standalone obstacle **без false `INVALID_SOURCE`**. Новий кошторис доступний за умови виконання існуючих mandatory pricing вимог; інші типізовані причини незавершеності не приховуються.
- PR містить відтворення, causal explanation, мінімальне стабільне виправлення, регресії, зазначені тести, повні same-final-SHA CI Android + Windows + iOS **SUCCESS** і актуальне резюме.
- Не торкатися Issue #44 або P4C4, не позначати PR Ready, не merge, не закривати #121 до незалежного code review та owner APK acceptance.
- Якщо потрібна участь власника: запросити **лише конкретні відсутні кроки відтворення** (екран, коли саме текст з'являється, тип/роль елемента) після спроби synthetic reproduction. Користувацька БД не є умовою початку роботи.

