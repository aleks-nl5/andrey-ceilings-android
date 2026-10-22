# Andrey Ceilings — Android native + Go

Це перший нативний Android-порт на основі завантаженого React/Vite-проєкту `andrey-ceilings`.

## Стек
- Kotlin + Jetpack Compose + Material 3 — нативний інтерфейс, без WebView.
- Go — цільова бізнес-логіка у `go-core/`.
- Room і DataStore включені як залежності для наступного етапу локального зберігання.
- Gradle — збирання Android-застосунку.

## Відкрити та запустити
1. Розпакуйте ZIP.
2. Відкрийте папку проєкту в Android Studio (актуальна версія з підтримкою AGP 8.9).
3. Дочекайтеся Gradle Sync та встановіть Android SDK 35, якщо Studio попросить.
4. Запустіть конфігурацію `app` на емуляторі або пристрої.

Зібрати debug APK:
```bash
./gradlew assembleDebug
```
Файл очікується в `app/build/outputs/apk/debug/app-debug.apk`.

## Перевірка Go-модуля
```bash
cd go-core
go test ./...
```

## Поточний стан — чесно
Це runnable UI-прототип/каркас, а не завершений порт усіх функцій вебзастосунку. Зараз є нативна навігація та початкові екрани панелі, об'єктів, замірів, калькулятора і налаштувань. Екранні списки ще містять демонстраційні дані; локальна база, фото, редактор креслення, повний кошторис/PDF, резервне копіювання, контактні дії та повна локалізація ще не перенесені.

У v4 додано gomobile-сумісний JSON API в `go-core/mobileapi` і скрипт генерації AAR. `CeilingCalculator` намагається викликати Go binding, якщо AAR уже згенерований і доступний; інакше використовує прозорий Kotlin fallback, щоб прототип і далі запускався. Сам AAR не вкладено, тому Go-виклик ще треба перевірити на Android-пристрої.

Згенерувати AAR (потрібні Go, Android SDK/NDK та gomobile):
```bash
go install golang.org/x/mobile/cmd/gomobile@latest
gomobile init
./scripts/build-android-aar.sh
```
Скрипт створює `app/libs/ceilings-core.aar`; Gradle автоматично підключає файл, якщо він існує. API навмисно передає/повертає JSON-рядки, щоб мінімізувати проблеми сумісності типів у Java binding.

## Що переносимо з оригіналу
Оригінал містить типи клієнтів, об'єктів і замірів; геометрію кімнат (прямокутна, квадратна, L-подібна, довільна); матеріали та профілі; опції освітлення; прайс-лист; фото; кошторис; контакти; експорт/друк; теми та українську/інші локалі. Це зафіксовано як цільова функціональність, але ще не вся реалізована в Android-порті.


## Go calculation engine (expanded in v3; Android bridge in v4)
`go-core/core/calculator.go` now contains the source-derived material/profile price list, room geometry for rectangle/square/L-shaped/custom rooms, complexity add-ons, and every standard option from the original calculator. Tests cover the reference rectangle calculation and avoiding duplicate charges for shadow/floating profiles.

Run:
```bash
cd go-core
go test ./...
```
The Android UI is still wired to a temporary Kotlin adapter until the gomobile AAR is generated and added to `app/libs`. The Go engine is implemented and testable independently; native integration is a separate build step.


## Що змінилося у v4
- Додано `go-core/mobileapi`: стабільний JSON-вхід/вихід для розрахунку геометрії та кошторису.
- Додано unit-тести JSON-моста й обробки некоректного вводу.
- Додано `scripts/build-android-aar.sh` і умовне підключення `app/libs/ceilings-core.aar` у Gradle.
- Kotlin adapter спершу пробує Go binding і явно маркує рушій у моделі результату; до появи AAR працює fallback.

**Важливо:** у цьому середовищі перевірено `go test ./...`, але не згенеровано AAR і не зібрано APK. Для наступного етапу треба згенерувати AAR, перевірити ім'я Java-класу в gomobile binding, а потім перенести клієнтів/об'єкти/заміри в Room.

## Encrypted local database (v5)

The Android data layer now uses Room on top of SQLCipher. The database file is `andrey_ceilings_secure.db` and includes tables for clients, projects, room measurements, and price items. DAO interfaces expose coroutine/Flow-based access for the UI and repositories.

### Key handling

- A random 256-bit SQLCipher passphrase is generated on first launch.
- The passphrase is wrapped with AES-256-GCM using a key held by Android Keystore.
- Only the wrapped passphrase is stored in app-private preferences; the SQLCipher passphrase is not hard-coded.
- Android system backup is disabled (`allowBackup=false`) to prevent copying the encrypted database without a compatible key lifecycle.
- The app attempts to open the encrypted database on startup and reports success/failure in Settings.
- There is deliberately no destructive Room migration fallback. Future schema changes must add explicit migrations.

### Security notes / limitations

This is local-at-rest protection, not a substitute for device screen lock. If app data or the Keystore wrapping key is deleted (for example, on uninstall, device reset, or some device restore scenarios), the local database may no longer be decryptable. A future backup/export feature must use a separately encrypted backup format and a user-controlled recovery secret; do not export the raw database or the wrapped key as a recovery mechanism. Photos, drawings, PDFs, and temporary exports are intentionally not encrypted as separate files in this project scope. The database itself remains encrypted.

### Dependencies added

- `net.zetetic:android-database-sqlcipher:4.5.4`
- `androidx.sqlite:sqlite:2.4.0`
- Room compiler configured through Kotlin KAPT.

The encrypted database opens lazily on an IO dispatcher. Do not access DAO operations on the main thread.


## v6 data screens

The Objects tab now reads live Room flows and supports adding clients/projects, linking a project to a client by name, cycling project status, and deleting records. The Measurements tab supports adding a rectangular measurement linked to a project and deleting measurements. Photo/drawing/PDF file encryption is explicitly out of scope; SQLCipher protects the database only.


## v7 — demo price list

- Added 22 illustrative price items for canvas materials, profiles, lighting, and extra works.
- Demo prices are seeded only when the encrypted price table is empty; existing/user-edited prices are not overwritten on app start.
- Added a native Price tab to view, add, edit, and delete price items. Values are in UAH and are clearly marked as examples that should be adjusted before real estimates.
- Price items are stored in the SQLCipher-encrypted Room database.
- Go tests pass. Android Gradle/APK build still requires verification in Android Studio with Android SDK installed.


## v8 — прайс-лист підключено до калькулятора

- Калькулятор спостерігає за позиціями прайсу з Room; зміна демонстраційної/користувацької ціни матеріалу чи профілю синхронізується з полями калькулятора.
- Передаємо ціни матеріалів, профілів та стандартних додаткових робіт у Go JSON API.
- Додано поля кількості/довжини для світлових ліній, тіньового та парящого профілю, люстр, GX53, обходів труб, ніш, додаткових кутів і складних стін.
- Додано вибір складності (пряма, багаторівнева, криволінійна) та окреме відображення вартості складності й додаткових робіт.
- Go JSON API має тест для перевірки перевизначених цін і вартості опцій.

**Обмеження:** без згенерованого `app/libs/ceilings-core.aar` калькулятор використовує Kotlin fallback, який рахує тільки полотно та профіль; повний розрахунок додаткових робіт і складності вимагає Go AAR. Цю Android-інтеграцію й APK ще треба перевірити в Android Studio.


## v9: збережені кошториси
- Додано таблицю `estimates` з міграцією Room 1 → 2 без видалення наявних даних.
- Кошторис зберігає об’єкт, назву кімнати, матеріал, профіль, складність, розміри, площу, периметр, розбивку вартості та значення додаткових робіт.
- Вкладка «Кошториси» показує записи та підсумок за кожним об’єктом. Видалення об’єкта каскадно видаляє пов’язані кошториси.
- Дані залишаються у зашифрованій SQLCipher-БД; окреме шифрування фото, креслень і PDF не планується.
- Android APK потрібно зібрати й перевірити в Android Studio; у цьому середовищі збірка не підтверджена.


## v10 — кошториси: редагування, дублювання та PDF

- Додано редагування назви збереженого кошторису без зміни зафіксованих параметрів і сум.
- Додано дублювання кошторису як окремого запису, з копіюванням збережених розмірів, матеріалів, розбивки та вартості.
- Додано експорт кошторису в PDF через системний діалог збереження Android (`CreateDocument`). PDF містить об'єкт, назву кошторису, параметри кімнати, матеріал, профіль, розбивку вартості та загальну суму. PDF-файли окремо не шифруються відповідно до узгодженого обсягу.
- Для PDF використовується системний шрифт Android із підтримкою кирилиці.
- Додано оновлення кошторису через Room DAO. Зміни схеми БД не потрібні, тому номер схеми не змінюється.

Перевірки в цьому середовищі: `go test ./...` проходить. Gradle/Android SDK не доступні як готове середовище збірки, тому Android APK і виконання PDF-експорту на пристрої ще не підтверджені.


## v11 — деталізований кошторис

- Під час збереження кошторису формується незмінний знімок позицій: назва, кількість, одиниця, ціна за одиницю та сума.
- Розбивка зберігається в `detailsJson` разом із кошторисом у зашифрованій Room/SQLCipher БД; зміна прайсу надалі не переписує історичний кошторис.
- Екран кошторисів показує детальні рядки, а PDF містить кількість, ціну за одиницю, суму й автоматичне розбиття на сторінки. Для старих записів без деталізації залишається агрегований fallback.
- Додано рядок коригування округлення, якщо сума окремих округлених рядків відрізняється від зафіксованого підсумку.
- Версія Android застосунку: `0.6.0` (`versionCode = 5`).

**Перевірка:** `go test ./...` проходить. У цьому середовищі Gradle/Android SDK для повної компіляції Android не доступний, тому APK не заявляється як зібраний.

## Automated APK build (GitHub Actions)
A workflow is included at `.github/workflows/android-debug-apk.yml`. After pushing this project to a GitHub repository, open **Actions → Build Android debug APK → Run workflow**. When the run succeeds, download the `andrey-ceilings-debug-apk` artifact. This workflow builds a debug APK only; it does not generate the optional gomobile AAR, so the app may use the documented Kotlin fallback unless `app/libs/ceilings-core.aar` is provided.
