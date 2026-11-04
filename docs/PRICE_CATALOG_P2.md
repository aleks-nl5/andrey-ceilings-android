# Прайс 2.0 P2 — #83 / #78

Розвиток чинного `PriceCatalogScreen`, без нового route, таблиць або seed.
UI «Категорія» — один активний `price_groups` із незмінним UUID. Початкові
5/22 записи лишаються звичайними редагованими даними, видалені не відновлюються.
P1 (#81) та Unicode/v6 (#85) уже merged; P2 потребує окремого приймання власника.

## Спільна boundary та UI

`PriceCatalogStore.groups` походить тільки з `PriceGroupRepository.observeAll()`;
включає порожні категорії. Store поєднує group/item Flow, а екран додатково
проєктує актуальні підписи за UUID: rename реагує без UPDATE позицій.
UUID-фільтр працює разом із пошуком; видалений UUID переходить на «Усі».
Hard-coded стандартні назви не додаються до чипів або селектора.

«Керувати категоріями» відкриває компактний CMP-діалог із CRUD/defaults,
доступний і в порожньому прайсі. Видалення потребує підтвердження та атомарно
дозволено лише без активних позицій; tombstones не заважають. Немає cascade.
Редагування зберігає UUID і використовує CAS початкового group.updatedAt.

`ConfiguredPriceDraft` та `PriceItemEditor` — окремий P2 контракт із immutable
original/version, groupUuid, unit, quantityMode, placementKind і текстом ціни.
Legacy `save(original, PriceCatalogDraft)` залишається сумісним. Новий
`saveConfigured` не викликає `ensureByName`: категорія має існувати.

Чернетка належить screen state. Дія «Нова категорія…» послідовно замінює
item modal на той самий category create form; одночасно активний один dialog.
Cancel повертає початкові поля/фокус. Успіх окремого Create вибирає новий UUID,
але не створює позицію. Подальша помилка item Save не відкочує цю категорію.
Підтвердження зміни одиниці та відкидання чернетки також послідовні.

## Незалежні параметри і ціна

Нова позиція починається з вибору категорії. Defaults копіюються один раз;
ручні overrides зберігаються при переході між категоріями. Явна дія
«Взяти типові параметри категорії» змінює лише три параметри.
Переміщення/rename/default update не переписують власні параметри позицій.
Порожня типова одиниця зберігається як null; null + non-manual mode допустимі.
Перед item Save одиницю слід вказати й узгодити через `PriceParameters.validate`.
Зміна unit не перемикає режим автоматично. Placement не створює геометрії,
кількостей чи фінансових рядків: це параметри майбутніх замірів (#76).

Configured Save та quick-price використовують спільний parser: 0…999 999 999,99,
до двох знаків, кома/крапка, без тихого округлення. Quick-price змінює лише ціну
з SQL CAS, зберігає id/uuid/code, membership та параметри. Дублікат category/item,
stale CAS, непорожня категорія й DB/IO failure відображаються окремо; чернетка
залишається відкритою, pending скидається у finally, cancellation не приховується.
Android/Desktop hosts лише збирають shared Flows та виконують IO callbacks.

## Перевірки

- `PriceCatalogEditorTest`: defaults/overrides/null, UUID-фільтр і rename без item
  emission, незалежність параметрів, dirty state, збереження чернетки/фокуса.
- `PriceCatalogP2FlowTest`: справжній store Flow при rename/empty/delete, відкритий
  редактор і stale/deleted UUID, окремі повідомлення помилок.
- `PriceCatalogP2Proof`: той самий configured/group store CRUD на JDBC, Android
  SQLCipher/WAL, Windows SQLCipher та iOS Simulator; Unicode duplicates, case-only
  rename, CAS, переміщення, quick-price non-default параметрів, bounds ціни,
  явно створена категорія після failed Save, tombstones і звільнення назв.
- Наявні migration/seed/backup/immutable history proofs зберігаються без послаблень.

Результати запусків і final-SHA workflow links — у Draft PR. CI не замінює APK smoke.

## APK-приймання власником на фінальному SHA (не виконано автоматично)

- [ ] Світла/темна тема: той самий список, пошук, quick-price, редагування/видалення.
- [ ] Категорії: порожня/нова/rename/defaults/delete; непорожня відхиляється.
- [ ] UUID-фільтр зберігається після rename; deleted група не повертається після restart.
- [ ] Item → Нова категорія → Cancel/Success: поля й фокус збережені, один modal.
- [ ] Defaults/overrides/move; м²+area → шт.+manual явно; неправильна пара блокується.
- [ ] Duplicate/stale/DB unavailable: чернетка лишається, повторна дія доступна.
- [ ] Мобільний скрол/клавіатура/insets; Windows адаптивна ширина й фокус.
- [ ] SQLCipher/WAL, backup/restore, snapshots кошторисів і PDF без змін.

Не merge і не закривати #83/#78 без рішення власника.
