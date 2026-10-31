# PR #29 — підсумковий аудит і критерії готовності

**Стан на 31.10.2026:** PR #29 (`feature/wal-and-estimate-revisions` → `main`) залишається **Draft**. Рішення про Ready / merge ухвалює тільки власник. Файл містить **актуальний** стан приймання; старі TODO/«чекаємо CI» у хронологічних записах `PROJECT_ROADMAP.md`, тексті PR та архітектурних нотатках описують тодішній етап, а не відкритий дефект сьогодні.

## Обсяг виконаної реалізації

| Напрям | Статус | Підтвердження |
| --- | --- | --- |
| Android SQLCipher **production WAL**; перехід із DELETE | Реалізовано, перевірено | `CeilingsDatabase.build`, ізольовані SQLCipher/WAL Android-тести; старий `main` ще не змінено |
| Консистентне encrypted `VACUUM INTO`, автоматичний і portable `.stpb`, повноцінний restore/rollback/Keystore, SIGKILL recovery | Реалізовано, CI перевірено, APK перевірявся | `PortableBackupManager`, `SqlCipherRestoreManager`, Android instrumentation та два ізольовані SIGKILL-сценарії |
| Ручний перерахунок кошторисів, незмінні v1/v2/v3, ціни/позиції, нумерація, PDF конкретних ревізій | Реалізовано, CI перевірено | KMP + SQLDelight immutable history; Android encrypted WAL регресійні тести |
| Прямокутник, L-форма й ортогональний багатокутник; **сторони + повороти 90°**, жодних X/Y у UI | Реалізовано, ручне приймання пройдене | Власник підтвердив «Повне ручне приймання нового багатокутника — Працює» |
| Наочне read-only креслення фактичного контуру для підтриманих замірів | Реалізовано, прийнято в межах тестування багатокутника | `MeasurementDrawingPreview`; вершини залишаються лише технічною моделлю |
| Вихідні заміряні довжини та повороти в новому `geometrySchemaVersion=3` | Реалізовано, CI перевірено | KMP `OrthogonalSideContourTest`, Android `MeasurementPolygonPersistenceTest` та `MeasuredSidesV3EstimateRegressionTest` |
| **Реальна нестача місця /data — ENOSPC під час restore stage** | **Доведено на CI** | [Android #41139366890](https://github.com/aleks-nl5/andrey-ceilings-android/actions/runs/41139366890): журнал містить `Genuine /data ENOSPC, unchanged SQLCipher WAL/key, recovery and cold reopen all verified` |
| **Реальна нестача місця — SQLCipher WAL INSERT / транзакційний відкат** | **Доведено на CI** | [Android #41179759728](https://github.com/aleks-nl5/andrey-ceilings-android/actions/runs/41179759728): `SqlCipherRealFullPartitionEnospcTest` пройшов (`OK (1 test)`). Тест фактично заповнює `/data`, перевіряє помилку `SQLITE_FULL` під час WAL INSERT, відсутність часткового запису, цілісність, FULL checkpoint, cold reopen і наступний успішний запис |

## Остання підтверджена матриця CI

На коміті `f2318558` (містить додатковий WAL-ENOSPC INSERT-тест `cca93da4`):
- **Android CI** [#41179759728](https://github.com/aleks-nl5/andrey-ceilings-android/actions/runs/41179759728): `success`; shared/KMP, JVM, інструментальні SQLCipher-тести, два окремі SIGKILL, **реальний ENOSPC restore та реальний `SQLITE_FULL` WAL INSERT**, Debug і підписаний Release APK.
- **Windows + iOS CI** [#41179759740](https://github.com/aleks-nl5/andrey-ceilings-android/actions/runs/41179759740): `success`; обидва незалежні jobs `windows-desktop` і `ios-and-desktop`.
- **Перевірено журнал Android:** `SqlCipherRealFullPartitionEnospcTest` завершився `OK (1 test)`; його незмінена логіка має обов'язкові assertions для обох реальних відмов запису, cleanup, rollback, WAL checkpoint, cold reopen і нового успішного INSERT. Жодного mock `IOException` як доказу ENOSPC не використано.
- **Ручне приймання:** власник підтвердив повне тестування нового багатокутника й працездатність APK.

## Критерії фінального Ready

- [x] Успішні **Android, Windows та iOS CI на останньому функціональному SHA** `f2318558` після додавання SQLCipher WAL ENOSPC INSERT. Наступні суто документальні коміти не змінюють production-код або тести.
- [x] Ізольований Android `SqlCipherRealFullPartitionEnospcTest` реально пройшов обидві фази: ENOSPC restore, `SQLITE_FULL` WAL-транзакції, перевірка rollback, checkpoint і cold reopen.
- [x] Власник підтвердив повне ручне приймання нового багатокутника й працездатність APK. Якщо production-код не змінюється, додавання лише тестів/документації не потребує повторного ручного приймання того самого UI.
- [x] Конфліктів із `main` немає на час першого аудиту 31.10.2026: GitHub `mergeable=true, mergeable_state=clean`. Перед об'єднанням перевірити повторно.
- [x] Не порушено незмінність ревізій кошторисів, друкованого номера, старих PDF та незалежне шифрування SQLCipher.
- [x] Власник вирішив **не витрачати час на міграцію локальної тестової БД та старих тестових архівів**. Але сумісність незмінних версій історії та коректність сучасного backup/restore залишаються обов'язковими.
- [x] Фінальне технічне приймання та документаційний аудит завершені; GitHub показав `mergeable=true`, `mergeable_state=clean` на `f2318558` (31.10.2026).
- [ ] **Лише рішення власника:** окреме погодження переведення Draft → Ready та подальшого merge. Нічого не зливати автоматично.

## Явно поза межами цього PR

Готові шаблони T/U/трапеції, діагоналі, непрямі кути, криволінійні сегменти, повноцінний графічний редактор `Накреслити кімнату`, майбутня синхронізація між пристроями. Це наступні задачі / інші PR, не підстави затримувати готовність SQLCipher WAL + immutable history після проходження узгоджених перевірок.

## Файли перевірки

- `ci/run-sqlcipher-instrumentation.sh`: послідовно звичайні інструментальні тести → два SIGKILL → окремий ENOSPC.
- `ci/verify-sqlcipher-enospc.sh`: захист від запуску на реальному телефоні, явний opt-in, `pm clear` лише disposable емулятора, аварійне видалення filler, fail-closed при невдалому тесті.
- `app/src/androidTest/java/com/andreyceilings/app/SqlCipherRealFullPartitionEnospcTest.kt`: фактична алокація блоків `/data` через `Os.posix_fallocate`; після очищення — encrypted WAL, Keystore, integrity, backup/restore та прямий WAL-INSERT.
- `docs/architecture/WAL_AND_ESTIMATE_REVISIONS.md`, `docs/architecture/MEASUREMENTS_SIDES_AND_DRAWINGS.md`: пояснення архітектури та UX; `docs/PROJECT_ROADMAP.md`: історія рішень.
