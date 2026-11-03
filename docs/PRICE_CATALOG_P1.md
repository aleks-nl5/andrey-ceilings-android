# Прайс 2.0 — P1 (#80, #78)

Чинне рішення власника від 03.11.2026: **старий тестовий прайс не переносимо**.
Схема SQLDelight v5 (`4.sqm`) замінює тільки `price_items` і створює
`price_groups`, `price_catalog_state` та сумісну view `price_catalog_items`.
Файл SQLCipher, ключі, WAL, backup/restore, інші бізнес-таблиці не скидаються.

## Модель і API

`PriceGroupRepository` — CRUD, Flow, soft-delete та CAS за `updatedAt`.
Група має локальний ID, UUID, назву, часові мітки та редаговані defaults.
Активні назви унікальні (SQLite NOCASE); видалення непорожньої групи атомарно
заборонене. Після soft-delete назву можна використати для нової незалежної групи.

`price_items.groupUuid` посилається на унікальний UUID групи через FK.
`PriceRepository.createInGroup` копіює defaults один раз і приймає індивідуальні
unit/quantityMode/placementKind overrides. За відсутності defaultUnit потрібна
явна одиниця. `updateConfigured` та `move` використовують SQL CAS; code та UUID
не мають операцій зміни. Перейменування групи не змінює позицій; `move` змінює
лише зв'язок та токен версії. Кількість не зберігається в позиції каталогу.

Валідація KMP і SQL CHECK: `ceiling_area` → `м²`,
`ceiling_perimeter`/`entered_length` → `м.п.`, `manual` → будь-яка непорожня
одиниця. Placement (`none`/`point`/`line`/`area`) незалежний і не створює
фінансових рядків, геометрії, кількості чи автоматичного розрахунку ціни.

Чинний CMP екран працює через view з `category = price_groups.name`.
List/search/create/edit/quick-price/delete збережені. Adapter зберігає groupUuid
при незмінній категорії, навіть якщо групу паралельно перейменовано.
Новий UI керування групами/параметрами — окремий P2.

## Одноразове наповнення

`StarterPriceCatalog` — єдине KMP джерело 5 груп і 22 демонстраційних позицій,
дослівно перенесених із `DemoPriceList.kt` у main `83fece8`.
Code/title/category/unit/price перевіряються незалежним regression fingerprint.
Quantity mode кожної позиції задано явно за stable code. Змішані групи мають
консервативні manual/none defaults; освітлення та додаткові роботи не мають
типової одиниці. Placement початкових позицій консервативно `none`.

Bootstrap спочатку виконує INSERT OR IGNORE singleton marker (захоплює SQLite
writer lock), потім читає `seedVersion`. Версія 0 означає незавершену ініціалізацію;
версія 1 — завершену. Групи, позиції та marker записуються в одній транзакції.
Умова count()==0 **не використовується**. Усі starter rows звичайні редаговані
записи; після видалення останньої позиції/групи повторне відкриття їх не відновлює.
Збережений marker переноситься з фізичним SQLCipher snapshot під час backup/restore;
нова процедура backup/restore чи повторний startup seed не додаються.

## Платформні міграції та відмова

- Android production відкриває БД через `PriceCatalogSchema`: generated create/migrate,
  seed та marker в одній транзакції, усередині schema callback SQLCipher helper.
  WAL factory і перевірки WAL/FK не змінені. Android-only `seedIfEmpty` вилучено.
  Ізольовані старі тести можуть створювати generated schema без starter data;
  нові instrumentation тести явно перевіряють production schema.
- Windows `openDesktopPriceCatalog` під транзакційним writer lock визначає фактичні
  v3/v4/v5 колонки. v3 спочатку отримує існуючу additive v4 міграцію, далі v5.
  Успіх зберігає `user_version=5`. Непідтримувана/неповна схема відхиляється.
  Не створюються нові файл БД, ключ або сіль замість існуючих.
- iOS: тільки KMP framework та isolated Simulator SQLCipher contract. Runnable
  host лишається #42; P1 не додає iOS застосунок.

Будь-яка помилка DDL, seed або marker призводить до rollback і відкриття завершується
помилкою. Wipe/retry через нову БД не існує. Тести інжектують помилку посеред seed
та перевіряють збереження старої таблиці/рядків після відмови.

## Залежності та історичні дані

Фактична v4 схема не містить inbound FK до `price_items`. Estimates, series,
revisions та revision lines зберігають власні snapshots/price UUID/code.
Міграція їх не читає для перепризначення, не змінює та не перераховує.
Старий UUID залишається незмінним у snapshot, навіть якщо новий starter має
такий самий code. Подальший явний перерахунок із недоступним UUID — #44.
SQL-залежності перевірені в усіх `.sq`/`.sqm`; view залежить тільки від нових
таблиць прайсу. Ізольований давній Room staging proof лише створює необхідну
групу перед імпортом у свою порожню тестову БД; він не є production міграцією.

Спільні persistence proofs запускаються на JVM, Android SQLCipher/WAL, Windows
SQLCipher та iOS Simulator. Перевіряються точний seed, defaults/overrides, CAS,
soft-delete/guard, rename/move, незмінність усіх колонок інших таблиць включно
з immutable revisions та старими price references, rollback і reopen без seed.
Окремий JVM тест перевіряє конкурентні bootstrap через дві connection.

## APK приймання власником на фінальному SHA

- [ ] Fresh install: 22 позиції у 5 категоріях, ціни відповідають starter.
- [ ] List/search/create/edit/quick-price/delete у світлій і темній темах.
- [ ] Видалити всі позиції; закрити/відкрити — вони не відновлюються.
- [ ] Existing encrypted v4 test DB: новий прайс, інші записи/кошториси/PDF незмінні.
- [ ] Backup/restore після редагування та видалення каталогу не запускає seed.

CI не замінює особисте APK приймання. #80/#78 не закривати та Draft PR не merge.
