# Прайс 2.0 P2 — #83 / #78

**Стан на 04.11.2026:** [PR #86](https://github.com/aleks-nl5/andrey-ceilings-android/pull/86) реалізував функціональний P2 на code HEAD `d92bac4893c23cf65bbea1a8a9e6da5b48420871`, але ще **не об'єднаний**. [Android CI](https://github.com/aleks-nl5/andrey-ceilings-android/actions/runs/41804457036) і [Windows/iOS CI](https://github.com/aleks-nl5/andrey-ceilings-android/actions/runs/41804456931) на цьому SHA успішні. Власник повідомив про перевірку APK; деталізовані сценарії нижче залишаються checklist, а не автоматично підтверджені пункти. Після документаційних змін потрібна перевірка CI на **новому фінальному SHA**, потім окреме рішення власника щодо merge.

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

Результати запусків на функціональному SHA й оновлені final-SHA workflow links — у PR #86. CI не замінює APK smoke.

## APK-приймання власником (власник повідомив про перевірку, без деталізованих позначок)

- [ ] Світла/темна тема: той самий список, пошук, quick-price, редагування/видалення.
- [ ] Категорії: порожня/нова/rename/defaults/delete; непорожня відхиляється.
- [ ] UUID-фільтр зберігається після rename; deleted група не повертається після restart.
- [ ] Item → Нова категорія → Cancel/Success: поля й фокус збережені, один modal.
- [ ] Defaults/overrides/move; м²+area → шт.+manual явно; неправильна пара блокується.
- [ ] Duplicate/stale/DB unavailable: чернетка лишається, повторна дія доступна.
- [ ] Мобільний скрол/клавіатура/insets; Windows адаптивна ширина й фокус.
- [ ] SQLCipher/WAL, backup/restore, snapshots кошторисів і PDF без змін.

## Відкладене UI/UX-полірування (поза PR #86)

За окремим рішенням власника косметичні покращення залишено на майбутнє:
відновити компактне розташування категорії й одиниці поруч приблизно 60/40,
ціну праворуч близько 35% із поясненням ліворуч, зблизити керування категоріями
з чипами та за потреби покращити висоту/скрол/фокус форм в обох темах.
Це **не функціональний блокер P2**, не підстава в цьому PR перебудовувати
екран, бізнес-логіку чи БД. Після merge планувати окремо.

Не merge і не закривати #83/#78 без рішення власника.
