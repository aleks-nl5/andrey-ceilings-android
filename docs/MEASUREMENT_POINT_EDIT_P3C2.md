# P3C2 — положення точок і read-only overlay редактора

Refs #96 / #71 / #93 / #95. Baseline: main `d71c04db871e54759c0e3c38362522015dc8fb09`,
PR #94 merged власником 04.11.2026 17:37 UTC. Початкова схема — v8.
Реалізація відстежується в [Draft PR #97](https://github.com/aleks-nl5/andrey-ceilings-android/pull/97).
Owner APK acceptance залишається окремим кроком; CI не замінює встановлення APK власником.

## Технічне рішення

- Один CMP Canvas і один gesture owner: selected-marker drag, empty-space pan або
  multi-pointer transform. Другий pointer скасовує point preview; release після
  pinch не створює/не переміщує точку. Preview приватний, швидкий і без SQL;
  один move intent на release, cancel/Back/route/close не записують preview.
- World Long-mm X/Y — єдине актуальне положення. Числовий ввід у метрах із комою/
  крапкою, до трьох десяткових знаків, signed X/Y без Double parsing. Відступи від
  явно вибраної стіни перетворюються за напрямом ребра й winding inward normal;
  після округлення показуються фактичні зворотні відстані. Довготривалих wall
  anchors немає: немає другого source of truth або обіцянки re-anchor.
- Адитивна v8→v9: precision кожного marker (старі — approximate) і receipts
  point_position_operations для CAS/idempotency переміщення. Receipt містить
  expected point/element/contour versions і payload; канонічна позиція читається
  тільки з point_placements. Контрольований trigger дозволяє coordinate update
  лише з відповідним валідованим receipt, незмінними UUID/element/createdAt/
  contour identity та чинними parent/count guards. Додавання/видалення й фінанси
  не переписуються. Numeric confirmation → exact; tap/free drag → approximate;
  aggregate exact лише коли всі active markers exact, count незалежний.
- Одна writer transaction: ownership, unlocked session, point/element CAS,
  поточний captured contour token, strict interior, receipt + coordinate update
  + summary/version. Retry з тим самим operation UUID не повторює move.
- Один останній confirmed move Undo/Redo в поточній session через той самий CAS;
  stale/route/restore/close очищують history, без автоматичного replay.
- MeasurementEditScreen отримує окремий атомарний read-only SQLDelight projection
  active parent + saved contour + points, scoped project/local ID/UUID/session.
  Loading/Missing/Unavailable не означають empty. Обидва режими редактора малюють
  points через той самий Canvas; invalid draft використовує реальний saved
  contour із явним fallback-поясненням. Змінений contour із points лишається
  заблокованим на рівні БД; name/notes-only Save працює.

## Власницький APK checklist — pending

- [ ] Обидві теми, Android «Заміри»/«Об'єкти»: два світильники, рух лише одного UUID.
- [ ] Empty-space pan, pinch, fit, wheel/keyboard, scroll/IME/rotation, fullscreen.
- [ ] Invalid/outside/boundary drag і cancel/Back/tab: preview відкочується без Save.
- [ ] Numeric X/Y (comma/dot/intermediate/signed) і відступи від вибраної стіни.
- [ ] Exact/approximate per marker, mixed aggregate, unchanged count, Undo/Redo CAS.
- [ ] Звичайний/fullscreen «Редагувати замір»: усі точки, dirty/invalid draft fallback.
- [ ] Name/notes-only Save; blocked changed contour з утриманням чернетки.
- [ ] Дві стелі, reopen/restart, encrypted backup/restore без втрати/дублів.
- [ ] Прайс, captured precision, історичні кошториси/PDF без регресій.

## Перевірки та межі

Потрібні common numeric/geometry/CAS proofs, CMP real gestures в обох темах,
fresh/populated v8→v9/rollback/reopen, Android SQLCipher WAL/backup/restore,
Windows encrypted JDBC, iOS Simulator encrypted proof + framework compilation.
Final-SHA CI links і результати — у Draft PR після фактичного виконання.
Fullscreen details та contour editor — read-only для точок. Немає UX redesign,
нових routes, line/area/void, auto-snap/re-anchor, financial/PDF changes чи iOS host.

## Реалізовані user journeys та code paths

У звичайних details тап вибирає конкретний point UUID. Drag починається тільки
з уже виділеної позначки; move preview від початкової world-позиції зберігає
відступ пальця від центру. Порожнє поле належить pan. Pinch негайно скасовує
point preview і передає жест viewport; release такого жесту не є placement tap.
`MeasurementDrawing` має один awaitEachGesture arbiter; pointer disposal/Back/tab
скасовують preview. На release виконується одна writer transaction. Outside/
boundary/stale rollback повертає persisted position. Округлений вільний drag
перетворює exact на approximate, навіть якщо integer-mm позиція не змінилася.

«Редагувати положення» відкриває один session-only CMP dialog (`PointPositionEditor`).
X/Y приймають signed comma/dot metres, до 3 знаків; incomplete `2,` залишається
в полі і не дає Save. Парсинг і форматування — integer mm, bound ±1 000 000 мм.
Альтернатива «Від стіни»: явний segment AB/BC/..., along від початку ребра й inward
за winding normal. Along обмежений довжиною сегмента, inward > 0; після mm-rounding
повторюється strict interior. Reverse distances показуються до 0.001 мм, щоб
було видно вплив округлення похилого ребра. Canonical X/Y — до 1 мм. Статус exact
означає явно підтверджене числове положення центра, без заяви про нову точність
контуру чи фізично виміряні відступи. Wall reference не зберігається в БД.

Один останній confirmed move можна Undo/Redo; команди мають новий operation UUID
і поточні expected point/element versions. Stale history очищується з поясненням,
route/close/restore не відтворюють її. Після невизначеного commit результату payload
і UUID заморожені до explicit retry/reload/discard. Dirty numeric Back/tab має
Continue/Discard; in-flight write блокує вихід і drain-иться чинним механізмом P3B.

## Схема v9 та межа запису

`point_placements.precision` має CHECK approximate/exact і default approximate;
`lastMoveUuid` посилається на immutable `point_position_operations`. Receipt містить
UUID, point UUID, expected point/element versions, current measurement token,
цільові Long X/Y, precision та createdAt. Він є записом intent/idempotency, а не
альтернативним актуальним положенням. Старі marker UUID/coords/timestamps зберігаються
без UPDATE. Point identity, createdAt і початковий contour provenance незмінні.

`PointPlacementRepository.movePoint` бере writer lock до читання, перевіряє active
parent/ownership/quantity/count, point+element CAS і current captured contour,
KMP strict interior, вставляє receipt й оновлює той самий point UUID. SQLite
`point_operation_insert_guard` додатково перевіряє версії/parent/count та strict
interior за vertices з drawingJson через обмежений recursive CTE й built-in JSON.
Прямі invalid SQL receipts або point UPDATE без узгодженого receipt відхиляються.
SQL integer cross products за ±1e6 bounds не переповнюють Long. Parity proof
перевіряє KMP та SQL на rectangle/L/concavity/diagonal/negative coordinates/
reversed winding/boundary/outside/overflow.

SQLDelight отримав лише build-time JSON signatures module **2.4.1**, узгоджений із
чинним runtime/dialect 2.4.1; версії не змінювались. JSON runtime забезпечують
наявні SQLCipher libraries; encrypted platform proofs виконують реальний guard.
Insert/remove/move triggers перераховують count*1000, aggregate exact тільки для
всіх active exact points, approximate за змішаного набору, unplaced при нулі;
ElementVersion монотонний. Quantity request, price snapshot і geometry не змінюються.
Старий `point_contour_guard` лишився чинним для будь-якого SQL UPDATE контуру.

`8.sqm`, historical `8.db`, fresh schema й strict v8/v9 fingerprints узгоджені.
Windows probe відрізняє v8/v9 за реальними columns+objects, partial/future не
ремонтуються автоматично. Android/iOS callbacks використовують ту саму адитивну
міграцію; iOS — зовнішню SQLiter transaction. Injection після ALTER columns до
CREATE receipts доводить rollback schema/rows/user_version. Backup preflight
перевіряє precision/receipt table; physical snapshot, SQLCipher keys/WAL та portable
формат не змінені. Receipts зберігаються разом із point tombstones.

## Read-only редактор заміру

`MeasurementEditStore.observePoints` виконує один SQLDelight SELECT active project,
measurement(local ID + UUID), captured contour і active points. Окремий session
close signal зупиняє reads; UI починає з Loading у route/model key. Missing і
Unavailable мають власні пояснення, жодна помилка читання не означає «немає точок».
Overlay не залежить від MeasurementElementsModel: scene закриває попередню модель
і перевіряє, що редактор продовжує бачити всі точки.

Нормальний і fullscreen editor передають markers у той самий MeasurementDrawing.
За valid changed draft вони залишаються у world-mm; fit включає і draft contour,
і saved points, тому shrink не обрізає позначки. За invalid draft показується
реальний saved contour з явним fallback-поясненням. Немає зміни геометрії або
placement Save з цього екрана. Name/notes-only Save працює; actual contour Save
з active points блокується SQLite/repository, чернетка та її Undo/Redo лишаються.

## Фактичні перевірки

Локальний Linux/JDK 21/Gradle 9.7.0 suite під час реалізації: 273 shared JVM cases,
26 desktop/CMP scenes, migration verification та Android instrumentation Kotlin
compilation. Нові common proofs виконуються також у наявних Android encrypted
WAL/restore, Windows encrypted JDBC і iOS Simulator suites. Це не твердження про
успіх цих платформ до завершення їхніх CI.

Фінальні SHA, точні результати та прямі success-run links публікуються в
[PR #97](https://github.com/aleks-nl5/andrey-ceilings-android/pull/97). iOS перевіряє
framework і Simulator SQLCipher/domain; runnable iOS UI не додано. Жоден manual
checklist пункт не вважається виконаним на підставі scene/CI.
