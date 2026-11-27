# P7 — робочий календар Libellaro: погоджений продуктовий контракт
27.11.2026. Issue #49. 46 рішень власника; planning/audit only. Не починати production до окремого погодження технічного handoff. Цей документ має пріоритет над суперечливими старими пунктами #49.

## Етапи
- P7A: SQLDelight/SQLCipher модель, CRUD, повторення і багатоденні роботи, CMP календар (тиждень/місяць/список), форма, внутрішні конфлікти, dashboard, об'єкт, пошук, налаштування core.
- P7B: нагадування, snooze, контекстні дії, системна зайнятість, експорт, одностороння синхронізація, налаштування інтеграцій. Android-first; shared contracts/tests, native iOS/Windows integration після #138/#42. Не розширювати до P8, Firebase або командної синхронізації.

## Рішення власника (№ — вибір)
1. В — об'єкт обов'язковий для монтажу, для інших опціональний; самостійна подія з адресою/контактом, пізніше прив'язка.
2. В — 4 стандартні типи (замір, монтаж, доставка, інше), власні назва/колір/іконка; стандартні не видаляються.
3. В — заплановано, підтверджено, виконано, скасовано; прострочення derived, не статус БД.
4. В — точний початок/кінець, весь день, дата без часу; налаштовувана типова тривалість кожного типу.
5. В — багатоденна робота одна подія зі спільним статусом, щоденним графіком, корекцією окремих днів.
6. В — після завершення монтажу запропонувати завершити об'єкт лише за підтвердженням; оплата й кошториси незалежні.
7. В — створення з календаря (дата), об'єкта (дані), панелі (компактний +); центральний nav + лишається новим заміром.
8. В — блок Роботи в об'єкті: до 2 найближчих, +, повний перелік окремо.
9. А — прострочені лишаються на своїй даті з індикатором; без окремого блоку/списку і автоперенесення.
10. Б — скасування і soft-delete будь-якого статусу після підтвердження видалення.
11. В — видалення об'єкта після явного підтвердження скасовує активні роботи/нагадування; історія збережена з назвою об'єкта; чинні обмеження видалення незмінні.
12. А — тільки кольорові крапки типів на сітці, без лічильників/назв.
13. Б — виконані приглушені, скасовані приглушені й перекреслені; іконки статусу.
14. В — контекстне меню і swipe зліва направо, підтвердження видалення, без конфлікту жестів.
15. Б — одна повноекранна CMP форма створення/редагування, explicit Save, dirty exit guard.
16. В — актуальні адреса/контакти об'єкта/клієнта з можливістю перевизначення на роботу; не змінювати джерело.
17. В — повторення щодня/щотижня/щомісяця/власний графік.
18. В — безстроково/до дати/після N повторень; не матеріалізувати нескінченність.
19. В — лише ця або ця й наступні; попередні не змінюються; застосовується до edit/move/cancel/delete; уточнює №17.
20. В — пошук назва/адреса/клієнт/об'єкт/примітки, фільтри тип/статус/об'єкт, компактне розкриття, reuse P6C.
21. В — глобальний пошук усіх робіт, активні пріоритетні, exact occurrence, bounded recurrence.
22. В — нагадування типові за типом, per-event override/add/off.
23. В — для без часу/all-day напередодні та в день роботи, налаштовуваний час/override/off.
24. В — окремі нагадування першого й наступних днів багатоденної роботи.
25. В — snooze 5/15/30/60 хв або власний час, без зміни роботи/інших нагадувань; stale invalidated при зміні/завершенні/скасуванні.
26. В — opt-in одностороння синхронізація за типами, per-event override; Libellaro source of truth; free/busy незалежне.
27. В — вибір існуючого календаря або окремого Libellaro якщо підтримується.
28. В — default лише тип/дата/час; назва об'єкта, адреса, ім'я/телефон клієнта, примітки opt-in; ніколи фінанси/кошториси.
29. В — сірі крапки зовнішньої зайнятості на сітці й нейтральні busy інтервали в розкладі; жодних приватних подробиць.
30. Б — динамічна перевірка накладання, компактні деталі, явне підтвердження при Save, не hard block; без автоматичного пошуку вільних слотів.
31. Б — all-day блокує день, date-only не блокує години.
32. Б — налаштовувані робочі/вихідні дні, візуальна відмінність, не блокувати; без робочих годин.
33. Б — компактний діалог перенесення, збереження тривалості, перевірка конфліктів і нагадування; багатоденні через повний редактор.
34. Б — архів/відновлення custom types; наявні події зберігають тип/колір/іконку; не вибирати архівовані для нових робіт; стандартні не архівуються.
35. А — лише загальні Налаштування → Календар і сповіщення; не додавати gear у календарі; інтегрувати у P8 без дублювання.
36. В — Back відновлює дату/фільтри/scroll; fresh entry today/reset filters; режим зберігається.
37. В — перший день тижня системний default, ручний override.
38. В — робочий TZ з per-event override, зміна TZ телефона не змінює домовленості; DST коректно.
39. В — при disable sync залишити (default) або видалити лише доведено власні записи; внутрішні/сторонні не змінюються.
40. В — restore робіт, типів, налаштувань; reactivation нагадувань/системної інтеграції лише після підтвердження й перевірки дозволів/ідентичності, без дублювання.
41. А — при зміні/видаленні власної синхронізованої події у системному календарі відновити з Libellaro; не змінювати сторонні.
42. В — іконка тільки проблем sync, пояснення/retry, повний стан у Settings.
43. В — ненав'язлива дія Запланувати монтаж в об'єкті за наявності approved quote і відсутності активного монтажу; без auto create/dialog.
44. Б — optional exact ceiling UUID selection for installation, empty means whole project; не змінювати geometry/finance/quote statuses.
45. А — окремого статусу монтажу стелі немає, факт лише в подіях.
46. А — завершити продуктове планування, зафіксувати, перейти до технічного аудиту; без нових функцій.

## Незмінені рішення #49
Week default, month/list, horizontal period swipes, Today, єдиний динамічний список під сіткою (today/empty fallback 2–3 future; selected other day exact; list chronological by date), компактні card actions, #141 anchor/offset return. Dashboard mandatory block configurable order, up to 4 (1 detailed + 3 compact), real upcoming only, urgency 7d/1d/3h, orange slow date-badge pulse with reduced-motion, accurate navigation. Explicit manual system-calendar export retained; native notification actions proposed earlier (call dialer, message compose, navigate, snooze, max supported actions, never automatic send/call; UUID recheck, lockscreen privacy). Opt-in selected external calendars, busy only, no titles/contacts/notes or logs, permission loss/unknown/stale not free, deduplicate own exported records, account/provider limitations must be proved. Restore and security invariants, localization resources, minimal automated UI, physical APK + independent review + exact QUICK/FULL. Owner-only Ready/merge.

## Початковий технічний аудит (main 93baa187, 27.11.2026)
Verified files:
- Android CalendarScreen.kt is legacy Android-only UI reading all projects/estimates and grouping createdAt; scheduled upcomingRecords always empty. Replace business-calendar route with shared CMP; historical createdAt records not scheduled work.
- shared dashboard UpcomingEventsCard.kt UI contract has startsAtMillis, project/client labels, optional projectId; upcomingWorkEvents filters starts >= now and takes 4. Android DashboardScreen.kt explicitly empty; shared DashboardScreen defaults empty. Adapt to exact occurrences, date-only/all-day/multiday ongoing semantics, independent event navigation and creation, bounded reads.
- BusinessSchema.sq + migrations 1..23, schema v24; new persistence needs proposed additive 24.sqm -> v25 (not authorized yet), current snapshot verification, current DB/restore across platforms. PortableBackupPayload embeds encrypted DB bytes, so persisted business data participates, but device permissions/alarms/provider mappings need separate reconciliation on restore.
- shared/build.gradle.kts already includes kotlinx-datetime 0.8.0; prefer common time rules, no Android java.time dependency in common. AppNavigationState has CALENDAR route. ObjectsClientsStore uses sessionClosed + projectRead mutex and cached flows; calendar must use same session lifetime principles, no stale data after close/restore.
- Audit remains incomplete: inspect all writer/deletion entry points, schema guards/snapshots and portable recovery/restore, desktop host, native permission/scheduling capabilities, query plans, localization, and existing tests before implementation.

## Proposed technical direction (not approved yet)
Use bounded indexed SQL queries and deterministic common recurrence expansion by requested date range, stable series ID + original occurrence key, occurrence exceptions and forward-only split; never expand entire series. Explicit schedule mode/date/TZ, timed daily segments for multi-day, local date-only semantics. Define recurrence monthly missing dates, DST ambiguity/nonexistent time, completed history and edits, bounded conflict check for infinite series, exact occurrence identifiers and status overrides. Recheck conflict/ownership at write transaction; active parent deletion + event cancellation atomic, preserve historic labels; no unreviewed cascade or live financial changes. Use session cache, no fake zero on read error, exact Back/restore. P7B durable idempotent reconciler: app-owned identity and provider mapping, permissions/restore gates, notifications reschedule/cancel, no private data beyond opt-in. Confirm OS-supported behavior before committing to exact delivery or external calendar creation. No production code/CI changes in this planning PR.

Next: technical decisions with owner, then bounded P7A handoff, separate P7B technical audit.