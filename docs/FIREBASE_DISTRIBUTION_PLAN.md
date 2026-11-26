# Libellaro — Firebase App Distribution, оновлення та Crashlytics

Дата погодження: 26.11.2026. Власник погодив 30 продуктових рішень (21 початкове та 9 уточнень №22–30). [Issue #174](https://github.com/aleks-nl5/andrey-ceilings-android/issues/174).

**Статус:** планування завершено; власник дозволив підготовчий аудит F0 паралельно з #143. Ізольований runtime-прототип, створення Firebase-проєкту і production F1–F6 потребують окремого погодження; production після основних функціональних етапів Android-first roadmap. Не є дозволом на зміну CI. Окреме погодження власника перед кожним bounded implementation PR. Не змінювати P6, поточний процес QUICK/FULL, підписані APK для PR, наявний backup/restore або shared бізнес-логіку в межах цього документа.

## 1. Канонічні рішення власника

Початкові рішення №1–21 збережено як історію. №22–30 нижче мають пріоритет у разі суперечності (зокрема №5–11, 16, 19–20).

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

## 1.1. Уточнення власника №22–30 (чинні)

| № | Вибір | Контракт |
|---|---|---|
| 22 | А | Стандартний Firebase App Distribution Android tester SDK; адаптувати UX до documented API. Без Cloudflare, власного сервера, publisher credentials у клієнті або недокументованих API. |
| 23 | А | Неблокуюча автоматична перевірка при запуску не частіше 24 год + ручна; оновлення запускає тільки майстер. Прибрати три режими та автоматичне завантаження (№5–6). Backup перед SDK download, не після нього (уточнення №7). |
| 24 | Б | Через Wi-Fi без додаткового попередження; при мобільному інтернеті підтвердження трафіку перед запуском SDK. Зміна мережі під час SDK flow — перевірити, не обіцяти контроль, якого немає. |
| 25 | А | Блокувати роботу/записи до snapshot і протягом download/install; компактний прогрес. Після підтвердженого завершення без install та здорової БД розблокувати. Бар'єр має охопити всі writer paths та process death, а не лише UI. |
| 26 | Б | Окремий приватний `libellaro-releases` для stable APK; основний репозиторій і CI публічні/незмінні. Це не робить development artifacts чи вихідний код приватними. |
| 27 | А | При підтвердженій помилці/скасуванні — ручна повторна спроба зі свіжим backup; не автоповторювати і не реалізовувати власний resume. SDK керує частковими файлами; перевірити cleanup (уточнення №9–11). |
| 28 | А | Видалити тимчасову копію після підтвердженого скасування/невдачі без install та перевірки здорової старої БД; при невідомому результаті або помилці запуску/БД зберігати. Після success — лише новий startup + migration + integrity. |
| 29 | Б | Нагадування за 15 днів до expiry останнього stable APK; ручна повторна доставка того самого архівного APK без rebuild/version/signature/digest change або повторних FULL. Перевірити чи повторний upload поновлює expiry; якщо ні — повернутися до власника. Нагадування налаштувати після першої публікації. |
| 30 | А | Явне підтвердження до початку; прогрес без кнопки примусового cancel download, якщо SDK не має supported API. Скасування інсталяції — системний Android installer; не вважати Back/timeout/process death доказом скасування. |

№10: не перемикати активний реліз мовчки; довести можливість зв'язати перевірений реліз і SDK install або повернутися до власника. №11: APK lifecycle під контролем SDK; не обіцяти власне видалення/збереження файла. №20: дозволено лише підготовчий аудит F0 зараз, production відкладено. Усі інші рішення чинні.

## 2. Межі та UX

- Безкоштовний Spark, без Play Console, без обов'язкового Obtainium/App Tester, один закритий канал stable-testers; Development APK залишаються в GitHub Actions. Firebase не є production store, sync чи backend для бізнес-даних. Не вмикати платні ресурси без нового погодження.
- Android-only інтеграція, ізольована від спільних KMP бізнес-моделей і Windows/iOS; shared CMP UI/resources для спільних представлень, Android platform adapter для SDK та installer. Збірка для майбутнього Google Play не повинна містити App Distribution tester SDK; перевірити окремий build variant у F0.
- Offline-first: старт, Save, клієнти, об'єкти, стелі, кошториси та PDF не залежать від Firebase, авторизації чи мережі. Фонові помилки не блокують і не створюють нав'язливих діалогів. Ручна перевірка показує результат/помилку. Немає примусових оновлень.
- Екран «Оновлення»: поточна/доступна версія, changelog, ручна перевірка, налаштування автоперевірки, авторизація, прогрес/помилка/ручна повторна спроба, явна дія «Оновити». Компактні повідомлення та індикатори, локалізація, light/dark, a11y; фінальний макет і стани погодити перед кодом.
- Зберігати durable update transaction state при process death, не запускати дублікати. Розрізняти доступність, backup, SDK download, installer, підтверджене скасування, failure, unknown та validated startup. SDK запускає installer тільки після явної дії майстра. Не обіцяти незалежний resume/cancel/ready-to-install. Не зберігати паролі Google.
- Відкликання доступу припиняє отримання нових збірок, не блокує локальний Libellaro. Посилання-запрошення не є публікацією вихідного коду. Поширений APK неможливо гарантовано відкликати з пристрою.

## 3. Release та безпека даних

Manual dispatch обирає точний main SHA; gate перевіряє, що обидва FULL завершені успішно саме на ньому, а не на іншому SHA чи лише на PR. Не запускати нові FULL у publishing workflow. Збирати/публікувати один і той самий підписаний артефакт та архівувати його в приватному Releases; зв'язок з SHA, versionName/versionCode, digest та changelog. Не публікувати draft/неперевірений код. Визначити атомарну/ідемпотентну поведінку повторного запуску, помилки архівації чи Firebase upload у F2. Основний репозиторій public; погоджено окремий приватний `libellaro-releases`. Перед архівацією перевірити його приватність і least-privilege cross-repo access; ніколи не публікувати stable APK у public Releases. Чернетку changelog має бути видно до остаточного запуску публікації.

Зберегти applicationId, підписувальний ключ та сумісність тестових/стабільних APK. Узгодити єдиний allocator versionCode з наявною CI, без колізій, downgrade чи перевищення Android limits. Не видавати нову збірку під старим code; повторна доставка того самого артефакту не є новим релізом. Перевірити підпис, package identity, code та цілісність перед системним installer. Без тихої інсталяції, обходу Android дозволів чи device verification. Не розкривати publisher credentials у APK, repo, логах; мінімальні permissions, бажано GitHub OIDC/Workload Identity Federation після перевірки сумісності та безкоштовності. Не передавати service account тестувальникам.

Тимчасова копія використовує існуючий безпечний backup API, не копіювати відкриту SQLite/WAL вручну; зберегти SQLCipher/ключі, snapshot consistency, окремий lifecycle/retention без витіснення звичайних копій. Створювати після бар'єра записів безпосередньо перед SDK download/install flow; фоновий download прибрано. У F4 погодити протокол бар'єра записів/перевірки після міграції, захист при process death, реальний recovery шлях за невдалого старту та явне підтвердження ризику без backup. Не видаляти копію тільки за installer success або зміною versionCode: потрібен успішний запуск і перевірка БД. Не робити автоматичний destructive restore/downgrade. Після успішної перевірки нової БД або підтвердженої невдачі/скасування без install та перевірки старої БД видаляти лише тимчасову копію відповідного оновлення; unknown/failed startup зберігати; звичайний backup schedule, retention та restore не змінювати.

Crashlytics: default collection disabled до згоди; налаштування дозволяють відкликати згоду. Перевірити можливе локальне накопичення unsent reports, видалення невідправлених даних і фактичний момент відправлення; не відправляти діагностику після відмови. Не додавати ідентифікатори клієнтів, контакти, адреси, креслення, ціни/кошториси, DB keys, токени, персональні дані в logs, keys, exception messages чи reports. Перед релізом перевірити автоматично зібрані технічні дані, redaction, consent UI та privacy disclosure. Не заявляти нульової телеметрії SDK без перевірки.

## 4. F0 — обов'язковий feasibility gate

Не вважати погоджений UX доведено реалізовним. Стандартний tester SDK обрано власником; окреме завантаження/інсталяція, custom delivery backend та автоматичний resume більше не є вимогами. Перевірити documented SDK/API, tester auth, release notes, exact release binding, progress і installer outcome, mobile warning, process death, duplicate flow, revocation, Android/targetSdk, підпис, SDK-managed APK cleanup і безпечний бар'єр записів/backup. Якщо SDK не дозволяє довести безпечний terminal outcome або коректний recovery, зупинити production та повернутися до власника. Не використовувати undocumented endpoints, scraping чи publisher credentials у APK. [Підготовчий аудит і сценарії прототипу](FIREBASE_F0_PREPARATION.md).

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
| F3 | Android update adapter, добровільна авторизація, окремий локалізований екран, повідомлення, 24h/manual check, ручне SDK update, mobile warning, progress/manual retry, explicit installer. |
| F4 | Бар'єр записів, тимчасовий backup до SDK download, перевірка міграцій/цілісності, recoverability, безпечне очищення. F3 installer production gate залежить від F4. |
| F5 | Crashlytics opt-in, вимкнення, privacy/redaction proof, без сторонніх Firebase сервісів. |
| F6 | Інтеграційне приймання, review, фізичний Android APK, точні CI SHA, release documentation. |

Тести: зберегти чинну scoped CI policy, focused unit/domain/platform/DB recovery tests, без нового широкого UI suite. Перевірити точний SHA FULL gate (missing/failed/wrong SHA), versionCode collision, ідентичність підпису й APK, release retries; offline/unauthorized, 24h/manual, once-per-version + persistent indicator, mobile warning, interruption/manual retry, новіша версія під час download, process death, SDK installer cancellation/failure/success та cleanup, skip-version migrations з усіх stable schemas, backup failure override/cancel, failed startup retains backup, existing copies unchanged, consent off/on/revoke і відсутність sensitive data. Physical APK acceptance у light/dark, перевірка реального оновлення та збереження даних; незалежний review, owner-only merge. Документація не є evidence пройдених тестів.

## 6. Не входить до scope

Cloud database, синхронізація, власна account system, Analytics, Performance Monitoring, Google Play publication, Windows/iOS distribution, автоматична публікація main, примусові оновлення, зміна звичайного backup/restore, сторонні платні сервіси. Усі потребують окремого рішення.

