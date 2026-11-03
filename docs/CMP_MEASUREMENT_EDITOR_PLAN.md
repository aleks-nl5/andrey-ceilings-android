# CMP Measurement Editor — узгоджений план після PR #68 (03.11.2026)

**Стан:** план наступних окремих PR, не реалізовано. **Джерело:** актуальний `main` після merged PR #66 (CMP перегляд) і PR #68 (Windows encrypted JDBC RETURNING). Issues [#60](https://github.com/aleks-nl5/andrey-ceilings-android/issues/60), [#57](https://github.com/aleks-nl5/andrey-ceilings-android/issues/57).

## Рішення щодо тестових даних

Власник підтвердив 03.11.2026: уся поточна БД тестова, важливих користувацьких записів немає. **Сумісність із застарілими кресленнями, старими тестовими JSON, Room/Go БД і тестовими архівами НЕ є вимогою наступних PR; жодних міграцій чи додаткових compatibility layers лише заради старих даних.** Можна видаляти / створювати заново *тестові дані*, лише після явного визначення обсягу, не виконувати destructive reset автоматично в застосунку чи CI. У разі суперечності старих історичних чеклістів це рішення має пріоритет для PR A/B.

Це **не скасовує** поточні контракти KMP геометрії, вимірювання у цілих міліметрах, SQLDelight/SQLCipher, UUID/timestamps/soft-delete, чинний формат збереження нових замірів, WAL, backup/restore, чинні незмінні ревізії кошторисів і PDF. Не псувати нові записи й чинні розрахунки заради спрощення старої сумісності.

## Цільова архітектура

- Один `shared/commonMain` CMP Editor + KMP UI-state/presenter/validation. Android, Windows і майбутній runnable iOS host використовують ті самі модель, екран, валідацію та репозиторій.
- Застосовувати наявні `CeilingShapeTemplates`, `VerifiedCeilingGeometry`, `FrozenPolygonGeometryContract`, `OrthogonalSideContour`, `MeasurementGeometryCodec`, `MeasurementDrawing`; жодних дубльованих формул або окремого геометричного рушія для форми.
- Користувач вводить **довжини й повороти**, ніколи не вводить технічні координати X/Y; підтримуються **лише фактично реалізовані** rectangle, l_shaped і polygon (сторони + LEFT/RIGHT 90°, повний замкнений контур). T/U/трапеції, непрямі кути, діагоналі, графічне креслення пальцем — окремі майбутні задачі.
- Поточна точність налаштувань застосовується **лише до нового заміру**; зафіксована точність роботи не змінюється після створення. Відображення інших даних не запускає перерахунок або модифікацію.
- Реальні SQLDelight MeasurementRepository, Flow, транзакції, глобальний UUID, timestamps/tombstones; без фіктивних даних. Windows adapter після #68 підтримує `RETURNING`.
- Reuse `CeilingsScreenScaffold`, `CeilingsFab`, theme/insets/Inter; не порушувати Project Details адресу/FAB/docking/30% snap, незалежну прокрутку Objects/Clients та native Back.

## PR A: створення заміру у CMP

**Мета:** завершена вертикальна функція «Об’єкти → конкретний об’єкт → + → створити замір → зберегти → реальний список/деталі заміру → Back».

- [ ] Окремий типізований маршрут/стан для create з переданим `projectId`; перевірка існування активного об’єкта, без дублікатів та випадкового auto-save після процесного restore.
- [ ] Спільний адаптивний екран: назва, форма, параметри шаблону або усі виміряні сторони+повороти, optional notes, точність із чинних налаштувань тільки для нового запису, зрозумілі пояснення помилок, live CMP drawing, площа й периметр.
- [ ] Використовувати однакову схему створення незалежно від платформи; реальний `MeasurementRepository.createReturningId`, validated geometry, запис `drawingJson`, правильна прив'язка `projectId`. Якщо активний проєкт зник/БД недоступна — fail closed; при повторному тапі не створювати дубль.
- [ ] Навігація після успішного створення до заміру або деталей об’єкта; без повторного запуску create при Back/restore.
- [ ] Зберегти чинні Android-only створення/редагування у глобальній вкладці «Заміри» та передачу у калькулятор; **не** видаляти їх у PR A.
- [ ] KMP/unit, Android SQLCipher/instrumentation, Windows native encrypted CRUD + CMP UI, iOS framework/simulator tests; owner APK smoke light/dark/narrow/keyboard/Back/error paths.
- [ ] **Поза PR A:** редагування, видалення, інтеграція глобальної вкладки, нові форми, зміна схем/драйверів/backup, drag-to-draw, переписування кошторисів/PDF.

## PR B: редагування, глобальна вкладка та Android cutover

- [ ] CMP edit з тією самою form/state, предзаповненням і безпечною валідацією; project ownership і захист від stale `updatedAt`/видалення (optimistic `UPDATE … WHERE updatedAt = ? AND deletedAt IS NULL RETURNING` за потреби).
- [ ] Редагування й відкриття з Measurement Details; глобальна вкладка та quick-create використовують спільний редактор; збереження запуску калькулятора від заміру.
- [ ] Перевірка зафіксованої точності та незмінних estimate revisions; відсутня форма або неповні виміри не автодоповнюються.
- [ ] Лише **після** власницького APK acceptance та платформних CI усунути Android-only editor/preview там, де більше немає споживачів.
- [ ] Продовження #60/#57; Predictive Back #56 та host #42 окремо.

## Послідовність далі

Після PR B: [#49](https://github.com/aleks-nl5/andrey-ceilings-android/issues/49) реальні робочі події/календар; CMP калькулятор/кошториси й прайс для **нових** кошторисів [#44](https://github.com/aleks-nl5/andrey-ceilings-android/issues/44); паралельно Windows/iOS hosts [#42](https://github.com/aleks-nl5/andrey-ceilings-android/issues/42). Діагностика #30–#34 та інші поліпшення — пізніше.

## Delivery / CI

Зміни від актуального main, один вузький Draft PR на етап, тільки власник робить merge. Усі Android unit/SQLCipher emulator/APK, Windows Desktop/native encrypted CRUD, iOS framework/Simulator тести — зелені на одному final SHA, з порівнянням кешів/тривалості із PR #68 (Android **10m19s**, Windows **4m19s**, macOS/iOS **17m55s**). UI smoke на Android власник перевіряє в обох темах. Інваріанти й workflow: [AGENTS.md](../AGENTS.md), [CMP UI Foundation](CMP_UI_FOUNDATION.md), [geometry contract](architecture/MEASUREMENTS_SIDES_AND_DRAWINGS.md).
