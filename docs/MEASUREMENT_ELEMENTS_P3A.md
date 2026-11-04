# Елементи заміру: P3A / #87

Домен і persistence для #76/#78 після merged #86. P3B додасть CMP UI до наявного заміру; #44 визначить pricing, #71 — фізичні placements. Нових екранів/маршрутів у P3A немає. Acceptance відстежується у Draft PR; лише успішні final-SHA Android/Windows/iOS runs підтверджують платформи.

## Дані та інваріанти

`BusinessSchema.sq` і `6.sqm` створюють одну `measurement_elements` з UUID primary key, FK на `room_measurements.uuid` і nullable FK на `price_items.uuid`. Немає каскадного видалення чи унікальності (measurement, item): ту саму позицію можна вибрати кількома окремими записами. Індекс `(measurementUuid, deletedAt, createdAt, uuid)` забезпечує стабільний порядок. `measurement_element_records` — read-only projection з availability, без ціни.

`ElementSource.Wish` має label/note і nullable unit. Code/mode/placement snapshot у wish NULL; unit є спільним атрибутом кількості, тому для exact/range wish він обов'язковий. `CatalogSelection` фіксує UUID/code/label/unit/quantityMode/placementKind на момент вибору. Перейменування, переміщення, зміна ціни/параметрів каталогу не переписують snapshot. Видалений item/group повертається як `DELETED_OR_MISSING`; UUID ніколи не переприв'язується за назвою чи кодом. Сума, salePrice і фінансові рядки не зберігаються.

`ElementQuantity`: Unknown, Exact(milli), Range(minMilli,maxMilli), CeilingArea, CeilingPerimeter. Integer thousandths only; 2500 = 2.5 м.п. Немає Double/парсингу локалізованих чисел. Межа `1..9_000_000_000_000_000`; range строго min < max; `шт.` кратні 1000. Unknown/derived мають усі три числові колонки NULL. Derived — тільки джерело майбутньої кількості, без обчисленого результату. Обмеження дублюються в KMP і SQL CHECK, включно з SQLite integer type/overflow. Unit/mode rules збігаються з прайсом.

Approval discussing/approved незалежне від placement. Схема резервує unplaced/approximate/exact і placedQuantityMilli; API P3A взагалі не приймає placement у mutating calls і завжди створює unplaced/0. Немає anchors, координат або instance-таблиць. Геометрія, drawingJson, native precision, area/perimeter і measurement.updatedAt не змінюються операціями елемента.

## API та атомарність

`MeasurementElementRepository` у commonMain: createWish, createCatalogSelection, edit, linkWishToCatalog, delete; all/active/tombstones/find для sync/test і read-only `observeForMeasurement` з початковим порожнім списком. Active читання перевіряє UUID parent + active project. Administrative all/find/tombstones навмисно включають приховані/видалені записи. UUID є ідентичністю; локальний measurement ID не приймається цим API. Це локальна перевірка належності заміру проєкту, не multi-user authentication.

Кожна mutation бере SQLite writer lock (`claimElementWrite`, no-op UPDATE цієї ж таблиці) перед читанням у транзакції. Так два deferred JDBC writers не намагаються одночасно підвищити read lock. INSERT/UPDATE додатково мають SQL EXISTS active measurement/project; create/link також active item/group. Snapshot читається в цій самій транзакції. Edit/delete/link вимагають `ElementVersion(uuid, measurementUuid, expectedUpdatedAt)`, оновлюють `max(now, old+1)` і відмовляють на Long.MAX_VALUE. Конкурент програє typed StaleVersion без partial writes. Geometry CAS має окремий token.

Caller може згенерувати `newRecordUuid()` один раз і передавати той самий UUID у повторний create. Пропущений UUID означає нову логічну операцію. Канонізація: trim label/unit, note зберігається точно, now не є payload. Retry повертає активний існуючий запис лише за того самого parent/type/input; інший payload/tombstone → RetryConflict. Catalog retry порівнює вибраний UUID і заявлені дані, а не оновлений live snapshot. Price rename/delete не змінює успішний попередній create.

Edit не має setter-ів catalog identity/snapshot. `linkWishToCatalog` зберігає element UUID/note/approval; Keep відмовляє за несумісної одиниці/режиму, Replace вимагає явної нової кількості. Немає автоматичної конвертації чи switch для вже зв'язаного item. Typed failures: parent/catalog unavailable, invalid input/quantity, incompatible quantity, stale version, retry conflict, database unavailable. Немає журналювання notes/клієнтських даних/ключів.

`MeasurementRepository.delete` у спільній транзакції tombstones усі active children і parent. Failure parent update відкочує дітей. Видалений project приховує елементи з active Flow й блокує writes; політика project/estimate deletion не переписана.

## Міграції й backup

Schema v7 — additive `6.sqm` без UPDATE старих business rows і без нового seed. Існуючий marker не відновлює видалений прайс. Snapshot `6.db` отримано з історичного `5.db` + незміненого `5.sqm`; `verifySqlDelightMigration` перевіряє обидва baselines проти fresh v7. Runtime populated-v6 proof порівнює всі старі колонки через SQLite quote(), включно з ключами, UUID, precision/history та sqlite_sequence.

- Android: чинний `PriceCatalogSchema`, SQLCipher/WAL driver і physical schema/version transaction. Portable preflight перевіряє також нову таблицю/колонки/FK; контрольований upgrade копії v6 не змінює source backup. Фізичний VACUUM INTO snapshot містить елементи без нового формату/ключа.
- Windows: `openDesktopPriceCatalog` бере writer lock зі старим token, визначає v7 за фактичною структурою таблиці, CHECK/FK SQL, індексом і view (`MeasurementElementSchema.kt`). Канонічні SQL declarations відображають `6.sqm`; migration tests перевіряють fresh/upgrade. Часткова структура відхиляється. `user_version=7` записується в кінці успішної транзакції; restart не повторює DDL. Майбутня невідома версія відхиляється.
- iOS: `IosPriceCatalogSchema` використовує існуючу зовнішню фізичну SQLiter-транзакцію; жодного вкладеного BEGIN. Спільна DDL/upgrade логіка незмінна.

Failed migration injection між CREATE TABLE та CREATE INDEX доводить rollback schema/rows/user_version. Попередні v5→v6 Unicode/backfill proofs лишаються та довгий upgrade до v7 проходить тим самим маршрутом. Immutable estimates/PDF, SQLCipher keys/WAL та формат .stpb не змінені.

## Перевірки

Common quantity tests і `MeasurementElementProof` / `MeasurementElementMigrationProof` перевикористовуються на JDBC, Android emulator SQLCipher/WAL і iOS Simulator SQLCipher. Windows додатково запускає encrypted JDBC upgrade/restart/rollback, concurrent open, CAS та same/different-payload retry races. Тести охоплюють parent isolation, active/deleted parents, snapshots, explicit linking, unknown/range/derived, SQL CHECK/FK on/off guards, read-only Flow та atomic parent deletion. Android перевіряє encrypted snapshot/portable envelope і v6→v7 restore image; чинні internal/SAF restore tests збережені.

Локальна JVM перевірка не замінює mandatory `Build Android APK` і `Verify CMP KMP platforms`. Owner smoke: чинний застосунок/прайс/заміри/кошториси/backup/restore без регресій; UI P3A немає. Ручні checklist пункти власник приймає сам.
