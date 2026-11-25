# P5D. Жива вартість і кошториси

База реалізації — `d13f03dc7932e437043fe06df5f49bf051433cc8`. Вимоги, рішення про логічне видалення та критерії приймання визначені в специфікації та handoff. [Issue #164](https://github.com/aleks-nl5/andrey-ceilings-android/issues/164) · [специфікація](P5D_UX_SPEC.md) · [Draft PR #166](https://github.com/aleks-nl5/andrey-ceilings-android/pull/166)

## Замір і живі ціни

- Редактор проєктує поточну чернетку заміру, включно з роботами без розміщення, замінами матеріалів, погодженнями, кількостями, контурами й позначками. Для редагування проєкція звіряється з транзакційним читанням заміру та прайсу. Для створення використовуються ідентичність об’єкта, UUID чернетки та активний прайс.
- Проєкція живе в пам’яті й використовує `LivePricingEngine`. Фінансовий запис повторно читає збережені джерела у своїй транзакції. Редактор показує недоступний стан або явно позначений неповний підсумок, коли бракує даних.
- «Вартість» групує погоджені роботи й ті, що обговорюються. Тап по роботі переводить до точного рядка в «Елементах». Тап по матеріалу підсвічує його селектор.
- «Кошториси» зберігають сесію композиції між вкладками. Новий або змінений замір вимагає підтвердити Save перед відкриттям або оновленням композиції. Частковий кошторис перевіряє відбиток підтвердженого переліку пропусків. Для нової версії поточний замір і прайс задають ціни, відкрита попередня версія задає джерело явно вибраних рішень для перенесення.

## Фінансові формати й історія

- Схема 21 додає `estimate_revision_tombstones`. Видалення останньої видимої версії або всієї допустимої серії приховує серію. Історична перевірка, квитанції і вже захоплені посилання зведеного PDF читають точний фінансовий запис. Активні списки та нові вибірки відсіюють видалені записи. Наступна версія отримує номер за повним історичним максимумом.
- Фіксована знижка використовує формат 4. Збережені суми й тип знижки перевіряються при читанні, відновленні й перевірці квитанції. Для фіксованого типу сумісна числова колонка відсотка має нуль, а точна сума і тип містяться в перевіреному payload. Формати 1–3 зберігають свої байти та правила округлення.
- Спільний запис статусу серіалізований з додаванням і видаленням версій. Погодження звіряє точні UUID та версії погоджених записів цієї стелі. Підтверджена заміна переведе попередні погоджені записи до SENT одним записом із новим APPROVED. Явний вибір версії через це ж підтвердження дозволяє розв’язати імпортований конфлікт. Вибір у старій технічній таблиці залишається доступним для сумісності.
- Для видалення версії перевіряється її поточний статус і версія статусу. Для серії перевіряється весь підтверджений активний набір. APPROVED і невідомий історичний статус захищаються в обох випадках.
- Android підтверджує вибір цілі для PDF через зворотний виклик системного меню поширення. Лише тоді DRAFT може перейти до SENT за точним UUID і версією статусу. Перегляд PDF, друк і збереження у файл не змінюють статус. Підтвердження вибору цілі не встановлює доставку отримувачу.

## Перевірка APK власником

- На новому замірі додати полотно, профіль і роботу з кількістю 6. Перевірити вартість до Save з чотирма та з нулем розміщених позначок. Замінити матеріал, змінити погодження й кількість, зробити Undo/Redo. Увести недійсний контур і відновити його. Порівняти суми до та після Save.
- Пройти всі чотири вкладки на вузькому екрані, зі збільшеним шрифтом та IME, у світлій і темній темах. Перевірити закріплену смугу, перехід до точного елемента й повернення до креслення після розгортання.
- Відкрити створення кошторису з нового та зміненого заміру. Скасувати діалог і повторити зі «Зберегти й продовжити». Перемкнути вкладку і повернутися до чернетки. Перевірити точну знижку 0,01 грн, відсоток, перевищення суми, скидання кількості та підтвердження пропусків.
- Відкрити стару версію, порівняти її креслення й підсумок, повернутися до живого заміру. Створити нову версію з поточних джерел і перенести вибране рішення з відкритої старої версії.
- Погодити версії з різних серій. Скасувати заміну й повторити з підтвердженням. Перевірити недоступність видалення погодженої версії, окремі підтвердження для версії й серії, видалення новішої версії та подальшу нумерацію.
- Переглянути PDF, запустити друк, закрити системне меню поширення і повторити з вибором застосунку. Звірити точну версію і перехід DRAFT → SENT. Переконатися, що попередньо погоджений документ зберігає статус.

На базі `d13f03dc` Android FULL #1364 і CMP FULL #1102 завершилися успішно. Підсумки перевірки реалізації наведені в Draft PR. Фінальний ручний FULL Android і CMP запланований після UX-доопрацювання та незалежного рев’ю на стабільному SHA. [Android #1364](https://github.com/aleks-nl5/andrey-ceilings-android/actions/runs/45471032453) · [CMP #1102](https://github.com/aleks-nl5/andrey-ceilings-android/actions/runs/45471032446)

## Правки після фізичної перевірки та рев’ю

- У звичайному режимі всі чотири вкладки мають іконки. Текст видно на активній вкладці. У fullscreen залишилися круглі кнопки «Контур» і «Елементи» та жест розгортання вгору. Повернення до звичайного режиму відновлює фінансову вкладку, її чернетку або відкриту версію.
- «Вартість» вирівнює потенційну суму праворуч і залишає рядок кількості та ціни. У кошторисах рядок показує назву й суму, а дозволене коригування кількості має окрему індикацію. Назва використовує компактне поле з місцем для плаваючого підпису.
- Валідна розрахункова кількість дає ціну навіть за відсутніх, зміщених чи неперевірених позначок. Стан розміщення видно в підказці. Перевірки заміру перед Save та сувора перевірка джерела кошторису діють окремо від попередження про актуальність.
- SQL-команда видалення звіряє власника версії, активність і точний дозволений статус. Після підтвердження поширення Android запис статусу завершує сховище сесії, а списки перечитують статус незалежно від того, звідки відкрито PDF.
- Вихід з редактора через інший маршрут або закриття Desktop спочатку перевіряє чернетку кошторису. Якщо замір теж має зміни, застосунок запитує рішення щодо заміру. Скасування будь-якого підтвердження залишає обидві чернетки.

Для наступної перевірки на APK варто відкрити фінансову вкладку, перейти у fullscreen до креслення, розгорнути меню жестом вгору, а потім повернутися до кошторису. Окремо перевірити підпис поля назви з IME, правий підсумок у «Вартості» та перехід в інший розділ із двома чернетками. [Зауваження власника](https://github.com/aleks-nl5/andrey-ceilings-android/pull/166#issuecomment-7144615169)

### Draft PR #166 review follow-up

New individual series creation checks the existing frozen v1 ownership inside the writer transaction, including tombstoned series. Duplicate revisions are rejected before version allocation. Successful retries keep their original receipt. No database migration is required. Focused writer proofs cover historical ownership, retry receipts and identical proposals. The workspace layout and gesture changes are for manual physical APK verification.

Continuing a fully deleted series uses the canonical append receipt and historical parent, retaining the series number. The append transaction preserves existing revision tombstones and fills in missing markers for older whole-series deletions before clearing the series marker. Failure restores the pre-save state. Focused proofs cover rollback, exact old payloads, receipt retry and races against another append or initial create.

Owner APK follow-up: the shared series lookup used by the workspace and transactional writer now returns all active series if present, otherwise only the newest-created historical series. This keeps deleted alternatives from blocking creation while preserving the lifetime number rule, explicit selection for multiple active series, and atomic continuation. The existing portable continuation proof also covers active precedence, rejection of stale continuation, and selection when all legacy series are deleted. CI/APK verification remains required.

Owner APK follow-up: neutral shape selector; edge-to-edge themed workspace headers; centered cost group headings, compact inline information icons and aligned larger totals. Shared alternating row tint and dividers are used in element, cost and quote lists. Compact quote labels omit the generated public token using the frozen numbering policy; stored identifiers and issued PDF numbers are unchanged. Physical APK verification remains required.

### Physical regression checklist (owner acceptance reference)

- 2×3 m ceiling: choose both main materials, verify auto-approval and current geometry/cost; Save and reopen.
- Add POINT with quantity 3, tap three times; drag a selected point, single tap highlights, fullscreen double tap opens distances. Empty distances don't show early red warning; invalid pair shows contextual warning, valid pair clears it.
- LINE: drag endpoint and body, toggle snap; declared length unchanged by drag; transfer from drawing after confirmation updates the declared field. Invalid length/width shows error icon; apply blocked until valid.
- AREA: rectangle corner retains rectangle; polygon vertex; circle diameter; obstacle and hole are not billable; invalid dimensions show error icon. Undo/Redo and Save/reopen.
- Work quantity A11: type without apply, switch pencils and back; verify un-applied value is preserved or explicitly discarded, never implicitly saved. Switch tabs and Back; test geometry pending edits separately.
- Back from catalog detail to search then list; dirty geometry discard dialog has red left action and safe right action. Test all four tabs with keyboard and both themes; normal/fullscreen fit; no duplicate system gradients. Verify info/help/error popovers and circular ripple.
- Quote deletion/discard: red destructive action left, cancel right; approved protected. Verify current quote draft remains intact when cancelling navigation.

Shared feedback components and affected labels are localized via CMP resources; existing unrelated domain error strings remain for the dedicated localization stage. No new automated UI tests. At the time of these changes, CI and physical APK acceptance were pending; see final acceptance record below.

Owner APK follow-up: quote title and discount opt into the shared dense single-line field variant (44 dp minimum, 2 dp vertical interior padding, standard bodyLarge input and floating label). No fixed maximum height; font scaling can grow the field. Other compact fields retain 52 dp. Verify text/label visibility at normal and increased font scale, with focus, keyboard, percent and fixed discounts, both themes.

Owner APK follow-up: the selected-geometry header X previously cleared selection without clearing its dirty parameter editor. Since the discard dialog lived inside the selected geometry panel, subsequent navigation could be blocked by hidden invalid input. Guard selection clearing, placement and deletion before abandoning dirty parameters; host LINE/AREA discard confirmation at workspace scope. Discard retains applied geometry/Undo history and removes the transient validation blocker. Added a focused JVM model/DB regression (oversized circle, X, continue/discard, select/delete/Undo/Save). Material pickers gain outlined clickable surfaces and a dropdown chevron. Dense quote inputs now use 40 dp minimum with 1 dp internal vertical padding, external floating-label clearance and 88 dp maximum discount width; standard typography and adaptive height remain. At the time of this change, physical APK/CI verification was pending; see final acceptance record below.

Final P5D review follow-up: explicit optional quote inclusion/exclusion now belongs to the transient session and stable source key rather than approval bucket; default selections still follow the measurement. Missing explicitly included work still requires an explicit decision, and refreshed sources invalidate partial acknowledgement. Approved-document drift checks previously unresolved omissions using the canonical engine and ignores intentional omissions. Focused common presenter and JVM database regressions added; no UI suite or schema/payload changes. See final acceptance record below.

## Final acceptance record — 25.11.2026

- [x] Owner accepted APK after the final P1/P2 follow-up: [PR acceptance record](https://github.com/aleks-nl5/andrey-ceilings-android/pull/166#issuecomment-7166726396). This records the owner's overall acceptance, not independently observed results for each individual manual checklist item.
- [x] [Final independent source review](https://github.com/aleks-nl5/andrey-ceilings-android/pull/166#pullrequestreview-6059682526) on `68a494e`: no P0 found in inspected paths; P1 explicit composition selection and P2 relevant omitted-work drift addressed by `675bd66`. QUICK #184 exposed a JVM regression-test argument mismatch, corrected by `46f9015` (test only).
- [x] [QUICK #185](https://github.com/aleks-nl5/andrey-ceilings-android/actions/runs/45639601965) SUCCESS on `46f901529dcd569ceffe12b0ad960c3bfa37f941` (bounded checks and compilation, not native FULL).
- [x] CI follow-up: Android FULL #1365 exposed incompatible common Kotlin `MatchGroup.range`, fixed in `c8a9b9c` without changing frozen identity, PDF or financial data. Android FULL #1366 failed on Maven Central HTTP 429, not a repeated compilation error. CMP FULL #1103 failed in historical migration proofs due to the missing v21 tombstone table on v16/v18/v19 fixtures. `ee66b54` uses historical SQL only for pre-v21 reads in three common test files; migrated databases still use production queries and assertions remain intact.
- [x] [QUICK #189](https://github.com/aleks-nl5/andrey-ceilings-android/actions/runs/45658089533) SUCCESS on `ee66b542ca2442c5f3835a9ac505964cbb5b9be0`.
- [x] [Android FULL #1367](https://github.com/aleks-nl5/andrey-ceilings-android/actions/runs/45658264031) SUCCESS on the same exact SHA: JVM, encrypted instrumentation, release APK and signed production open/recovery passed.
- [x] [CMP FULL #1104](https://github.com/aleks-nl5/andrey-ceilings-android/actions/runs/45658307795) SUCCESS on the same exact SHA: Windows JVM/SQLCipher, macOS Desktop compilation/shared JVM and iOS Simulator/SQLCipher passed. Scoped macOS UI smoke and Android retry/failure diagnostics were skipped as expected by policy.
- [x] Tested SHA is 26 commits ahead and zero behind main `d13f03dc7932e437043fe06df5f49bf051433cc8`; PR mergeable before this acceptance-record update. The subsequent Markdown-only delta must be checked separately and accepted by the owner; do not claim an exact-head FULL for the documentation commit.
- [ ] Owner marks Ready and merges; verify both automatic main FULL runs on the merged SHA before closing the accepted work.

### Scope remaining outside PR #166

- PDF compact layout/page breaks, drawing sizing, totals placement, and short printed number presentation: separate follow-up; preserve exact frozen values and original document identity. Existing extra-page concern is layout, not accepted evidence of changed finances.
- Deferred/partial placement indicators on ceiling/project lists and optional work filter: non-blocking P5D follow-up under #76; agree UX and create a dedicated tracked issue before closing #76 if absent. Existing editor shows declared/placed quantity; lack of placement never blocks pricing solely for that reason.
- Full localization remains its own workstream. No historical-series consolidation/renumbering or migration solely for old test records. No automated UI suite.
- #164 remains open until P5D merge/verification. #95 and #76 require their own final criteria audit; do not auto-close umbrella issues with this PR.

