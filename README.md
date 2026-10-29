# Andrey Ceilings

Offline-first застосунок для замірів, розрахунків і кошторисів натяжних стель. Поточний Android UI — Jetpack Compose; спільна бізнес-логіка, розрахунки й сховище використовують **Kotlin Multiplatform**.

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
- Фактичний режим журналювання Android SQLCipher на телефоні: `DELETE`. Контрольований перехід на WAL заплановано окремо: [#26](https://github.com/aleks-nl5/andrey-ceilings-android/issues/26).

## Розробка та перевірки

```bash
gradle --no-daemon :shared:jvmTest :app:testDebugUnitTest
gradle --no-daemon :app:connectedDebugAndroidTest   # Потрібен Android emulator/пристрій
gradle --no-daemon :shared:iosSimulatorArm64Test     # Потрібен macOS/Xcode
```

Окремі загальні KMP-тести мають зафіксовані еталонні розрахунки колишнього Go-рушія; тестові вектори залишаються у Kotlin після видалення Go. Критерії приймання й поточний стан — [дорожня карта](docs/PROJECT_ROADMAP.md), [KMP-калькулятор](docs/architecture/GO_TO_KMP_PARITY.md) і [SQLDelight](docs/architecture/ROOM_TO_SQLDELIGHT_CUTOVER.md).

## Межі

Поточна SQLDelight-БД і дані ще тестові; міграцію старої тестової Room/Go-БД за рішенням власника не виконуємо. Перед використанням реальних даних потрібні відповідні production migration/backup гарантії. Шифрування забезпечує захист локальної БД, але не окремих PDF/фото. Не поєднувати runtime cutover, WAL, серверну синхронізацію та зміни UI в одному PR.
