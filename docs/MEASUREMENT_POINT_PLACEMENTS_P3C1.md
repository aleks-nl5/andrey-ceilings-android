# Точкові розміщення — P3C1 / #93

Перший функціональний зріз #71 після P3A #88 і P3B #91; пов'язаний із #76/#78.
Baseline: `main` `11b8af0b41f5607a1cbbb1c92596bfba5082547a` (документаційний #92 уже merged).
Приймання власником APK є окремим кроком; автоматизовані перевірки не означають його виконання.

## Звичайний CMP екран

Android «Заміри» і «Об'єкти», Windows Objects використовують існуючий
`MeasurementDetailsScreen` і перевірений `ElementRoute(projectId, measurementId, measurementUuid)`.
Секція показує `Розміщено: 2 з 6` і «Розмістити» для immutable snapshot
`CatalogSelection / POINT / шт. / Exact(1000*N)`. Ціни/суми не показуються.
Кнопка прокручує до креслення; там залишаються назва, лічильник, інструкція
про приблизність і «Готово». Нового route немає.

Тап строго всередині captured polygon додає один запис. Розширений hit target
24 dp має пріоритет над додаванням і вибирає найближчий існуючий marker,
підсвічуючи його та елемент. «Видалити позначку» вимагає окремого підтвердження.
Drag/pinch обробляються чинним gesture detector і скасовують tap після touch slop.
Поза mode збережено wall selection, side chips, scroll-wheel, keyboard, zoom/pan/fit.
Fullscreen відображає ті самі точки read-only і не пропонує placement editing.
Back спочатку завершує mode/selection; tab/route/close безпечно виходять із mode.

Wish, NONE/LINE/AREA, непідтверджена/діапазонна/derived кількість, дробові шт., інша
одиниця та historical `{}` не дають права запису; UI пояснює причину.
Заблокована БД/відсутній parent не дають активного редактора. Point repository
повторює всі перевірки незалежно від стану UI.

Нові starter каталоги мають POINT лише для `light.spot` і `light.chandelier`.
Seed marker незмінний: наявні каталоги не пересівуються. Для старого NONE користувач
явно змінює тип у прайсі й додає новий selection до заміру. Snapshot старого
selection зберігається; назва/код ніколи не визначають право placement.
Перейменування/видалення позиції прайсу не змінює наявних точок чи snapshot.

## Домен і транзакція

`PointPlacementRepository` — окремий KMP API: `observeForMeasurement`, `addPoint`,
`removePoint`, administrative find/tombstones. UUID генерується один раз на
логічний tap у `PointCommand.Add`. Невизначений результат зберігає ту саму команду
для явного retry, блокуючи нові тапи до retry або завершення mode.
Той самий UUID/payload повертає той самий active record; інші координати/element/token
або tombstone дають typed `RetryConflict`. Delete retry повертає tombstone.

У writer transaction спочатку береться чинний P3A lock, потім перевіряються:

- active measurement/project, UUID ownership і active element;
- `ElementVersion`, immutable POINT snapshot, Exact цілих шт. та capacity;
- актуальний measurement.updatedAt, captured codec і повна валідація polygon;
- integer координата в межах ±1 000 000 мм, strictly inside (межа відхиляється);
- унікальність UUID та `activeCount < requestedPieces`.

Один запис = 1000 milli. SQLite AFTER triggers атомарно перераховують summary
і підвищують ElementVersion: zero → UNPLACED, positive → APPROXIMATE, ніколи EXACT.
Кількість замовлення, price snapshot, measurement version і drawingJson при цьому
не змінюються. SQLDelight враховує trigger dependencies для Flow notifications.
UI отримує дані з БД; кількість не виводиться з намальованих символів.

Edit нижче active count або перехід Exact→Unknown/Range відхиляються typed
`PlacementsInUse`; SQL trigger захищає і низькорівневий element UPDATE.
Element/measurement/project soft-delete tombstone-ить children і обнуляє summary
в тій самій транзакції, включно з SQL UPDATE parent поза repository.
Point identity/coordinates незмінні; remove — CAS tombstone. Hard delete points
заборонено. Concurrent writers серіалізуються до читання інваріантів.

Typed point failures: ParentUnavailable, UnsupportedElement, StaleContour,
InvalidContour, OutsideContour, CapacityReached, StaleVersion, RetryConflict,
InvalidInput, DatabaseUnavailable. SQL/шляхи/ключі не показуються у UI.

## Консервативна політика контуру

Поки існує active marker, `point_contour_guard` забороняє фактичну зміну
drawingJson, shape, world bounds, area/perimeter та identity/ownership заміру.
Це діє на CAS editor, `MeasurementRepository.update`, Android DAO adapter і raw SQL.
Редактор бере writer lock до читання live document; point-add бере той самий lock.
Тому гонка contour-edit/point-add залишає одного переможця й не створює stale point.

Назва/примітка за незмінного контуру дозволені; редактор зберігає оригінальні
geometry bytes і REAL summaries для такого Save. Measurement.updatedAt є
консервативним token: навіть name-only edit вимагає перевідкриття placement mode
для наступного add. Існуючі точки від цього не переміщуються; видалення дозволене.
Повідомлення: «Спочатку приберіть розміщені позначки, потім змінюйте контур».
Невдалий Save залишає чернетку редактора та історію на екрані.

## Transform і точність

`measurementDrawingProjection` — єдиний fit affine transform для контуру та
markers у тому самому Canvas. `MeasurementViewport` додає спільні zoom/pan.
Pointer у локальних координатах Canvas проходить inverse viewport, inverse fit,
перевірку finite/bounds, округлення до найближчого integer mm і strict polygon test.
Density впливає лише на fit margins/hit radius; scroll/orientation змінюють local
layout. Збережено тільки world Long-mm; жодного pixel/Offset/pan/zoom у БД.
Це приблизні точки без snap і без зміни captured contour precision.
Integer cross products за наявних ±1e6 bounds безпечні для Long.

## SQLDelight v7 → v8, шифрування та міграції

`BusinessSchema.sq` + additive `7.sqm`: `point_placements(uuid PK, elementUuid FK,
xMm, yMm, contourUpdatedAt, createdAt, updatedAt, deletedAt)`. Measurement UUID
виводиться через element FK. Індекс `(elementUuid, deletedAt, createdAt, uuid)`
дає active/ordered lookup. CHECKs перевіряють SQLite integer types, bounds і timestamps.
`contourUpdatedAt` фіксує token на момент add; це provenance, без re-anchor.

`7.db` — authentic pre-P3C1 snapshot з `6.db` + незміненої `6.sqm`.
`verifySqlDelightMigration` порівнює 5/6/7 baselines із fresh v8.
`PriceCatalogSchema.version` походить із SQLDelight (8); Android helper і iOS
`IosPriceCatalogSchema` отримують його автоматично. Strict fingerprint перевіряє
таблицю, індекс і всі trigger-и. Android перевіряє його також при reopen.
Windows визначає v7/v8 за реальною структурою, відхиляє partial/future schema,
мігрує encrypted JDBC у writer transaction і записує user_version лише наприкінці.
iOS SQLiter callback перевикористовує його зовнішню фізичну транзакцію без BEGIN.
Runnable iOS host у цьому репозиторії ще відсутній (#42).

Міграція не оновлює старі business rows, snapshots, sequence, revisions чи seed marker.
Injection після CREATE TABLE/INDEX перед trigger-ами доводить rollback DDL/даних/version.
Populated-v7 proof порівнює кожне старе значення через SQLite quote(), включно з
P3A/B records і NONE snapshot, потім перевіряє point UUID/координати після reopen.
Android використовує реальний WAL helper і physical encrypted snapshot/portable
envelope/production restore; preflight перевіряє також нову таблицю. Ключі, WAL policy,
формат .stpb, SQLCipher і старі restore protections не змінені.

## Lifecycle і перевірки

Point model живе у чинному `MeasurementElementsModel`, у registry unlocked session.
Додавання/видалення реєструються синхронно в P3B beginWrite/endWrite; SQL працює на
Default у NonCancellable region. Подвійний tap Save, другий overlay editor і вихід
під час accepted write блокуються. Windows close drains ці самі completion-и;
Android ProductionDatabaseGate зберігає transaction leases при restore/close.
Після close model очищений і більше не приймає операцій. Зміна ceiling UUID створює
новий model із порожнім initial state. Приватні команди/чернетки не серіалізуються
у savedState/URI/logs.

Спільні proofs виконуються на JDBC, Android SQLCipher/WAL, Windows encrypted JDBC
та iOS Simulator: counts/Flow/retry/tombstones, eligibility/isolation, quantity/geometry
guards, empty/populated-v7 upgrade, rollback/reopen. JVM додає multi-connection races,
partial/future schema і close drain. Common geometry tests охоплюють rectangle/L/polygon,
concavity, edge/bounds, fit/density/orientation/zoom/pan та inverse precision.
CMP scene тестує звичайний details, tap/hit/delete confirmation, 2/6, Back,
fullscreen, pan і обидві теми. Final-SHA результати й CI links фіксуються у Draft PR.

## Окремий checklist APK власника — усі пункти pending

- [ ] Встановити APK фінального SHA, перевірити світлу та темну тему.
- [ ] На старій інсталяції явно налаштувати POINT у прайсі й додати новий selection;
  старий NONE показує пояснення і не конвертується.
- [ ] «Заміри» → Кухня → Exact(6 шт.) → Розмістити → дві точки: 2 з 6.
- [ ] Вийти/повернутися через «Об'єкти», перезапустити застосунок: ті самі точки.
- [ ] Видалити одну з підтвердженням: 1 з 6; скасувати підтвердження без змін.
- [ ] Додати до шести; сьома, межа та зовнішня точка відхиляються з поясненням.
- [ ] Дві стелі одного/різних об'єктів, Back/tab, rotation, scroll/IME, pinch/pan,
  fit і fullscreen: без витоку selection чи випадкових точок.
- [ ] Зменшення quantity нижче placed і зміна mode відхиляються без втрати даних.
- [ ] З marker змінити контур: readable refusal, чернетка залишається;
  name/notes-only Save працює; після вилучення всіх marker контур редагується.
- [ ] Backup/portable restore з точками, reopen, обидва Android входи.
- [ ] Прайс, геометрія/точність, кошториси, історія/PDF та резервування без регресій.

Обмеження P3C1: приблизні point placements; fullscreen markers read-only;
немає drag/move/rotate/resize, exact anchors, line/area/void, Undo/Redo placements,
live pricing, нових estimates/PDF чи runnable iOS UI. Наступні етапи — #71/#44/#42.
