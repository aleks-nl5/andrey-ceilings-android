# План перенесення функцій з оригіналу

## З оригіналу підтверджено
- Dashboard із нагадуваннями/статистикою.
- CRM: клієнти та об'єкти, статуси замовлень, аванс, дати заміру й монтажу.
- Замір кімнат: прямокутник, квадрат, L-подібна та довільна геометрія; сторони/діагоналі; креслення, фото, нотатки.
- Матеріали полотна, типи профілю, складність та опції (світлові лінії, тіньовий/парящий профіль, люстри, споти, труби, карниз, тверді стіни).
- Розрахунок за прайс-листом, кошторис та експорт/друк.
- Керування прайс-листом, профілем майстра, темою, мовою та резервними копіями.
- Дії з контактами/телефоном, навігація до адреси та WhatsApp/Viber.
- Offline-first storage у вебверсії.

## Етапи
1. Нативна оболонка Compose та Go-модуль — стартова база.
2. Перенести всі доменні моделі й правила калькулятора в Go; покрити тестами.
3. [У роботі] JSON API та скрипт AAR додані у v4; згенерувати AAR через gomobile і перевірити Go-виклик на Android.
4. Далі: Room — клієнти, об'єкти, заміри, прайс-лист, профіль майстра.
5. Редактор замірів і креслення пальцем; фото через Android Photo Picker.
6. Кошторис/PDF, share sheet, телефонні контакти та навігаційні intents.
7. Імпорт/експорт резервної копії, локалізація, теми, тестування офлайн.

## v5 — encrypted persistence foundation

- [x] Room entities and DAOs for clients, projects, room measurements, and price items.
- [x] SQLCipher-backed Room database.
- [x] Random database passphrase protected by AES-GCM with an Android Keystore wrapping key.
- [x] Disable Android Auto Backup for the app data.
- [x] Startup check and Settings status for encrypted database opening.
- [ ] Connect client/object/measurement screens to live DAO flows and CRUD forms.
- [ ] Add explicit Room migrations as the schema evolves.
- [ ] Implement encrypted export/import with recovery-key design.
- [x] Photos, drawings, and PDFs are intentionally out of scope for file encryption; only the database is encrypted.
- [ ] Build and test on an Android SDK/device; this environment does not have Gradle installed.


## v6 — connected local data screens

- [x] Objects tab observes projects and clients from Room.
- [x] Add clients and projects; project creation can link to an existing client or create one by name.
- [x] Cycle project status and delete clients/projects.
- [x] Measurements tab observes and creates rectangular room measurements linked to a project, and supports deletion.
- [x] Removed file-encryption requirement for photos, drawings, and PDFs; encryption scope is the local database only.
- [ ] Add editing forms and safer delete confirmations / client-project referential rules.
- [ ] Connect editable price list to DAO and Go calculator.
- [ ] Build and smoke-test Android app on SDK/device.


## v7 — demo price list

- [x] Seed a starter price list (22 example entries) only when the table is empty.
- [x] Native price list screen groups items by category and shows unit price in UAH.
- [x] Add, edit, and delete price items in the encrypted Room database.
- [x] Clearly label demo prices as illustrative and editable; don't overwrite prices once the table has entries.
- [ ] Connect selected material/profile and add-on prices to the estimate calculator and Go engine.
- [ ] Build and smoke-test Android app on SDK/device.


## Етап v9 — кошториси замовлень
- [x] Додати збереження кошторису в зашифровану БД із прив’язкою до об’єкта.
- [x] Зберігати знімок параметрів кімнати, матеріалу, профілю та розбивку вартості.
- [x] Додати список кошторисів та агреговану суму по об’єкту.
- [x] Додати міграцію БД без destructive fallback.
- [ ] Перевірити Android-збірку на машині з Android SDK/Gradle.
- [ ] Наступне: редагування/дублювання кошторисів і експорт у PDF.

Примітка безпеки: шифрується база даних; фото, креслення та PDF окремо не шифруються за вимогою власника проєкту.


## Етап v10 — керування кошторисами та PDF

- [x] Редагування назви збереженого кошторису.
- [x] Дублювання кошторису з усіма збереженими параметрами та сумами.
- [x] Експорт у PDF через системний Android-дialog збереження.
- [x] Зберегти шифрування БД через SQLCipher; окреме шифрування PDF не входить у погоджений обсяг.
- [ ] Перевірити Gradle-збірку та PDF на Android-емуляторі/пристрої.
- [ ] Наступним етапом: повний деталізований список позицій з кількостями й цінами, після чого перевірка фактичного Go AAR під Android.


## v11 — деталізація кошторисів
- [x] Зберігати позиції кошторису як історичний знімок із кількістю, одиницею, ціною та сумою.
- [x] Відображати позиції у списку кошторисів.
- [x] Виводити позиції в PDF із поділом на сторінки.
- [ ] Перевірити повну Android-компіляцію та PDF на фізичному пристрої/емуляторі.
- [ ] Згенерувати та перевірити `ceilings-core.aar`, щоб підтвердити використання Go-рушія на Android.
