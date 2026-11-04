# P3C2 — положення точок і read-only overlay редактора

Refs #96 / #71 / #93 / #95. Baseline: main `d71c04db871e54759c0e3c38362522015dc8fb09`,
PR #94 merged власником 04.11.2026 17:37 UTC. Початкова схема — v8.
Цей документ спочатку фіксує технічне рішення; фактичні результати будуть додані
після реалізації й перевірок. Owner APK acceptance залишається окремим кроком.

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
