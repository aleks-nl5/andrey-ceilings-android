# iOS host — фактичний стан 03.11.2026

Наявні `shared` KMP **iosArm64/iosSimulatorArm64** targets, спільний Compose Multiplatform UI, SQLDelight бізнес-схема, SQLCipher encryption tests у **iOS Simulator** та framework compilation у CI. Збірка фреймворку / Simulator tests **не означають**, що існує готовий runnable iOS застосунок. Повноцінний `iosApp` Xcode/Swift entrypoint, запуск CMP/SQLCipher/Keychain і ручний UI smoke — [Issue #42](https://github.com/aleks-nl5/andrey-ceilings-android/issues/42).

## Поточна черга

1. PR #66 (CMP Measurement Details) та PR #68 (Windows JDBC) уже в `main`.
2. Наступні PR A/B: [CMP Measurement Editor](../docs/CMP_MEASUREMENT_EDITOR_PLAN.md) на основі спільного `shared/commonMain`; iOS compiler та Simulator SQLCipher gates залишаються обов'язковими.
3. Окремо за #42: runnable Compose UI host, Keychain, encryption/key lifecycle, native permissions/file/PDF/backup adapters, Inter font, launch/reopen/UI smoke й підготовка до пакування.

## Обмеження

Не відтворювати Android UI або бізнес-логіку окремо для iOS; платформними залишаються OS key storage, sandbox, files, PDF і lifecycle. Не підміняти production дані mock. БД зараз повністю тестова, за рішенням власника не потрібна міграція старих Room/Go БД, застарілих креслень чи архівів; без автоматичного destructive reset і без зміни поточного SQLCipher або immutable estimate contract. Android залишається UX-еталоном.

Актуальні рішення: [дорожня карта](../docs/PROJECT_ROADMAP.md) та [AGENTS.md](../AGENTS.md).
