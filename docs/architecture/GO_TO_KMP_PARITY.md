# Етап 2 — Kotlin Multiplatform як єдиний розрахунковий рушій

**Стан 29.10.2026 · PR #23 (Draft), гілка `feature/kmp-calculation-parity`.** На прохання власника після успішного baseline CI і підтвердженої роботи Go APK на телефоні Android-калькулятор **перемкнуто на KMP**, а Go-ядро, gomobile, AAR і Go-етапи CI **видалено в цій гілці**. **Новий KMP APK і всі платформи ще мають пройти CI та ручну перевірку; `main` не змінено.**

## Єдиний runtime

- Android UI `CeilingCalculator.estimate` → `:shared/commonMain` `KmpCeilingCalculator.estimate`: геометрія, площа, периметр, матеріал/профіль, складність, додаткові роботи й грошове округлення.
- `PriceItemEntity.code` → KMP `PriceList`: локальний прайс має пріоритет над defaults; введена в калькуляторі ціна **обраного матеріалу/профілю** має пріоритет над прайсом. Відсутні позиції використовують еталонні defaults.
- Немає Go/AAR, `Mobileapi` або запасного спрощеного калькулятора. На екрані результату й у diagnostics рушій позначено **Kotlin KMP**.
- Заміри вже обчислюються KMP `geometry`; SQLDelight зберігає знімок розрахунку з незмінними сумами, line items і кодами. PDF читає збережені `detailsJson` та `totalCost`, не поточні ціни.
- Windows/iOS використовують те саме KMP-ядро; повні Windows/iOS бізнес-інтерфейси ще не завершені.

## Правила точності та кодів

- Одне глобальне Android-налаштування точності: **10 см / 1 см / 5 мм / 1 мм** (`MeasurementPrecision`); застосовується до розмірів, площі (крок²), периметра, складних конструкцій, лінійних опцій, нових замірів і кошторисів.
- Кількості у штуках не округлюються як метри. Грошове округлення — до цілої гривні, *half-away-from-zero*, незалежно від кроку вимірювань.
- `measurementPrecisionMeters` зберігається в `detailsJson` конкретного кошторису; PDF показує його первинну точність, а не поточне налаштування.
- Уніфіковані технічні коди додаткових робіт: `light.line`, `light.spot`, `light.chandelier`, `work.shadow_profile`, `work.floating_ceiling`, `work.pipe_pass`, `work.curtain_niche`, `work.extra_corner`, `work.hard_wall`. Відображувана назва світильника — «Точковий світильник», без GX53.
- Матеріали/профілі зберігаються за незмінними ID, а не за назвами. `EstimateLineItem.code` і `title` розділені, щоб змінена назва прайсу не порушувала збережений документ.
- Старі записи тестової БД не мігруються, за рішенням власника.

## Регресійні перевірки

- [x] Раніше проведені Go/KMP baseline comparisons, fixture vectors і приклади округлень. Тепер еталонні результати **зафіксовані в Kotlin commonTest**; Go-код видалено і не потрібен для збірки/тестів.
- [x] На попередньому Go-runtime HEAD `1957ac13` проходили [Android 40897166522](https://github.com/aleks-nl5/andrey-ceilings-android/actions/runs/40897166522) та [Windows/iOS 40897166664](https://github.com/aleks-nl5/andrey-ceilings-android/actions/runs/40897166664).
- [x] Власник перевірив на фізичному Android: **попередній Go APK успішно виконував розрахунки**. Цей результат не можна поширювати на новий KMP APK.
- [x] Додано `CeilingCalculatorKmpInstrumentedTest` для Android UI-адаптера: усі чотири точності, комплексний кошторис, ціни матеріалу/профілю й додаткових робіт із прайсу, `Kotlin KMP` engine.
- [x] Додано `EstimateSnapshotPersistenceInstrumentedTest` на реальній Android SQLCipher/SQLDelight: зміна/видалення прайсу, копія, повторне відкриття БД, незмінні `lineItems` і `totalCost`.
- [ ] **Фінальна CI саме після видалення Go**: Android Debug/Release APK, unit/instrumentation KMP tests, Windows JVM, iOS Simulator.
- [ ] **Ручна перевірка нового KMP APK на телефоні**: 10 см / 1 см / 5 мм / 1 мм, усі опції, складність, зміна прайсу, збереження, дублювання, повторний запуск, PDF і відповідність сум.
- [ ] Зафіксувати й відпрацювати будь-які регресії KMP до рішення власника щодо merge.

**Не входить у PR #23:** [#24 — ручний перерахунок/версії](https://github.com/aleks-nl5/andrey-ceilings-android/issues/24), [#25 — глобальна нумерація](https://github.com/aleks-nl5/andrey-ceilings-android/issues/25), [#26 — безпечний WAL](https://github.com/aleks-nl5/andrey-ceilings-android/issues/26). Android БД працює в `DELETE` за результатом `PRAGMA journal_mode`; тут режим не змінюємо.

Після перевірки нового KMP APK — завершити PR #23 за дозволом власника, перейти до CMP-екранів (перший — прайс). **PR залишається Draft, автоматично не зливати.**
