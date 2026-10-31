# Compose Multiplatform / Kotlin Multiplatform — план і фактичний стан

> **Актуалізовано 31.10.2026 після merge PR #29.** Це поточний план; попередній опис PR #22/#23 нижче лишається як історична довідка. Затверджений пріоритет власника: **спочатку завершити KMP + CMP для Android, Windows і iOS, діагностика #30–#34 пізніше.** Android є production-подібним функціональним еталоном, його UI ще платформний. Go/AAR видалені, KMP-розрахунки та SQLDelight працюють; SQLCipher WAL + immutable revisions + .stpb hardening з PR #29 вже в `main`.

## Активний CMP інкремент — Draft PR #43 (31.10.2026)

- **Android:** `PriceCatalogScreen` і `PriceCatalogStore` підключено до SQLDelight/SQLCipher; KMP пошук, валідація та атомарне збереження/soft-delete. WAL, backup і immutable snapshots залишилися в Android host. CI та фізичний APK — **підтверджені**.
- **Windows:** той самий CMP-каталог і реальний `PriceRepository` із SQLCipher file, явним паролем і per-installation PBKDF2 salt. Ключ на диск не зберігаємо, але **немає системного Windows credential-vault, installer, автоматичного backup і recovery UX**; поки лише прототип, не готова production-збірка.
- **iOS:** CMP commonMain/UI компіляція та iOS Simulator тести — обов'язкові; runnable Xcode host і Keychain пізніше через #42.
- **Схему цін не змінено:** `price` залишається єдиною обов'язковою ціною продажу; опціональні закупівля, монтаж, витрати, націнка — #37.
- [x] CI на перевіреному функціональному коміті `afd64322`: [Android APK, JVM, instrumentation](https://github.com/aleks-nl5/andrey-ceilings-android/actions/runs/41217205314) і [Windows desktop/JVM, iOS Simulator framework/SQLCipher tests](https://github.com/aleks-nl5/andrey-ceilings-android/actions/runs/41217205280) — успішні. Власник перевірив Android APK (CRUD, збереження після перезапуску, остаточний вигляд прайсу). [ ] Окремо: Windows GUI smoke, системне сховище Windows і runnable iOS host (#42).

## Оновлена цільова архітектура
- **Один спільний `:shared` KMP domain + repository + SQLDelight schema**, логіка розрахунків та immutable кошторисів не дублюється.
- **Один адаптивний CMP UI** із Material 3, погодженими кольорами, Inter, темами (light/dark/system). Відмінність між мобільними й desktop — **layout і OS interactions**, не business-logic копії.
- **Тонкі platform hosts** `:app` Android, `:desktopApp` Windows та майбутній runnable `iosApp`, відповідальні за вікно, системні inset, permission, доступ до файлів/SAF, PDF/друк, безпечні SQLCipher ключі, резервування та platform lifecycle.
- **Вимога шифрування:** Android SQLCipher WAL готовий. Працюючі KMP/SQLCipher тести Windows/iOS не доводять production key storage, packaging, реальний iOS host чи parity; кожну платформу вводити контрольованими перевірками.
- **Без синхронізації**, інтернет необов'язковий. UUID, timestamps і tombstones лише закладають можливість майбутнього sync; клієнтські PDF окремо не шифруємо за погодженою політикою.

## Черга робіт
1. **Перший робочий CMP slice: прайс** — [issue #36](https://github.com/aleks-nl5/andrey-ceilings-android/issues/36) / [повна специфікація](CMP_PRICE_LIST_DESIGN.md). Спільна тема та design primitives; adaptive search/filter/list; швидка/повна правка ціни, категорія, додавання/видалення; підтвердження кожного запису; Android cutover; Windows реальний зашифрований CRUD; iOS compile/tests. Один Draft PR без зміни ціноутворення.
2. **Windows/iOS host hardening** — [issue #42](https://github.com/aleks-nl5/andrey-ceilings-android/issues/42): Xcode host, Keychain, Windows key store/packaging, platform file/backup/PDF adapters, smoke тести запуску. Не називати scaffold повноцінним додатком.
3. **Поекранне CMP перенесення:** клієнти й об'єкти; заміри/геометрія/креслення/календар; калькулятор/незмінні кошториси та PDF; налаштування/навігація. Кожен cutover проходить Android APK acceptance + Windows/iOS CI; старий Android екран прибирати лише після parity.
4. **Розширена модель прайсу після базового CMP прайсу** — [issue #37](https://github.com/aleks-nl5/andrey-ceilings-android/issues/37): **одна обов'язкова ціна продажу**, опціональні закупівля/монтаж/інші прямі витрати/націнка; не підміняти невідоме нулем. У першому UI PR чинне `price` залишається єдиною ціною.
5. **Пізніше окремими PR:** [категорії #38](https://github.com/aleks-nl5/andrey-ceilings-android/issues/38), [масові операції #39](https://github.com/aleks-nl5/andrey-ceilings-android/issues/39), [CSV #40](https://github.com/aleks-nl5/andrey-ceilings-android/issues/40), [історія цін #41](https://github.com/aleks-nl5/andrey-ceilings-android/issues/41), [ручні конфлікти прайсу #35](https://github.com/aleks-nl5/andrey-ceilings-android/issues/35); системна діагностика #30–#34 після пріоритетної міграції.

## Інваріанти переходу
- Не змінювати наявні SQLCipher WAL, .stpb, механізм restore/rollback, алгоритми KMP геометрії/округлення, UUID/коди та історичні immutable v1/v2/v3 кошториси/PDF лише заради UI перенесення.
- Не втрачати Android UI еталон (теми, Inter, картки, календар, навігація, edge-to-edge, масштабування, accessibility). Не використовувати заглушки на місці робочих функцій.
- У кожному PR тести common/KMP, Android CI/instrumentation та фізичний APK, Windows JVM/desktop, iOS Simulator build/tests. Не вважати iOS продукт готовим за фактом compile framework.
- Не змінювати `main` або зливати Draft без окремого рішення власника.

---

## Історичний план попередніх PR (архів; не поточний статус)

**Стан на 29 жовтня 2026 року:** PR #22 об'єднано з `main`, етап 1 завершено. У Draft PR #23 на прохання власника переведено Android-калькулятор на shared Kotlin KMP та видалено Go/AAR; KMP-only кодовий HEAD `ae326a73` пройшов Android та Windows/iOS CI, власник підтвердив на телефоні роботу нового KMP APK (розрахунки, кошториси, PDF); PR #23 підготовлено до огляду, але **у `main` не об'єднано**. Наведені нижче початкові рішення PR #22 — **історичний стан**, а не опис актуального runtime. Деталі: [GO_TO_KMP_PARITY.md](GO_TO_KMP_PARITY.md).

## Актуальна архітектура PR #23 (KMP-only калькулятор)

- **Android:** Compose UI → `CeilingCalculator` (адаптер) → `:shared` `KmpCeilingCalculator`; SQLDelight/SQLCipher, збереження знімків кошторисів і Android PDF без змін інтерфейсу.
- **Windows/iOS:** `:shared` KMP-розрахунки доступні в targets; iOS Simulator, JVM/Windows тести й framework продовжують збиратися. Повний реальний UI цих платформ іще попереду.
- **Go видалено** разом з AAR, gomobile toolchain та CI Go steps. Еталонні тестові вектори залишилися у Kotlin.
- **Підтверджено:** [Android CI 40903911505](https://github.com/aleks-nl5/andrey-ceilings-android/actions/runs/40903911505) і [Windows/iOS CI 40903911291](https://github.com/aleks-nl5/andrey-ceilings-android/actions/runs/40903911291) — успішні на `ae326a73`; власник перевірив KMP APK, розрахунки, кошториси та PDF. Не заявляти додаткові невиконані ручні граничні сценарії як пройдені. Merge — тільки за власником; WAL/синхронізація — окремі задачі.

Наступні розділи про `PR #22`, `Go/AAR` та первинні чеклісти збережені як історія, не як поточні інструкції.

## Мета і межі PR #22

Поступово переводимо Andrey Ceilings на KMP/CMP для Android, Windows та iOS **без заміни чинного Android UI заглушками**. Цей PR завершує перший стабілізаційний етап: **спільна зашифрована локальна SQLDelight-БД + стабільний Android**. Переписування Go-ядра й перенесення повноцінного UI — наступні окремі PR, не об'єднувати їх в один великий ризикований реліз.

## Фактична архітектура

| Частина | Статус |
|---|---|
| `:app` Android | Робочий Compose Android UI, SQLDelight через `:shared`, SQLCipher/Keystore, збережені PDF/backup/Go-виклики |
| `:shared` | KMP для Android/JVM/iOS; common domain, SQLDelight-схема, CRUD, `Flow` |
| `:desktopApp` | Desktop JVM host збирається на Windows; повний бізнес-UI ще не перенесено |
| iOS | Framework і SQLCipher-тести проходять на симуляторі; runnable Xcode host та фізичний пристрій — пізніше |
| Розрахунки | Go/AAR — єдине поточне runtime-джерело; KMP-перенесення не починати без parity fixtures |
| Синхронізація | **Не реалізовано**; лише метадані UUID/timestamps/tombstones для майбутнього етапу |

### Сховище й безпека

- SQLDelight **2.4.1** — чинна Android production-runtime база; Room runtime та KSP Room compiler прибрано.
- SQLCipher забезпечує шифрування на Android, iOS Simulator і Windows/JVM у перевірених тестах. Керування ключем iOS/Windows для production, фізичний iOS-пристрій, ліцензійні/native packaging перевірки та переносимість encrypted DB між платформами ще не завершені.
- Таблиці `clients`, `projects`, `room_measurements`, `price_items`, `estimates` мають локальний `id`, стабільний UUID, `updatedAt` і `deletedAt`.
- Протокол синхронізації, мережеві запити, сервер, акаунти та конфлікти — **поза поточним PR**. Offline-first залишається обов'язковим.
- Тестова БД не переноситься: новий файл `andrey_ceilings_sqldelight_v2.db`; старі файли залишаються на диску, але не використовуються як джерело автоматичного імпорту.
- Формат захищених паролем переносних резервних копій `.stpb` збережено. Ручний імпорт і сценарії аварій/конкурентних записів потрібно ще доробити й перевірити.
- Робочий Android більше не використовує псевдо-Room `openHelper` / `invalidationTracker`. Адаптація старих Android UI-моделей до shared репозиторіїв лишається до реальної міграції екранів.

Технічна специфікація SQLDelight та критерії приймання — [ROOM_TO_SQLDELIGHT_CUTOVER.md](ROOM_TO_SQLDELIGHT_CUTOVER.md), перевірки SQLCipher — [SQLDELIGHT_ENCRYPTION_SPIKE.md](SQLDELIGHT_ENCRYPTION_SPIKE.md).

## Етап 1 — стабілізація Android (PR #22)

**Основні технічні критерії приймання виконані 29.10.2026.** Злиття в `main` залишається за рішенням власника. Подальші розширені тести стійкості не позначені як пройдені.

- [x] KMP/CMP фундамент, Windows Desktop host, iOS Simulator framework, спільні моделі та SQLDelight-репозиторії.
- [x] Підключити реальний Android UI до SQLDelight, видалити Room runtime/KSP, зберегти Go/AAR.
- [x] Перевірити запуск, базові CRUD, резервування/експорт/імпорт на фізичному Android. Редагування клієнтів, об'єктів, замірів і кошторисів UI ще не реалізовано.
- [x] Додати на кожну таблицю UUID, `updatedAt`, `deletedAt`, soft deletion із tombstones.
- [x] Прибрати діагностичні Room-стилізовані адаптери; зберегти необхідний UI-шар до поступового CMP-перенесення.
- [x] Додати регресійні тести метаданих і пошкоджених backup snapshots.
- [x] Android Debug/Release APK та instrumentation tests, iOS Simulator framework/tests, Windows Desktop/JVM тести успішно пройшли на фінальному кодовому коміті `f905dd85` — runs `40877796304`, `40877796098`.
- [x] Власник перевірив фізичний Android APK: створення/видалення записів, редагування прайсу, збереження після перезапуску, автоматичні резервні копії, експорт/відновлення БД і PDF працюють.
- [ ] Додаткове hardening перед production: crash-safe restore, одночасні записи, зайнятий WAL і переривання під час заміни файла не підтверджені цією перевіркою.
- [ ] Дозвіл власника на злиття в `main`.

## Етап 2 — Go/AAR → Kotlin Multiplatform (новий PR)

- [ ] Зафіксувати результати поточного Go для нормальних, граничних та історичних прикладів.
- [ ] Поступово перенести геометрію, матеріали, профілі, складність, опції, додаткові роботи та підсумки.
- [ ] Зафіксувати правила округлення, грошових сум, одиниць, порожніх/некоректних полів.
- [ ] Усунути відому невідповідність додаткових робіт між UI, збереженим кошторисом і PDF.
- [ ] Замінити runtime-виклики й прибрати Go/AAR **лише** після підтвердження parity та регресійних тестів.

## Етап 3 — реальний shared UI (окремі PR)

- [ ] Почати з прайс-листа (доступний повний CRUD), потім переносити інші екрани невеликими частинами.
- [ ] Не втратити тему (світла/темна/системна), Inter, картки та списки, календар, навігацію, відступи, edge-to-edge, клавіатуру, поведінку скролу.
- [ ] Поступово реалізувати клієнтів, об'єкти, заміри, кошториси, дублікат/редагування/PDF у CMP.
- [ ] Windows: повноцінний Desktop UI, пакування, нативні залежності, захист ключів.
- [ ] iOS: runnable Xcode host, Swift/Compose UI, key storage, файл/backup/PDF адаптери, тести на фізичному пристрої.

## Етап 4 — синхронізація (ще пізніше)

- [ ] Обрати transport/provider, авторизацію та схему розмежування користувачів.
- [ ] Визначити курсори змін, зв'язки локальних ID з UUID, порядок ідемпотентного застосування змін.
- [ ] Реалізувати віддалені видалення, повтори, мережеву безпеку й вирішення конфліктів.
- [ ] До реалізації зберегти всі основні можливості без інтернету.

## Критерії злиття

Наявні Android-екрани та основні сценарії працюють на пристрої; БД зашифрована; резервування/імпорт не знищують дані при помилці; фінальний Android, Windows та iOS Simulator CI — зелений; diff переглянуто; власник схвалив злиття. PR #21 не переносити повністю: його можна використовувати тільки як довідку.
