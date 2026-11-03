# Andrey Ceilings

Offline-first застосунок для замірів, розрахунків і кошторисів натяжних стель. Android використовує Jetpack Compose та поступово спільний **Compose Multiplatform** UI. Бізнес-логіка, геометрія, SQLDelight та частина екранів — **Kotlin Multiplatform**. PR #66, #68 і #72 об’єднано з `main` 03.11.2026.

> **Актуальний план:** [CMP Measurement Editor — PR A/B](docs/CMP_MEASUREMENT_EDITOR_PLAN.md); [roadmap](docs/PROJECT_ROADMAP.md). Власник підтвердив, що БД повністю тестова: міграція старих креслень/Go/Room та старих архівів **не потрібна**. Автоматично видаляти БД при запуску не можна.

## Стек

- **Kotlin Multiplatform (`:shared`)**: `KmpCeilingCalculator` (геометрія, площа, периметр, матеріали/профілі, складність, додаткові роботи, грошові округлення), типізовані моделі та golden regression tests.
- **Android (`:app`)**: Jetpack Compose, Material 3, SQLDelight/SQLCipher, PDF через Android `PdfDocument`, резервне копіювання та відновлення `.stpb`.
- **SQLDelight 2.4.1 + SQLCipher**: одна бізнес-схема для Android, JVM/Windows та iOS Simulator, локальні UUID, `updatedAt`, `deletedAt`; мережевої синхронізації поки немає.
- **Windows (`:desktopApp`) та iOS**: KMP/SQLCipher фундамент, тестовані targets і поступова реалізація повного UI. Не вважати ці платформи завершеними застосунками.

**Go та gomobile/AAR більше не входять у проєкт.** Android-калькулятор викликає спільний `KmpCeilingCalculator.estimate` через невеликий UI-адаптер `CeilingCalculator`. Розрахунок не підмінюється спрощеним fallback.

## Збірка Android

Відкрити проєкт в Android Studio з підтримкою AGP 9.3.1, JDK 21 та актуальним Android SDK. Зібрати (з установленим Gradle 9.7.0, як у GitHub Actions; у репозиторії поки немає Gradle Wrapper):

```bash
gradle --no-daemon :shared:jvmTest :app:assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`.

**Go toolchain, NDK та генерація `ceilings-core.aar` більше не потрібні.** GitHub Actions `Build Android APK` збирає Debug, за наявності signing secrets — Release, запускає unit та Android instrumentation тести. `Verify CMP KMP platforms` перевіряє Windows/JVM та iOS Simulator.

## Розрахунки, прайс і PDF

- Вибір точності: **10 см / 1 см / 5 мм / 1 мм**, спільний для геометрії, лінійних кількостей, замірів та збережених кошторисів.
- Розрахункові коди, наприклад `light.spot` і `light.line`, не залежать від назв позицій. Обрані матеріал/профіль та редаговані ціни калькулятора передаються в KMP-ядро.
- Під час збереження кошторису створюються детальні рядки з назвами, кількостями, одиницями, цінами та сумами. Подальше редагування прайсу **не перераховує вже збережений кошторис**; PDF використовує його збережені дані.
- Майбутні **ручний перерахунок та історія версій**: [Issue #24](https://github.com/aleks-nl5/andrey-ceilings-android/issues/24), **нумерація**: [#25](https://github.com/aleks-nl5/andrey-ceilings-android/issues/25).
- Android SQLCipher **працює в WAL** після PR #29; підтверджено реальними Android SQLCipher/ENOSPC/restore перевірками. [Чинна WAL політика](docs/architecture/WAL_OPERATION_POLICY.md).

## Розробка та перевірки

```bash
gradle --no-daemon :shared:jvmTest :app:testDebugUnitTest
gradle --no-daemon :app:connectedDebugAndroidTest   # Потрібен Android emulator/пристрій
gradle --no-daemon :shared:iosSimulatorArm64Test     # Потрібен macOS/Xcode
```

Окремі загальні KMP-тести мають зафіксовані еталонні розрахунки колишнього Go-рушія; тестові вектори залишаються у Kotlin після видалення Go. Критерії приймання й поточний стан — [дорожня карта](docs/PROJECT_ROADMAP.md), [KMP-калькулятор](docs/architecture/GO_TO_KMP_PARITY.md) і [SQLDelight](docs/architecture/ROOM_TO_SQLDELIGHT_CUTOVER.md).

## Спільний CMP інтерфейс

Нові CMP-екрани будуються на [CMP UI Foundation](docs/CMP_UI_FOUNDATION.md): scaffold,
єдині edge gradients, вимірювані pinned-області та круглі FAB у `shared/commonMain`.
Перші інтегровані екрани — «Об’єкти/Клієнти» й «Деталі об’єкта» (#63).

Спільний read-only перегляд заміру, кодек v2/v3 і Canvas-креслення описані в
[CMP Measurement Foundation](docs/CMP_MEASUREMENT_FOUNDATION.md). Android-редактор
та глобальна вкладка «Заміри» зберігаються до окремого етапу CMP Editor (#60/#57).

PR A додає [спільне створення замірів](docs/CMP_MEASUREMENT_CREATE.md) із «+»
деталей конкретного об'єкта: rectangle/L/orthogonal polygon, live креслення,
точні міліметри й SQLDelight save. PR #72 merged після green final-SHA CI та власницького APK приймання.
[PR B1](docs/CMP_MEASUREMENT_EDIT.md) додає безпечне редагування існуючих замірів,
Undo/Redo, atomic updatedAt CAS і незалежне fullscreen-креслення. Міграція загальної
вкладки, quick-create та вилучення Android-only редактора — окремий PR B2 після приймання B1.

## Найближча розробка

Завершено read-only [CMP Measurement Foundation](docs/CMP_MEASUREMENT_FOUNDATION.md) (PR #66) і Windows JDBC RETURNING [#67](docs/WINDOWS_SQLCIPHER_RETURNING.md) (merged PR #68). PR A (#72) merged і протестовано власником. Поточний PR B1 — CMP редагування/fullscreen (#73); потім окремий B2 — cutover глобальної вкладки. Працюючий Android-редактор зберігаємо до B2 та перевірки паритету. Потім реальні робочі події #49, CMP калькулятор/кошториси #44 і host-и #42.

## Межі

Поточна SQLDelight-БД повністю тестова; за рішенням власника сумісність зі старими кресленнями та міграції Room/Go тестових даних/архівів не потрібні. Перед використанням реальних даних потрібні відповідні production migration/backup гарантії. Шифрування забезпечує захист локальної БД, але не окремих PDF/фото. Не поєднувати runtime cutover, WAL, серверну синхронізацію та зміни UI в одному PR.
