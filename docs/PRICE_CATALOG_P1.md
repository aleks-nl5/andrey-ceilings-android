# Прайс 2.0 — P1 (#80, #78): реалізовано в PR #81

**Статус на 03.11.2026:** [PR #81](https://github.com/aleks-nl5/andrey-ceilings-android/pull/81) вже **merged** у `main`
(final `de8dddde`, merge `b4907ff`). Обидва обов'язкові CI
[Android](https://github.com/aleks-nl5/andrey-ceilings-android/actions/runs/41729752782) і
[Windows/iOS](https://github.com/aleks-nl5/andrey-ceilings-android/actions/runs/41729752735)
пройшли успішно на фінальному SHA. Власник повідомив, що встановив і
успішно протестував APK; це не означає автоматичного підтвердження кожного
нижченаведеного детального ручного сценарію.

**Постмердж-аудит:** [#82](https://github.com/aleks-nl5/andrey-ceilings-android/issues/82)
— виправити Unicode-унікальність назв груп: стандартний SQLite `COLLATE NOCASE`
не зводить різнорегістрові українські назви (наприклад, «Полотна»/«полотна»).
Нова атомарна гарантія та безпечна v5→v6 міграція — окремий PR. 
[#80](https://github.com/aleks-nl5/andrey-ceilings-android/issues/80)
залишається відкритим до цього виправлення; umbrella [#78](https://github.com/aleks-nl5/andrey-ceilings-android/issues/78) — до завершення Прайсу 2.0.

**UX-обмеження старого compatibility editor:** коли одиницю `м²` змінюють
на `шт.`, але позиція має `ceiling_area`, KMP валідація правильно відхиляє
запис; старий екран не дає одночасно редагувати quantityMode і показує
загальне повідомлення. Вирішення — [#83 / P2 CMP editor](https://github.com/aleks-nl5/andrey-ceilings-android/issues/83),
з явним вибором режиму та зрозумілою помилкою; **жодного мовчазного перемикання**.
Starter placementKind=`none` — навмисний консервативний стан P1,
не недоробка Canvas: відповідний вибір вводиться в P2, actual placement — #76/#71.

Цей документ далі зберігає технічний опис **реально виконаного P1**.

Чинне рішення власника від 03.11.2026: **старий тестовий прайс не переносимо**.
Схема SQLDelight v5 (`4.sqm`) замінює тільки `price_items` і створює
`price_groups`, `price_catalog_state` та сумісну view `price_catalog_items`.
Файл SQLCipher, ключі, WAL, backup/restore, інші бізнес-таблиці не скидаються.

## Модель і API

`PriceGroupRepository` — CRUD, Flow, soft-delete та CAS за `updatedAt`.
Група має локальний ID, UUID, назву, часові мітки та редаговані defaults.
Активні назви унікальні за SQLite NOCASE **лише в межах підтримуваного ним регістру** (для Unicode/української є відома вада #82); видалення непорожньої групи атомарно
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
Новий UI керування групами/параметрами — окремий P2. Compatibility CAS читає
параметри перед одиничним SQL UPDATE з id/uuid/code/version guards: два deferred
read locks не повинні перетворювати звичайний stale conflict на SQLite busy.
Створення нової категорії та пов'язаний CAS лишаються однією транзакцією;
невдалий CAS відкочує створення групи.

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
- iOS: тільки KMP framework та isolated Simulator SQLCipher contract. SQLiter 1.3.3
  сам обгортає create/upgrade та user_version фізичною транзакцією; borrowed driver
  у callback її не бачить. Тонкий `IosPriceCatalogSchema` використовує цю вже
  наявну транзакцію для того ж спільного DDL/seed, без вкладеного BEGIN.
  Fresh install та rollback посеред справжнього upgrade callback перевіряються
  окремо. Runnable host лишається #42; P1 не додає iOS застосунок.

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

CI не замінює особисте APK приймання. **PR #81 вже merged, APK smoke власником успішний.** Детальні чекбокси вище збережено як довідковий перелік і не позначено без окремих підтверджень. #80 лишається відкритим до виправлення #82; #78 — umbrella; наступний UI етап — #83.
