# Прайс P1: Unicode-унікальність (#82)

Вузький follow-up до #80/#78, після PR #81/#84. Не додає P2 редактор,
не змінює історичні кошториси, revisions, PDF або калькулятор.

## Контракт

Єдиний `normalizedNameKey(raw) = raw.trim().lowercase()` у commonMain.
Display при звичайному записі — `raw.trim()`, порожні назви відхиляються.
Немає locale/ICU, NFC/NFKC, стискання внутрішніх пробілів, transliteration
або fuzzy matching. UUID/code залишаються identity; title/name — лише назви.

`price_groups.nameKey` унікальний серед активних груп;
`price_items.titleKey` унікальний серед усіх активних позицій без `groupUuid`.
Два partial UNIQUE indexes виключають tombstones. Soft-delete звільняє назву,
але зберігає рядок і UUID. Capitalization-only rename — звичайний CAS update.

INSERT застосовує цільовий `ON CONFLICT(key) WHERE deletedAt IS NULL DO NOTHING
RETURNING`, тому тільки конфлікт назви стає `DuplicateGroupName`/
`DuplicateItemTitle`. Інші SQL/шифрувальні помилки поширюються без маскування.
UPDATE має CAS/active guards і атомарний `NOT EXISTS` для дубліката, перевіряє
причину порожнього RETURNING у тій же writer-транзакції, спочатку stale CAS.
UNIQUE залишається остаточною гарантією, зокрема для legacy SQL queries.
`ensureByName` захоплює writer lock перед пошуком; конкурентні writers
не створюють двох груп. Невдала зміна позиції відкочує створену категорію.
Поточний CMP editor показує «Позиція з такою назвою вже існує».

## Міграції

| Вхід | Дія | Успіх / відмова |
| --- | --- | --- |
| Fresh | generated v6 + ті самі 5 груп / 22 позиції + marker | Одна транзакція |
| v3/v4 | Чинні additive upgrade steps до v5, далі v6 | Збережено існуючі P1 semantics |
| v5 | `5.sqm`: ключі/індекси/guards; KMP collision check + backfill фактичних назв | Без змін будь-яких старих колонок |
| v6 | No-op/reopen, marker=1 | Не reseed, включно з порожнім каталогом |
| v5 collision / interruption | Контрольована відмова відкриття | Відкат DDL, ключів, business rows і версії |

`4.sqm` незмінний. `5.db` — порожній schema-only snapshot актуального v5
для SQLDelight migration verification; жодних user data або ключів.
SQLite ALTER не може одразу додати обов'язковий заповнений Unicode key.
Тому колонки структурно nullable лише для staged backfill; однакові BEFORE
INSERT/UPDATE triggers у fresh і migrated схемі відхиляють NULL/empty для
активного рядка. Жодна майбутня активна вставка/оновлення не може обійти UNIQUE
через NULL/empty sentinel. Kotlin backfill заповнює також tombstones;
дозволяється порожній ключ лише у вже видаленого рядка з порожньою назвою.
Усі прикладні writes, legacy queries/import fixture передають спільний ключ.
SQLite `lower()`/NOCASE не використовується для нормалізації.

Production `PriceCatalogSchema` обгортає DDL, перевірку обох наборів назв,
backfill та marker однією транзакцією. Android helper володіє зовнішньою
SQLCipher/WAL schema/version транзакцією. iOS SQLiter 1.3.3 уже має фізичну
транзакцію callback/version: `IosPriceCatalogSchema` не запускає вкладений BEGIN.
Windows probe визначає v3/v4/v5/v6 за колонками під writer lock;
`user_version=6` зберігається тільки при успішному commit.

## Безпечне ручне виправлення колізії v5

Помилка `CatalogNameMigrationConflict` містить тип GROUP/ITEM і UUID проблемних
рядків (або ознаку порожньої активної назви); повні назви/БД/секрети не логуються.
Відкриття v6 зупиняється. Немає автоматичного merge/rename/delete або wipe.

1. Зберегти encrypted backup старої БД штатним способом у v5-сумісній версії;
   не копіювати лише `.db` при активному WAL. Ключ не змінювати.
2. Відкрити каталог у попередній v5-сумісній версії. За UUID визначити рядки;
   власник явно обирає різні непорожні назви або soft-delete непотрібного рядка.
   Для item titles перевірити весь каталог, включно з іншими групами.
3. Зберегти звичайною versioned операцією, не змінювати UUID/code/історію/ціни.
   Якщо старий UI не дозволяє потрібну дію, виправляти лише контрольованим
   v5-сумісним інструментом із backup, а не довільним plaintext export.
4. Повторити upgrade. Оригінальний v5 файл лишається доступним до успішного
   завершення; ніякого відкриття частково мігрованого файлу.

## Перевірки і власницьке приймання

Common proofs запускаються на JVM SQLite, Android SQLCipher/WAL, Windows
SQLCipher та iOS Simulator: Unicode keys, глобальні дублікати, self-rename,
CAS vs duplicate, soft-delete/tombstones, відсутні/порожні ключі, rollback
нової групи, точний starter. File tests: редагований populated v5 з immutable
history, group/item collisions, empty title, interruption після часткового
backfill, schema/data/user_version equality після rollback, upgrade/reopen.
Дві незалежні JVM/Windows connections перевіряють create vs create і ensure.

Фінальні SHA, CI runs і результати містяться в Draft PR. Не позначати особисте
APK приймання власника за нього. На фінальному SHA (обидві теми):

- [ ] Список/пошук, Unicode case + крайні пробіли.
- [ ] Create/edit/delete/quick price працюють.
- [ ] Дублікат у своїй/іншій групі показує окреме повідомлення; draft зберігається.
- [ ] Stale edit не видає duplicate; невдала зміна не залишає порожню групу.
- [ ] Історичний кошторис/PDF незмінний після редагування прайсу.

P2 UI/quantityMode/placementKind — #83; runnable iOS host — #42.
