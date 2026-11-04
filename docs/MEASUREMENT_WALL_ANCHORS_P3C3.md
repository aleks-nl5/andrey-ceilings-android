# P3C3 — монтажні прив'язки та спільний редактор

Refs #98 / #71 / #76 / #95. Baseline main `c34a2d35348a9534a620728be3cec0a1e85ae566`;
PR #97 merged власником 04.11.2026 19:42:48 UTC. Початкова схема v9.

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
