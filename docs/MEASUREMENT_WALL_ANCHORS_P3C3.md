# P3C3 — монтажні прив'язки та спільний редактор

Refs #98 / #71 / #76 / #95. Baseline main `c34a2d35348a9534a620728be3cec0a1e85ae566`;
PR #97 merged власником 04.11.2026 19:42:48 UTC. Початкова схема v9.

**05.11.2026: owner APK на `9aa8db4` не відкрив БД. Це блокер приймання.**
Нижче — початковий контракт P3C3; поточний startup fix використовує schema v11
зі збереженням уже розданих v10 даних. Нові CI й повторне owner APK acceptance
не можна підміняти попередніми успішними перевірками.

## Рішення до реалізації

- Один MeasurementEditModel володіє чернеткою контуру, точок, опор і review.
  Existing CMP Canvas/gesture arbiter використовуються normal/fullscreen;
  drag/поля/Undo/Redo змінюють лише чернетку. Один явний Save записує весь набір.
- Default монтажний ввід: дві directed walls і два перпендикулярні Long-mm
  відступи. Розв'язок — перетин inward offset lines з перевіркою finite feet,
  winding, strict interior, near-parallel і фактичних відстаней після mm rounding.
  Альтернатива — одна стіна, along/inward. X/Y залишаються внутрішніми.
- Persistent wall UUID відокремлено від labels/ordinal/inputId. Міграція створює
  ідентичності існуючих ребер без anchors для старих точок. Length/turn edits
  з незмінним topology зберігають directed identity; shape/topology changes без
  доведеної відповідності створюють нові IDs, старі refs стають unresolved.
- Схема v9→v10: additive metadata точок, directed wall records і immutable
  whole-ceiling operation receipts. Один journal INSERT перевіряє measurement,
  повний point/element CAS, geometry/wall mapping та valid/unresolved results;
  SQL trigger атомарно застосовує contour+walls+points+summaries. Прямі contour
  або anchored-point UPDATE без відповідного receipt fail closed. Старі v9 move
  receipts/UUID/coords/timestamps/tombstones зберігаються.
- Anchor є source of truth для виміряної точки; X/Y — перевірена похідна.
  Drag явно прибирає active anchor і ставить approximate. Unresolved зберігає
  anchor, last known coords і geometry context; він резервує capacity, але не
  входить у placed count. Exact/approximate і needsReview — незалежні стани.
- Зміна геометрії перераховує лише доведені refs та ставить durable review.
  Legacy unanchored зберігає world-mm, без вигаданих замірів. «Пізніше» не
  підтверджує розміщення; «Перевірити» проходить affected UUID з CAS.
- SQLCipher keys/WAL/portable format, captured precision, estimates/PDF та прайс
  не змінюються. Invalid draft показує saved valid contour і точки; no-op/name/
  notes не змінюють anchors/review. Stale/close/restore не запускають replay.

## Перевірки й доставка

План: common geometry/remap/rounding, repository atomic CAS/retry/count/rollback,
direct SQL bypass, populated v9→v10 fresh equality, Android encrypted WAL/backup,
Windows strict encrypted JDBC, iOS Simulator SQLCipher/framework, CMP real
touch/mouse/co-edit/review scenes в обох темах. Фактичні результати й final-SHA CI
будуть наведені у Draft PR після виконання.

## Owner APK checklist — pending

- [ ] Обидві теми та Android «Заміри»/«Об'єкти», normal/fullscreen co-edit.
- [ ] 4×3, 2 з 6: AB 0,60 м + AD 1,50 м, Save/restart з тими самими UUID/anchors.
- [ ] AB 4→5, preview/Save, незмінні монтажні відступи, «Перевірити / Пізніше».
- [ ] Пізніше/restart/backup; послідовна перевірка й підтвердження кожної точки.
- [ ] Shrink/topology/lost wall → unresolved без втрати anchor/coords/reservation.
- [ ] Mixed valid/unresolved count; нові точки не обходять reserved capacity.
- [ ] Drag скидає active anchor; повторний measured input повертає exact link.
- [ ] Undo/Redo, Cancel/Back/tab/rotation/IME, stale/close/restore, no partial Save.
- [ ] Прайс, historical estimates/PDF, captured geometry precision без регресій.

Межі: без повного UX redesign, нових hosts, line/area/void або constraint solver.
Ручне APK приймання P3C3 виконує власник; приймання P3C2 його не замінює.

## Реалізація і source of truth

`MeasurementEditModel` має одну composite history для contour draft і explicit
`PointMountingEdit` за point UUID. Числові mounting inputs зберігають comma/dot raw
text, зокрема `0,`; застосування лише staging. Existing gesture arbiter працює
normal/fullscreen: selected marker drag, empty pan, pinch cancel. Drag знімає
активний anchor і ставить approximate; Undo повертає і координати, і anchor у
чернетці. Розмірні лінії й підсвічені опорні стіни використовують той самий transform.
Невалідний контур показує actual saved geometry/points з fallback-поясненням.

Persistent `ceiling_walls` містить UUID, measurement UUID, directed ordinal і
верифіковані endpoints/version. Ordinal — лише поточне відображення, не identity.
Старі walls зберігаються inactive, їх UUID ніколи не перевикористовуються.
Length/turn changes із тією самою послідовністю session sides лишають IDs.
Shape/add/remove changes створюють окрему generation; Undo/Redo/rotation несуть
її разом із чернеткою. Недоведені refs стають unresolved, без пошуку «найближчої» стіни.

`WallAnchor` має TWO_WALLS або ALONG_INWARD, wall UUIDs і Long-mm distances.
Two-wall solver використовує inward unit normals з winding і відхиляє нестійкий
near-parallel determinant; перевіряє bounds, finite feet, strict interior та
видимість perpendicular dimension всередині polygon. Відступ не може перетинати
увігнутий виріз/іншу стіну. Actual rounded distances відрізняються не більш ніж
на 1 мм; UI не обіцяє вищої точності. X/Y не є монтажною формою.

Legacy v9 points зберігають precision, UUID, coords, timestamps, tombstones та
position receipts. Їх metadata default `{}` означає unanchored, без виміряних
опор. Після geometry change координати лишаються world-mm; inside → valid +
needsReview, outside/edge → unresolved + needsReview. Anchored marker remap
використовує тільки ті самі proven walls/distances. Unresolved зберігає старі
coords, anchor та last valid geometry context. Нові недопустимі measured inputs
не застосовуються й не підмінюють стару збережену прив'язку.

## Атомарна схема v10

Адитивна `9.sqm`, historical `9.db`: `point_placements.mountingJson` та
`lastCeilingEditUuid`, `ceiling_walls`, immutable `ceiling_operations` /
`ceiling_operation_results` і SQL views/
triggers для валідації. Room/measurement business row не отримує нового column;
жодна історична міграція не редагується. Migration wall IDs створюються окремо,
без UPDATE старих points/business rows. SQLCipher physical snapshots включають
нові records; portable format/keys/WAL не змінені.

Один `CeilingEditRepository.save` спочатку бере writer lock, потім перевіряє
measurement/active parent CAS, повний point set і всі element tokens. Immutable
receipt містить однаковий request payload для explicit retry. Один INSERT
валідує геометрію/джерельні параметри/precision/bounds/simple polygon/totals,
wall mapping, point/element versions, typed metadata та anchor distances/visibility.
SQL не залежить від optional math extension: squared distance guards і bounded
Newton roots перевіряють фізичний результат та perimeter.

Той самий SQL statement застосовує measurement, walls, усі point changes і
summaries. Failures після contour/walls до point update відкотять весь INSERT,
включно з receipt. Прямі UPDATE контуру з active points або anchor metadata без
operation відхиляються. Старий writer залишається заблокованим; новий contour
path дозволений лише через receipt, без вимоги видаляти points. Повтор того самого
receipt після невизначеного commit читає результат, не виконує move ще раз;
пізніша whole-ceiling operation робить старий point-move retry stale.

Count/soft-delete guards враховують усі active reservations при quantity limit.
`placedQuantityMilli` та placementStatus використовують тільки valid points;
unresolved показано окремо. Exact/approximate і review незалежні. Journal прямо
оновлює element summary також для правильних SQLDelight Flow invalidations після
вкладених point triggers. Tombstones зберігають історію anchors/receipts.

## Review, session, dirty та відновлення

Geometry Save з affected points зберігає needsReview до показу dialog. «Пізніше»
записує durable deferredAt через наступний CAS receipt; перезапуск/backup цього
не підтверджує. У деталях є «Перевірити (N)». Review підсвічує кожний UUID, показує
опори/відстані; unresolved не підтверджується до нового коректного measured input.
Підтвердження має confirmedAt і modifiedVersion; badge зникає лише після всіх
підтверджень. Name/notes-only/no-op не змінюють point records або review.

Save реєструється до worker scheduling, один NonCancellable worker завершує
транзакцію й сигналить completion без очікування Main. Windows close drain-ить
accepted writes та overlay reads; Android лишається під ProductionDatabaseGate.
Закрита сесія не приймає нових writes. Stale/conflict зберігає чернетку, вимагає
explicit reload. Невизначена спроба фіксує operation UUID/payload; редагування
заморожені до retry/reload/discard. Доведена відмова запису визначається лише
успішним outer `afterRollback` SQLDelight: UI показує «Зміни не записано»,
звільняє failed attempt і дозволяє виправлення/Undo/Redo без reload. Exception
text/class або відсутність receipt не доводять rollback. Помилка commit,
rollback acknowledgement чи post-commit notification лишається uncertain,
навіть якщо має тип validation/conflict exception; повтор використовує той самий
operation UUID/payload. Local restoration містить co-draft/history/
selection/raw mounting input і лише receipt reference для read reconciliation;
жодного автоматичного повтору команд після process replacement.

## Фактична верифікація та межі

Повний Linux suite після startup fixes: **289 shared/JVM + 26 CMP + 9 Android unit**, 0
failures/0 skipped; migration verification й Android instrumentation compilation success.
Прогін включає concave visibility/source/SQL bypass checks, Captured legacy
mounting, immutable result CAS та commit/response-loss retry. Final SHA, Android/Windows/iOS success links і APK публікуються
в [Draft PR #99](https://github.com/aleks-nl5/andrey-ceilings-android/pull/99).

## Startup blocker: відтворення, v11 та recovery

Відтворено конкретну помилку v9→v10: legacy `drawingJson` з `precisionStepMeters`
і масивом вершин без integer `xMm/yMm` дає `NOT NULL constraint failed:
ceiling_walls.ax`. Це доведений клас помилки, але без санітизованої діагностики
пристрою не є доказом точного root cause повідомлення власника.

Вхід `9.sqm` перевіряє весь набір вершин перед автоматичним seed: supported
geometry version/shape, array, object vertices, integer bounded mm, integer
geometry version timestamp. Непідтримані старі контури лишаються буквально
незмінними, без часткових/вигаданих walls або measured anchors. Старі business
records/UUID/precision/timestamps/tombstones/receipts/history не переписуються.
Історичні `8.sqm` та старші не змінені. Вхід із v9 необхідно виправити саме до
проблемного INSERT: інакше виконання не дійде до corrective migration.

Нова `10.sqm` (v10→v11) змінює лише два automatic seed triggers, а `10.db`
та strict `pointV10SchemaDefinitions` зберігають фактичну роздану v10 структуру.
Вже створені v10 anchors/wall UUIDs/operation receipts залишаються на місці.
Fresh-v11 і обидва migrated-v11 шляхи перевіряються; невідома/часткова/майбутня
схема, неправильний ключ і пошкодження fail closed. Default Android corruption
handler не має права видаляти файли. За наявної БД без wrapped key новий ключ не
створюється. Прайс, фінансові snapshots, PDF, SQLCipher cipher/key/WAL/portable
формат не змінюються.

Startup завжди показує безпечний код (`DB-KEY`, `DB-MIGRATE-9-11`, `DB-SCHEMA`,
`DB-CORRUPTION`, `DB-RECOVERY` тощо). Debug Logcat містить лише код, типи exception
і stack frames; exception messages, SQL values, шляхи, записи та ключі не
експортуються. Release показує категорію й дозволяє скопіювати код без Logcat.

`.stpb` validation доступна без live DB і не викликає `CeilingsDatabase.get()`.
Звичайний restore зберігає чинний verified SQLCipher snapshot path. Для null
session є окреме явне підтвердження `restoreUnavailable`: exclusive gate,
перевірений incoming stage, fsync-архів закритих DB/WAL/SHM/journal та лише
Keystore-wrapped original key. Маніфест із SHA-256 кожного файла прив'язаний
контрольною сумою до атомарного SharedPreferences marker з попереднім wrapped
key. Тільки після цього дозволено закрити/замінити файли та встановити новий key.

За помилки/перезапуску raw recovery спочатку перевіряє маркер/маніфест/усі
оригінали, готує всі копії й лише тоді повертає точні bytes та попередній key.
Marker знімається останнім. Recovery не мусить відкривати чи мігрувати початкову
недоступну БД. Пошкоджений/відсутній резерв зупиняє запуск до будь-якого створення
порожньої БД. Неможливість зберегти файли/старий wrapped key відхиляє імпорт;
немає force-replace/reset. Після успіху raw archive залишається в приватному
`noBackupFilesDir/database_recovery`, без автоматичного видалення. Cleanup/export
цих історичних raw archives через UI не входить у вузький fix.

Нові proofs: mixed v9 legacy/history/points і deployed-v10 physical upgrade з
partial-DDL rollback; production singleton filename + реальний Keystore + WAL,
client/project CRUD та portable round trip; реальний SIGKILL із v9 WAL і raw
restore після заміни key; explicit recovery, всі failure checkpoints, пошкоджений
маніфест, незаписуваний archive path, missing key і фізичне пошкодження ciphertext.
Окремий signed-release instrumentation run використовує той самий production
test source. Результати фізичних платформ — лише за фактичним final-SHA CI у PR.

- [ ] Owner: оновити фінальним APK без очищення даних; БД відкривається і старі записи видимі.
- [ ] Owner: створити клієнта/об'єкт; перевірити export/verify/restore резервної копії.
- [ ] Owner: якщо запуск ще відмовляє, передати безпечний код; не видаляти DB/WAL/SHM/ключі.
- [ ] Owner: повторити монтажний P3C3 journey в обох темах. Блокер лишається до окремого підтвердження.
Цей запис не прирівнює Linux до encrypted runtime перевірки іншої платформи.

Чинні shape inputs (rectangle/L/measured orthogonal) збережені; повний кутовий
solver не додається. Математика mounting і SQL guards підтримує нахилені прості
polygon walls. Verified legacy polygon без measured sides підтримує монтажні прив'язки й name/notes у тому самому editor; його вершини зберігаються буквально. Для заміни контуру потрібен явний ввід нових виміряних сторін/форми, без вигаданих measurements. Malformed/unsupported geometry лишається fail closed. iOS
framework/Simulator не означає runnable iOS UI. Повний UX polish, anchors для
line/area/void, auto-snap і фінансові зміни не входять.


Receipt result зберігає фактичні measurement/point/element versions у тому самому
SQL statement. Після result insert receipt більше не авторизує direct SQL updates;
це одноразова capability, а не постійний дозвіл. Retry звіряє весь current point
set і element versions із цим immutable result; пізніший move/add/delete/note edit
не видається за результат попередньої операції. Result document читається під тим
самим writer transaction, до звільнення lock. За незмінного контуру SQL буквально
зберігає старі length/width/area/perimeter scalars, включно з native REAL tails.


Unresolved є sticky до нового допустимого measured anchor. Вільний drag може
показати лише staging-позицію; без нових опор він не потрапляє в placed і не
підтверджується. Save такого staging блокується без partial commit. Просте
розширення контуру теж не підтверджує unresolved. Старий details move API й raw
SQL move receipts відхиляють таку спробу; typed mounting через co-editor — єдиний
шлях повернення цієї точки у valid placed.
