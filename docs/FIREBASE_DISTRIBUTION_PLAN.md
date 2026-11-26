# Libellaro — Firebase App Distribution, оновлення та Crashlytics

Дата погодження: 26.11.2026. Власник погодив 21 продуктове рішення. [Issue #174](https://github.com/aleks-nl5/andrey-ceilings-android/issues/174).

**Статус:** планування завершено; реалізація відкладена до завершення основних функціональних етапів Android-first roadmap. Не є дозволом на код, створення Firebase-проєкту чи зміну CI. Окреме погодження власника перед кожним bounded implementation PR. Не змінювати P6, поточний процес QUICK/FULL, підписані APK для PR, наявний backup/restore або shared бізнес-логіку в межах цього документа.

## 1. Канонічні рішення власника

| № | Вибір | Контракт |
|---|---|---|
| 1 | А | Публікація тільки ручним запуском GitHub Actions; без автоматичної публікації після merge або тегу. |
| 2 | Б | Неблокуюча фонова перевірка при запуску, не частіше разу на 24 години; ручна перевірка у налаштуваннях не залежить від інтервалу. |
| 3 | В | Одноразове компактне повідомлення для кожної виявленої нової версії; постійний індикатор на іконці налаштувань до встановлення; не переривати роботу майстра. |
| 4 | А | Окремий пункт «Оновлення» в налаштуваннях з індикатором; окремий екран керування. |
| 5 | В | Три режими завантаження: ручний, автоматичний через Wi-Fi, автоматичний через будь-яке інтернет-з'єднання. Інсталяція завжди за явним підтвердженням користувача. |
| 6 | Б | Default: автоматичне завантаження через Wi-Fi. |
| 7 | Б + уточнення | Автоматична тимчасова локальна резервна копія безпосередньо перед встановленням; видаляти тільки після успішного запуску нової версії та перевірки цілісності БД. При помилці зберегти; інші копії не чіпати. |
| 8 | Б | Якщо backup не вдалося створити — попередити й дозволити користувачу явно продовжити без копії або скасувати. |
| 9 | Б | Автоматично відновити перерване завантаження за відповідної мережі, якщо підтримується; інакше почати заново. Врахувати закриття застосунку й перезапуск процесу. |
| 10 | А | Якщо під час завантаження з'явилася новіша версія, завершити вже вибрану і запропонувати її встановити; не переключати активне завантаження. |
| 11 | А | Видаляти завантажений APK після успішної інсталяції; після скасування чи помилки залишити для повторної спроби. |
| 12 | В | Персональні запрошення (default) і багаторазові посилання через Firebase Console; без власної системи реєстрації. |
| 13 | В | Автоматична чернетка короткого user-facing changelog від попереднього випуску, яку можна відредагувати перед публікацією. |
| 14 | Б | Публікація лише після успішних Android FULL і CMP FULL на точному коміті main; повторно FULL перед публікацією не запускати. Фізичне приймання кожного релізу не є окремим обов'язковим publication gate; власник вирішує. |
| 15 | В | versionName вручну; versionCode автоматично, строго монотонно в єдиній системі GitHub/Firebase, включно із сумісністю з тестовими APK. |
| 16 | Б | Архів усіх опублікованих стабільних APK у GitHub Releases приватного репозиторію: версія, коміт, changelog. Не архівувати проміжні/PR APK. |
| 17 | А | Якщо пропущено кілька версій — одразу найновіша стабільна, без проміжних інсталяцій; міграції з усіх раніше опублікованих стабільних версій без втрати даних. Рішення 10 діє для вже активного завантаження. |
| 18 | В | При першому запуску компактно запропонувати підключити оновлення через Google-акаунт; можна відмовитись і підключити пізніше. Авторизація потрібна лише для оновлень. |
| 19 | Б | Firebase для авторизації тестувальника, поширення та перевірки версій; власне завантаження/встановлення за потреби, лише через documented/supported interfaces. Обов'язковий F0 до основної реалізації. |
| 20 | В | Впровадження після завершення всіх основних функціональних етапів; не відволікатися від поточної дорожньої карти. |
| 21 | Б | App Distribution + Crashlytics. Діагностика тільки opt-in, з можливістю вимкнути; жодних персональних даних клієнтів; без Analytics та Performance Monitoring. |

## 2. Межі та UX

- Безкоштовний Spark, без Play Console, без обов'язкового Obtainium/App Tester, один закритий канал stable-testers; Development APK залишаються в GitHub Actions. Firebase не є production store, sync чи backend для бізнес-даних. Не вмикати платні ресурси без нового погодження.
- Android-only інтеграція, ізольована від спільних KMP бізнес-моделей і Windows/iOS; shared CMP UI/resources для спільних представлень, Android platform adapter для SDK та installer. Збірка для майбутнього Google Play не повинна містити App Distribution tester SDK; перевірити окремий build variant у F0.
- Offline-first: старт, Save, клієнти, об'єкти, стелі, кошториси та PDF не залежать від Firebase, авторизації чи мережі. Фонові помилки не блокують і не створюють нав'язливих діалогів. Ручна перевірка показує результат/помилку. Немає примусових оновлень.
- Екран «Оновлення»: поточна/доступна версія, changelog, ручна перевірка, налаштування автоперевірки й режиму завантаження, авторизація, прогрес/готовність/повторна спроба, явна дія встановлення. Компактні повідомлення та індикатори, локалізація, light/dark, a11y; фінальний макет і стани погодити перед кодом.
- Стан завантаження не повинен втрачатися при process death; поважати вибраний тип мережі й не запускати дублікати. Розрізняти доступність, завантаження, готовність, інсталяцію, скасування та помилку. Не показувати інсталятор автоматично від фонового завантаження. Не зберігати паролі Google.
- Відкликання доступу припиняє отримання нових збірок, не блокує локальний Libellaro. Посилання-запрошення не є публікацією вихідного коду. Поширений APK неможливо гарантовано відкликати з пристрою.

## 3. Release та безпека даних

Manual dispatch обирає точний main SHA; gate перевіряє, що обидва FULL завершені успішно саме на ньому, а не на іншому SHA чи лише на PR. Не запускати нові FULL у publishing workflow. Збирати/публікувати один і той самий підписаний артефакт та архівувати його в приватному Releases; зв'язок з SHA, versionName/versionCode, digest та changelog. Не публікувати draft/неперевірений код. Визначити атомарну/ідемпотентну поведінку повторного запуску, помилки архівації чи Firebase upload у F2. Верифікувати приватність репозиторію перед архівацією; якщо не приватний — погодити окремий приватний архів. Чернетку changelog має бути видно до остаточного запуску публікації.

Зберегти applicationId, підписувальний ключ та сумісність тестових/стабільних APK. Узгодити єдиний allocator versionCode з наявною CI, без колізій, downgrade чи перевищення Android limits. Не видавати нову збірку під старим code; повторна доставка того самого артефакту не є новим релізом. Перевірити підпис, package identity, code та цілісність перед системним installer. Без тихої інсталяції, обходу Android дозволів чи device verification. Не розкривати publisher credentials у APK, repo, логах; мінімальні permissions, бажано GitHub OIDC/Workload Identity Federation після перевірки сумісності та безкоштовності. Не передавати service account тестувальникам.

Тимчасова копія використовує існуючий безпечний backup API, не копіювати відкриту SQLite/WAL вручну; зберегти SQLCipher/ключі, snapshot consistency, окремий lifecycle/retention без витіснення звичайних копій. Створювати перед інсталяцією, не при фоновому download. У F4 погодити протокол бар'єра записів/перевірки після міграції, захист при process death, реальний recovery шлях за невдалого старту та явне підтвердження ризику без backup. Не видаляти копію тільки за installer success або зміною versionCode: потрібен успішний запуск і перевірка БД. Не робити автоматичний destructive restore/downgrade. Після успіху видаляти лише тимчасову копію відповідного оновлення; звичайний backup schedule, retention та restore не змінювати.

Crashlytics: default collection disabled до згоди; налаштування дозволяють відкликати згоду. Перевірити можливе локальне накопичення unsent reports, видалення невідправлених даних і фактичний момент відправлення; не відправляти діагностику після відмови. Не додавати ідентифікатори клієнтів, контакти, адреси, креслення, ціни/кошториси, DB keys, токени, персональні дані в logs, keys, exception messages чи reports. Перед релізом перевірити автоматично зібрані технічні дані, redaction, consent UI та privacy disclosure. Не заявляти нульової телеметрії SDK без перевірки.

## 4. F0 — обов'язковий feasibility gate

Не вважати погоджений UX доведено реалізовним. Firebase App Distribution Android tester SDK має Beta-обмеження; стандартний download/update flow може запускати installer одразу, а publisher API не є безпечним client API. Перевірити актуальні офіційні SDK/API, можливість authenticated tester download без App Tester, можливість окремого download та install, автоматичне фонове завантаження з network constraints, retry/resume і process death, доступність release notes, відмову/відкликання доступу, сумісність Android/targetSdk, підпису і варіантів збірки. Не вбудовувати publisher credentials, не використовувати undocumented endpoints, scraping чи витік тимчасових download URLs. Якщо вимоги неможливо виконати на підтримуваних API, зафіксувати альтернативи (зокрема інший захищений delivery adapter) та отримати рішення власника, перш ніж змінювати контракт. Не додавати платний backend без погодження.

Орієнтири, які треба актуалізувати на F0: Spark/безкоштовність App Distribution, ліміти тестувальників і збірок, 150-денне зберігання релізу; expiry обмежує повторне завантаження, а не роботу встановленого застосунку. Окремо перевірити чинні Android developer verification / limited distribution вимоги, країни, дати, ліміти пристроїв і спосіб безкоштовної реєстрації. Firebase не замінює developer verification. Не обіцяти постійну безкоштовність або доставку поза сервісними лімітами. При expiry або відсутності релізу використати погоджений F2 процес повторного поширення архіву, не публікувати старі APK з іншою ідентичністю.

Офіційна документація для повторної перевірки:
- [App Distribution](https://firebase.google.com/docs/app-distribution)
- [Android in-app alerts and updates](https://firebase.google.com/docs/app-distribution/set-up-alerts)
- [Tester guide](https://firebase.google.com/docs/app-distribution/get-set-up-as-a-tester)
- [CLI distribution](https://firebase.google.com/docs/app-distribution/android/distribute-cli)
- [Pricing](https://firebase.google.com/pricing)
- [Crashlytics collection and customization](https://firebase.google.com/docs/crashlytics/customize-crash-reports?platform=android)
- [Android developer verification](https://developer.android.com/developer-verification)

## 5. План виконання

| Етап | Результат |
|---|---|
| F0 | Ізольований технічний прототип та evidence; рішення про supported delivery architecture, Android verification, privacy, витрати й tradeoffs. |
| F1 | Spark project, Android registration, доступи та група, перша ручна доставка підписаного APK на фізичний пристрій. |
| F2 | Manual release workflow, exact-SHA FULL gate, version allocator, редагований changelog, приватний архів, retry/expiry runbook. |
| F3 | Android update adapter, добровільна авторизація, окремий локалізований екран, повідомлення, 24h/manual check, режими download, retry та explicit installer. |
| F4 | Тимчасовий backup, перевірка міграцій/цілісності, recoverability, безпечне очищення. F3 installer production gate залежить від F4. |
| F5 | Crashlytics opt-in, вимкнення, privacy/redaction proof, без сторонніх Firebase сервісів. |
| F6 | Інтеграційне приймання, review, фізичний Android APK, точні CI SHA, release documentation. |

Тести: зберегти чинну scoped CI policy, focused unit/domain/platform/DB recovery tests, без нового широкого UI suite. Перевірити точний SHA FULL gate (missing/failed/wrong SHA), versionCode collision, ідентичність підпису й APK, release retries; offline/unauthorized, 24h/manual, once-per-version + persistent indicator, три network modes, interruption/resume, новіша версія під час download, cancellation/installer failure/success cleanup, skip-version migrations з усіх stable schemas, backup failure override/cancel, failed startup retains backup, existing copies unchanged, consent off/on/revoke і відсутність sensitive data. Physical APK acceptance у light/dark, перевірка реального оновлення та збереження даних; незалежний review, owner-only merge. Документація не є evidence пройдених тестів.

## 6. Не входить до scope

Cloud database, синхронізація, власна account system, Analytics, Performance Monitoring, Google Play publication, Windows/iOS distribution, автоматична публікація main, примусові оновлення, зміна звичайного backup/restore, сторонні платні сервіси. Усі потребують окремого рішення.

