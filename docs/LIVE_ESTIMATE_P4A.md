# P4A — validated KMP live-pricing preview (read-only)

Issue [#104](https://github.com/aleks-nl5/andrey-ceilings-android/issues/104), parent #44; implementation in existing `feat/p4a-kmp-live-pricing` / [Draft PR #107](https://github.com/aleks-nl5/andrey-ceilings-android/pull/107). This contract incorporates the [final owner instruction 07.11.2026](https://github.com/aleks-nl5/andrey-ceilings-android/pull/107#issuecomment-6704739545), which **replaces earlier mixed live+frozen/projectWorks consolidation and estimate creation from the print dialog**.

## 0. Погоджена бізнес-модель: замір, індивідуальний документ, frozen зведення

1. **Замір → live калькулятор:** один конкретний замір, його validated geometry, усі активні елементи/quantities/approval та поточний каталог. Інформаційний read-only preview без кошторисних записів, номерів і PDF. Неповний замір допустимий, проблеми видимі.
2. **Замір → новий індивідуальний кошторис (P4C):** тільки explicit Create/Save у цьому замірі після readiness checks. Зберегти immutable revision із frozen цінами, кількостями, source/catalog identities та **точною версією креслення/схеми**, придатною для незалежного PDF/reprint після змін заміру чи прайсу.
3. **Об’єкт → друк зведеного (P4B/P4C):** діалог checkbox-вибору кімнат/замірів. Для кожної ВИБРАНОЇ кімнати — рівно один раніше збережений immutable individual revision. Невибрані кімнати, навіть без кошторисів, не блокують і не входять до суми. Порожня вибірка або вибрана кімната без revision блокує confirm/print; можна зняти вибір або перейти до заміру й свідомо створити документ. **Жодного автоматичного створення/Save в діалозі друку.**
4. **Зведений PDF — документальний пакет:** повні frozen рядки, креслення, details/omissions і subtotal кожної вибраної кімнати, один total = **SUM(saved individual document subtotals)**. Жодного повторного quantity×price/geometry розрахунку, поточного catalog validation, repricing, mutable drawing або зміни/перенумерації джерел. Новий consolidated estimate_series автоматично не створюється; пакет може бути одноразовим.
5. Кілька документів/версій кімнати потребують явного вибору ONE exact series/revision UUID; жодного silent latest. Зміни live даних дають **окремі попередження лише вибраних кімнат** шляхом порівняння provenance tokens: друкувати старий документ без змін або перейти до заміру й створити новий. Зміни чи недоступність поточного джерела самі не блокують історичний друк.

P4A реалізує тільки pure read-only API/validation. Змішаний ProjectPricingEngine видалено: live джерела, незбережені projectWorks, overrides чи current catalog не можуть входити до consolidated financial API.

## 0.1. Persistence та межі етапів

P4B тримає всі незбережені calculator/individual selections/overrides/ack у пам’яті. Preview, cancel, print-dialog selection або navigation не створюють estimate rows/series/number. P4C виконує explicit individual Save з atomic source/catalog/selection/override/ack recheck та commit. Reprint використовує committed snapshot і нічого не створює. Скасування OS-друку після explicit commit не скасовує збережений документ; dispatch не доводить фізичний друк.

## 0.2. Confirmed та potential

APPROVED з валідними source/quantity/price → confirmedTotalCents. DISCUSSING з priceable exact/derived quantity → окремий potentialTotalCents. Wish/Unknown/Range/missing catalog → typed unresolved, potentialIncomplete відповідно. Один рядок належить одному bucket. Potential не входить автоматично в selectedIncludedTotalCents або PDF; явне включення у майбутню individual пропозицію не змінює approval заміру. Frozen пакет бере саме збережений склад, не застосовує live bucket policy повторно.

## 0.3. Незалежні кошториси

Окремі індивідуальні кошториси достатні без alternative-proposal subsystem. Майбутній per-estimate workflow status незалежний від approval заміру, project status, інших документів та immutable revisions/PDF. Не додавати status UI/назви/DDL до P4A.

## 0.4. Замір описує роботи

Усі матеріали/полотно/профілі/світильники/ніші/інші роботи походять із catalog-linked MeasurementElement, включно з PlacementKind.NONE. Калькулятор читає всі активні elements одним coherent snapshot і не просить обирати полотно/профіль вдруге. Ніяких project-wide незбережених робіт у зведенні. Presets/seed/catalog expansion — окремий майбутній backlog.

## 0.5. Typed ролі та основні матеріали

At most one active CANVAS і PRIMARY_PROFILE на замір; дублікати — typed fatal blocker навіть з однаковим catalog UUID. Додаткові профілі/роботи не обмежені, їх кількості незалежно валідовані. v14 MeasurementElement не зберігає business role: pure API потребує достовірного source-bound typed binding або ROLE_UNDETERMINED. Ніколи не infer за title/code/group/order/quantity mode. Role writer/schema/UX поза P4A.

## 0.5a. Mandatory readiness тільки нового individual Save

Новий individual документ потребує рівно одну валідну фінансово включену **CONFIRMED CANVAS + PRIMARY_PROFILE**: exact catalog selection, compatible active item/group, quantity/unit/price та captured area/perimeter або валідний explicit billable override. Missing → MISSING_MANDATORY_CANVAS/PRIMARY_PROFILE; наявна, але unpriced/deleted/unresolved/discussing/excluded/incompatible → INVALID_MANDATORY_CANVAS/PRIMARY_PROFILE. Mandatory problems блокують full **і partial**. Empty/extras-only/potential-only не ready; валідна ціна 0.00 дозволена. Інший замір або frozen документ не може замінити відсутню базу цього заміру.

Це правило **не revalidation історичного кошторису**: frozen composition не перевіряє сьогоднішні ролі/ціни/одиниці. Вона перевіряє ownership, exact individual revision, immutable content/drawing proof та доступність frozen document. v14 не дає потрібного provenance/drawing proof: production adapter повертає PROVENANCE_MISSING, не фабрикує його з current measurement.

## 0.6. Частковий новий individual документ

Локальні optional unresolved роботи можуть бути свідомо пропущені лише після явного review/ack точного omitted list у P4B. Preview повертає priceableLines, unresolvedItems з source keys/reasons/buckets, fatalProblems, mandatoryProblems, full/partial candidate readiness. Жодних synthetic zero для відсутньої ціни/quantity. Fatal geometry/source/stale/role/DB/overflow блокує будь-яку фіналізацію.

IndividualEstimateSelection задає **один** project/measurement/composition UUID, source/catalog basis, expected preview signature та optional includedSourceKeys. IndividualEstimatePricing.previewSelection дає included/excluded із USER_EXCLUDED/UNRESOLVED, totals та signature. Виключені CONFIRMED роботи роблять документ partial; невключений advisory potential сам не робить partial. Повнота стосується саме цього заміру; інші кімнати поза scope.

Обчислити selection.scopeSignature, побудувати live preview з ним та composition UUID, потім прив’язати expectedPreviewSignature. Будь-яка зміна source/catalog/scope/override інвалідує acknowledgement. canRequestPartialAcknowledgement не є Save authorization. P4C atomic recheck фіксує partial flag, включені рядки, omissions/reasons і frozen drawing для незмінного reprint.

## 0.7. Джерельна та billable quantity

CANVAS бере captured area, PRIMARY_PROFILE — captured perimeter. За замовчуванням effectiveBillableQuantityMilli = sourceQuantityMilli. Explicit per-quote override змінює лише effective quantity; зберігає source і EXPLICIT_BILLABLE_OVERRIDE origin, не змінює geometry/ElementQuantity/placements/catalog і не додає другого рядка. Derived ElementQuantity.CeilingArea/Perimeter не підміняється Exact.

EstimateQuantityOverride прив’язаний до project/measurement/element/typed role, composition UUID, source/catalog/scope signatures. Тільки positive Long milli, відповідна unit, safe bounds; wrong identity/role/unit/duplicate/stale/zero/negative/overflow — typed blocker без fallback. Немає прихованого округлення, націнки чи вимоги override ≥ measured. Незалежні quotes можуть мати різні overrides; clear повертає measured quantity. Geometry/catalog/scope changes потребують recheck/ack. P4B — memory UX обох величин; P4C — frozen source/effective quantity, origin, price/total/provenance/note. Frozen consolidation ніяких overrides не приймає.

## 0.8. Майбутнє двонаправлене редагування геометрії: креслення ⇄ числові розміри (рішення власника, поза P4A)

**Архітектурна вимога на майбутнє:** майстер зможе **малювати або пересувати стіни/вершини на кресленні**, і ця дія змінюватиме **геометричні дані самого заміру** (допустимі довжини/кути/контур), а отже актуальну площу, периметр, відповідні derived quantities і **живий калькулятор**. Зворотно: **точне числове редагування виміряної довжини/кута** перебудовує той самий контур і відображення. Це два інтерфейси **однієї canonical KMP measurement geometry draft**, а не окремі, потенційно розсинхронізовані моделі «пікселі креслення» і «цифри заміру». Пов'язаний майбутній geometric/CMP solver — [issue #70](https://github.com/aleks-nl5/andrey-ceilings-android/issues/70).

- **Дві різні категорії чисел:** (1) *геометричні розміри* стін/вершин, площа й периметр — двосторонньо змінюються через дозволене редагування **геометрії заміру**; (2) *заявлені кількості робіт* (`ElementQuantity.Exact` світильників, введені лінійні метри чи площа додаткових робіт) — **незалежні від необов'язкової фізичної розкладки**, не переписуються автоматично від переміщення маркера, **доки для конкретної роботи не введено окремий явний типізований geometry-linked quantity mode**. Тобто правило «ручні кількості не змінювати приховано» **не забороняє** бінаправлене редагування довжин стін.
- **Геометрична валідність:** довільний drag у пікселях сам по собі **не доказ** виміряної фізичної відстані. Коли є достатні калібровані опори/сторони/кути й однозначні обмеження, solver перераховує точні канонічні мм; коли їх немає — показує tentative draft, `NEEDS_MEASUREMENT`/conflict і просить уточнення, **без вигаданих точних розмірів або silent snap**. Замкненість, самоперетини, стінні якорі, стани POINT/LINE/AREA та площа/периметр мають бути перевірені одним geometry edit transaction / CAS до persisted Save.
- **Поточне P4A:** підтримати **вхідний validated `MeasurementEditModel`/captured geometry snapshot** і детермінований повторний `buildPreview` після змін геометрії — derived `CeilingArea/CeilingPerimeter` перераховуються; manual `Exact` залишається; invalid/stale geometry дає typed problem, а не стару суму як актуальну. **Не** додавати зараз Canvas wall drawing/drag, solver #70, pixel-to-metric inference, двонаправлений UX чи нову SQL-схему.
- **Коригування billable quantity конкретного кошторису** (§0.7) **не є** зміною geometry і не переноситься у замір. Якщо геометрія була змінена числом або через майбутнє креслення, source quantity оновлюється, а наявний scoped `EstimateQuantityOverride` вимагає повторної валідації/підтвердження за новим sourceSignature: не видаляти й не залишати активним мовчки.
- **Історія:** раніше збережені `estimate_revisions` і PDF залишаються immutable; нова геометрія впливає лише на свіжі live previews / явно створені нові документи чи ревізії.
- **Майбутні acceptance tests (#70 / геометричний етап):** зміна довжини числом → deterministic new contour + нові area/perimeter + новий live quote; wall drag при достатній інформації → узгоджені числові виміри, area/perimeter і quote; недовизначена/невалідна геометрія → preview не показує старий total як чинний; unlinked manual light count / declared line length не змінюється від drag; scoped billable override позначається stale; source UUID/undo/redo/SQLCipher CAS і cross-platform behavior збережено.

**Розклад робіт:** §0.8 є **future-proofing contract**, а **не новим функціональним scope P4A**. P4A отримує валідовану геометрію й реагує на її зміну, але не створює редактор. Майбутній CMP/KMP геометричний етап, зв'язаний із #70, реалізує прямий та зворотний ввід. Написи/кнопки залишаються UX-рішеннями на пізніше.

## 1. Мета і межі

Одне deterministic commonMain ядро single-measurement live preview, pure individual selection та окрема frozen-only selected-room composition. Ніяких production estimate/PDF writes. Нові UI, writers, schema/seed/migrations, numbering, #106 parser, #70 editor/solver, #42 runnable iOS host — поза P4A.

## 2. Чинна архітектура, яку обов'язково повторно перевірити
- `measurement/MeasurementElement.kt`: `ElementSource.Wish` або `CatalogSelection(priceItemUuid,code,quantityMode,placementKind,availability)` **без sale price**; `ElementQuantity.Unknown/Exact(milli)/Range/CeilingArea/CeilingPerimeter`, approval, placement і placedQuantity незалежні.
- `measurement/MeasurementEditStore.kt` / `db/CeilingEditRepository.kt`: єдиний coherent measurement document та journal/CAS для contour+POINT+LINE+AREA; child-only зміни можуть не збільшувати `room_measurements.updatedAt`.
- `measurement/MeasurementGeometryCodec.kt`, `estimate/FrozenPolygonGeometry.kt`, `calculation/KmpCeilingCalculator.kt`: verified captured geometry, зафіксована точність, KMP geometry. Не створювати фіктивну точність для historical shapes.
- `prices/PriceCatalogModel.kt`, `prices/PriceCatalogStore.kt`, `db/BusinessRepositories.kt`: активні `price_items` + активні `price_groups`, UUID, immutable semantic code, unit, quantityMode, placementKind, sale price, updatedAt, soft delete.
- `estimate/ImmutableEstimateRepository.kt`, `estimate/EstimateRecalculationRepository.kt`: історичні серії/ревізії, snapshots і reprice previews **не** є P4A writer. Не змінювати public history semantics або `kmp-v1` golden vectors.
- Перед змінами прочитати `AGENTS.md`, roadmap, issue #44, поточні схеми `BusinessSchema.sq` та migration v14; орієнтуватися на *фактичний* main, не на застарілі архівні зрізи.

## 3. Домени і API

- LiveEstimateSource + ActiveCatalogSnapshot → LivePricingEngine.buildPreview: coherent source/geometry/elements/catalog, typed role bindings, scoped overrides; одна робота — один stable element source key/line, placements не додають рядків.
- LiveEstimatePreview та IndividualEstimatePreview: confirmed/potential, inclusion/exclusion/reasons, source/catalog/scope/preview tokens, full/partial readiness. У P4A жодного Save.
- ConsolidatedEstimateSelection: project UUID, ordered selectedMeasurementUuids та FrozenRoomRevisionRef(measurementUuid,seriesUuid,revisionUuid,expectedContentSignature). Рівно один ref на кожну вибрану кімнату.
- FrozenIndividualRevision: exact ownership/identity, complete FrozenIndividualDocument (rows, monetary subtotal/currency, details, FrozenDrawingSnapshot(formatVersion,payload)), saved source/catalog tokens та trusted immutable-store verification receipt. Content signature охоплює повний документ/креслення. Test fixtures не є production adapter.
- ConsolidatedEstimateEngine.buildPreview(selection,revisions): only frozen inputs. Empty/missing/ambiguous/duplicate/cross-project/deleted/stale/unverified/non-individual/missing drawing/invalid subtotal/mixed currencies/overflow блокують canCompose; unselected revisions ігноруються. SUM лише frozen subtotal cents, без recalculation. Frozen units і saved partial metadata зберігаються.
- frozenSourceWarnings(preview,currentBasis): окрема token comparison для selected sections, без pricing, зміни readiness або silent latest.

## 4. Правила кількості і ціни
1. **Тільки явно обраний каталог**: ідентичність за `priceItemUuid`, звірка code, unit, quantityMode, placementKind та активності item+group. Selection-time snapshot не містить ціни; для *нового* preview прочитати live sale price. Якщо позицію видалено/перенесено несумісно/змінено метадані — typed unresolved для локальної відсутньої/несумісної catalog item (повний кошторис не можна завершити; partial тільки з явним exclusion/ack за §0.6), без rebinding за title або code і без позичених дефолтів. Невалідні global input/version/DB states — fatal.
2. **Основні матеріали такі ж самі елементи заміру:** полотно, профіль та інші роботи мають бути *явно зафіксовані у замірі* як `MeasurementElement.CatalogSelection`; calculator їх **не вибирає вдруге**. `PlacementKind.NONE` підтримує unplaced матеріали. `CEILING_AREA` → валідована площа у м², `CEILING_PERIMETER` → валідований периметр у м.п. Для main `CANVAS`/`PRIMARY_PROFILE` за замовчуванням effective = captured, **але окремий explicitly confirmed billable quantity override конкретного кошторису** застосовується без зміни заміру (див. §0.7). Часткові кількості не можна безпідставно трактувати як повну площу/периметр. Відсутність полотна чи профілю не створює synthetic рядок/цінник, але блокує обидва readiness flags нового кошторису згідно з рішенням 07.11.2026 (§0.5a). Неповний замір можна переглядати й редагувати. Заборонено підставляти legacy `materialType` або `KmpCeilingCalculator.defaultPriceList()`.
3. **Measurement elements:** каталогові `APPROVED` з `Exact` і unit-compatible ціною формують максимум **один рядок на element UUID**, незалежно від числа фізичних placements. Для quantityMode `CEILING_AREA`/`CEILING_PERIMETER` — тільки відповідна derived quantity з captured geometry; `MANUAL`/`ENTERED_LENGTH` — лише явно підтверджена `Exact` з коректною одиницею (м.п. для entered length). `Wish`, `Unknown` та `Range` залишаються видимими як `NEEDS_INPUT/UNPRICED`, не перетворюються на нуль або середину діапазону; `DISCUSSING` з точною валідованою кількістю і ціною потрапляє **тільки в окремий potential bucket** згідно з §0.2. Вибір складових *документа* у P4B не є повторним вибором/додаванням елементів для калькулятора. Не допустити непомітного «готового» кошторису із пропущеними необхідними рядками.
4. **Placed vs declared:** `placedQuantityMilli`, фактичні point count/line length/area і `APPROXIMATE/EXACT` — контрольні величини та status/review, **не приховане джерело нової фінансової кількості**. Для LINE окремий measured length не переписує заявлену `Exact`; для AREA геометрична площа й declared quantity незалежні. Sticky unresolved/review є видимими: local item issues блокують **повний** кошторис, але допускають **лише свідомий частковий** кошторис за §0.6; fatal data/geometry/version conflicts блокують будь-яку фіналізацію. Preview не є записаним кошторисом.
5. **Annotations:** `AREA role=OBSTACLE/VOID_MARKER` без explicit compatible catalog element — лише позначки: **не віднімати** площу, не генерувати ціну, не змінювати периметр, не інтерпретувати отвір як 2D boolean cutout. Коди `light.*` тощо не створюють кошторисний рядок за самою назвою.
6. **Одиниці:** не змішувати `м²`, `м.п.`, `шт.`, `компл.`, `послуга` та custom units. Цілі `шт.`/комплекти — тільки за явно валідованими правилами; unsupported custom unit без явно визначеної семантики → blocker. Нульова **наявна** ціна продажу дозволена як справжнє 0, але missing/deleted/invalid не позначати нульовою.
7. **Математика:** quantity у canonical Long milli, sale price — перевірена кількість **копійок Long** (ціна у БД зараз REAL; convert only with strict 2-decimal cents check), підсумок рядка = half-away-from-zero round(cents × milli / 1000) **один раз за рядок**, total = точна сума цілих копійок. Guard на overflow, NaN, negative, overprecision; без `Double` для множення грошей і без неявного кількаразового округлення. Повага до зафіксованої geometry precision; не міняти її під час перерахунку.
8. **Детермінізм/ідемпотентність:** same validated source+catalog snapshot → byte-for-byte equivalent preview, stable line ordering/keys, без duplicate charge при повторному reload/Flow або overlapping POINT+LINE+AREA. Content token включає всі визначальні версії та зміни у child records, не лише parent `updatedAt`.

## 5. Persistence / concurrency

LivePricingReader.closeReads() — suspending admission/drain barrier. Один atomic CAS state припиняє admission і рахує всі accepted physical read/verify calls; finally release після завершення transaction/rollback. NonCancellable drain чекає success/failure навіть при cancellation closer. Caller має await перед DB close/restore/replacement; нові calls не починають SQL. Reader не володіє driver close; raw JDBC close може відкрити connection повторно.

Жодного INSERT/UPDATE/init, writer lock, schema v15 або історичного repricing. SQLCipher keys/WAL/backup/restore/receipts/numbering недоторкані. Tokens — opaque content, не захист майбутнього Save від TOCTOU; P4C потрібна write transaction recheck+commit.

## 6. Перевірки

- Common live geometry/quantity/unit/catalog/bucket/money/override tests: rectangle 4×3, polygon/precision, unplaced/NONE/Wish, exact/derived/Unknown/Range, deleted/metadata drift, zero price, deterministic one-element-one-line, overflow/half-away.
- Mandatory full/partial matrix: valid/zero-price pair, missing/unpriced/deleted/discussing/excluded pair, optional omissions, duplicate/undetermined roles; independent individual scopes.
- Frozen package: 2 selected rooms із 3 (unselected missing ignored), empty/selected missing/cross-project/duplicate/ambiguous fail; exact alternate revision only its room; frozen rows/drawing/subtotals immutable despite changed live tokens; warnings separate; exact sum/overflow; no live/catalog/projectWorks API; historical custom units/no current mandatory-role revalidation.
- Coherent SQLite WAL read under child/catalog changes, stale/closed failures, zero estimate writes; close-during-read/verify failure/cancellation drain; shared physical SQLCipher Android/Windows/iOS proof integrations.
- Local shared/JVM + Android unit/build/instrumentation compile + desktop + migration verification; fresh same-final-SHA Android instrumentation, Windows and iOS Simulator CI. Actual results/links in PR #107; owner APK/review remain separate gates.

## 7. Delivery

Continue existing feature branch/Draft PR #107; do not create a second PR. Update PR changes/tests/limits and issue #104 contract without closing it. Keep Draft pending independent review and owner acceptance. No automatic Ready, merge or claiming owner smoke. P4B/P4C UX, explicit individual persistence with frozen drawing and consolidated PDF package remain deferred acceptance.
