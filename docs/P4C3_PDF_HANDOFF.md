# P4C3 — Codex implementation handoff

> **Status:** documentation-only handoff in `feat/p4c3-frozen-pdf-document` after owner merged PR #117; actual feature not yet implemented. Canonical task: [#119](https://github.com/aleks-nl5/andrey-ceilings-android/issues/119).
>
> **Owner decisions (18.11.2026):** one current issuer/company/logo source in future Settings; no per-document historical issuer snapshot or company profile versions. Future object photos are an extension point only. P4C3 delivers immutable single-revision PDF, light presentation editor, preview; consolidated document is separate P4C4. No deletion.

# P4C3 — самодостатнє завдання для Codex (після merged PR #117)

## Стартова база та межі
- Стартувати **виключно від актуального `main`** після [PR #117](https://github.com/aleks-nl5/andrey-ceilings-android/pull/117) (merge commit `132f437a6f8e5aa3f4e620e56bf1d71c038cf376`, schema v19). [#116](https://github.com/aleks-nl5/andrey-ceilings-android/issues/116) completed.
- P4C3 = **PDF одного конкретного збереженого індивідуального кошторису**: вибір конкретної immutable `revisionUuid`, попередній перегляд, редактор оформлення, експорт, повторне формування. P4C4 (зведений об'єктний PDF із вибраних кімнат) — **окремо**, але використовує ту саму модель секцій.
- Цей issue — продуктово-технічний контракт. Спочатку handoff/docs-only у Draft PR, далі Codex реалізує **в тому самому PR**. Не merge / не Ready без власника.
- Наявний Android експорт ( `ImmutableEstimatePdfSnapshot.kt`, `EstimateScreens.kt::writeEstimatePdf`, `EstimateHistoryDialog.kt`) працює для історії, але використовує Android `PdfDocument` і legacy `EstimateEntity/Double`. **Не переносити нові exact format-3 кошториси через Double/старий адаптер.** Legacy PDF має продовжити працювати.

## Ключове рішення власника
**Грошові, геометричні й кількісні дані беруться лише з перевіреного immutable frozen snapshot вибраної ревізії**, не з поточного прайсу/заміру і не з інших vN. PDF-редагування змінює лише **оформлення та нефінансовий текст**, а не збережений кошторис.

**Простота реквізитів:** у майбутньому компанія/майстер/телефони/адреса/логотип беруться з **єдиних актуальних Settings** (профіль #114 ще не реалізовано). **НЕ** вводити окрему БД історичних профілів, per-estimate issuer snapshots, версії логотипів, їхню міграцію чи редактор профілю в P4C3. Передбачити тільки optional typed issuer/logo provider interface/section, що пропускається, коли даних немає. Аналогічно — optional photo/attachment section для майбутніх фото об'єкта: зараз НЕ реалізовувати фото CRUD, picker, DB, копіювання/збереження медіа.

**Повторне формування:** фінансовий зміст і version/number не змінюються; натомість поточні майбутні Settings або змінений текст/вигляд можуть змінити зовнішній PDF. Не обіцяти byte-identical «копію» без збереженого оригінального PDF. Не створювати прихованих фінансових ревізій або друкованих issuer-ревізій.

## Функціональна поведінка P4C3
1. У спільній CMP read-only картці **кожної конкретної** збереженої версії (з глобальних «Кошториси», об'єкта, заміру) — дія **«Створити PDF»**. Усі v1/v2/v3 доступні незалежно від «найновіша»/«обрана»/4-state статусу. Не робити implicit latest. Показувати номер, vN, об'єкт, кімнату.
2. Відкривати **CMP preview A4** того документа, який експортуватиметься. Базовий один охайний шаблон українською з автоматичною багатосторінковою версткою, переносами назв і довгих текстів, таблицею, repeat header/page numbering, читабельною кирилицею/символами (², %, валюта).
3. Легкий **редактор оформлення до експорту**, не довільний PDF text editor: видимість/порядок дозволених нефінансових секцій (параметри приміщення, frozen креслення, примітки, додаткові умови); редаговані необов'язкові текстові блоки «Примітки», «Умови оплати», «Строки», «Гарантійні умови» лише як введений користувачем текст (ніяких вигаданих гарантій). Таблиця позицій, totals, frozen number/version — обов'язкові й нерухомі; не дозволяти приховувати загальну суму. Порожні секції не показувати. Preview і PDF використовують **один render model**, немає відмінних розрахунків.
4. Друкувати: незмінний публічний номер **без private UUID**, конкретну vN, дату створення версії, назву/доступну адресу об'єкта й кімнати, frozen параметри/креслення, кожну збережену позицію (назва, одиниця, кількість, ціна, сума), знижку, підсумок, FULL/PARTIAL та невраховані confirmed optional роботи/причини, якщо вони є. Точно відображати різницю **виміряна площа** vs **округлена до 0.001 м² розрахункова кількість** для format 3; не округлювати двічі.
5. **Експорт — явна дія**, SAF/share/print через тонкі платформні адаптери; скасування не створює файл і не змінює стан. Грамотна назва файлу `<safe-public-number>-vN.pdf` без UUID і path separators; відмова/повідомлення при permission/I/O/cancel. Не перезаписувати без системного підтвердження.
6. Не писати нічого в `estimate_series`, `estimate_revision`, `estimate_workflow`, selected pointer, price/measurement; не змінювати status або selected при експорті. Налаштування вигляду для конкретного preview можна тримати **in-memory**; без додаткової схеми історії оформлення чи PDF/issuer snapshots. Повторний виклик заново читає frozen ревізію. Якщо дані frozen відсутні/суперечливі — fail-closed із зрозумілою помилкою, без часткового «успішного PDF».
7. Архітектура: shared KMP документна модель з типізованими секціями, `FrozenRevisionDocumentSource`/validator, `DocumentPresentationOptions`, optional slots/providers для майбутніх Settings та фото, platform-independent layout/render contract, CMP preview. Android PDF adapter із максимально повторним використанням common logic; Windows/iOS збірки/тести тієї самої моделі, не вдавати, що нативний export готовий там, де host (#42) ще не підтримує його. Звести різницю між platform preview/output до мінімуму без дублювання бізнес-логіки.
8. Ніякої автоматичної reprice, зміни source/status, фінансового edit, випадкового документа з latest, auto-save, consolidated PDF, нових photo/issuer settings tables. Зберегти legacy PDF без регресій; якщо міграція необхідна, спершу довести чому, і не додавати «про запас».

## Контракт точності та безпеки
- Frozen format 1/2/3: не переписувати payload/content proof; новий документ із format 3 зберігає `Long` гроші/мілікількості і точну versioned quantity policy. Ніякого `Double` для фінансового шляху, silent coercion або відновлення ціни з поточного каталогу. Інваріанти total/discount/omissions/drawing перевіряються до rendering.
- Old v1/v2/v3 зберігають свої status і chosen selection. Проект може змінитися/прайс оновитися — це не переоцінює frozen history. Використовувати лише дозволені нефінансові поточні поля, з прозорим distinction.
- При відсутності issuer/photos жодних порожніх блоків чи fake текстів. Не виводити full UUID, SQLCipher keys, debug stack traces, персональні дані в logs.

## Acceptance / тести (оновлювати разом зі змінами, issue #118)
- KMP test: v1/v2/v3 вибрані точним UUID; latest і selected різні; PDF рядки/підсумки саме обраної версії; after price/geometry change frozen зміст незмінний; зміна статусу/вибору під час preview не мутує кошторис.
- Грошові golden tests: format 3 `4.12×3.34=13.7608 → 13.761`, ціна 100/м² = 1376.10; half-up, знижка, крайні величини, нульові дозволені ціни, FULL/PARTIAL omissions; compare extracted PDF numbers and shared render model, жодного double rounding.
- Layout golden/smoke: довгі назви/примітки, багатосторінкова таблиця, Unicode, page numbers, orientation, не обрізані totals/переноси, схема L/полігон/rectangle зі snapshot (не поточної геометрії). Sections on/off ніколи не зменшують financial truth.
- Реальний Android SQLCipher → frozen load → preview → PDF bytes/SAF → повторити після restart/backup-restore, помилки I/O/cancel, не змінюються DB rows/status/selected. Перевірити legacy PDF v1. На Windows/iOS — спільні контракти, SQLCipher reopen/import proofs і нативна CI відповідно до наявних host capabilities.
- Перед push визначити залежні існуючі тести й оновити з функцією; не послаблювати перевірки заради green. **Gate:** Android + Windows/iOS CI на одному фінальному SHA, незалежне code review, owner APK acceptance; merge лише власник.

## Не входить
P4C4 consolidated PDF; issuer/company Settings UI та зберігання (#114); фото у БД/Photo Picker; багатошаблонний дизайнер; вільне редагування PDF; підписи та юридично значимий e-signature; видалення кошторисів (власник відклав); повний UX #95; native hosts #42; CSV/export каталогу.

## Вихід Codex
Один існуючий Draft PR, реалізований і протестований без прихованої зміни бізнес-правил. У PR описати architecture, supported platforms, migrations (якщо були), явні відкладені hook-и та фактичні CI результати. Створювати інший PR/робити merge/закривати umbrella #44 автоматично — заборонено.

## Codex start protocol
1. Read `AGENTS.md`, `docs/PROJECT_ROADMAP.md`, this handoff and current main code paths: `app/src/main/java/com/andreyceilings/app/ui/screens/ImmutableEstimatePdfSnapshot.kt`, `EstimateScreens.kt`, `EstimateHistoryDialog.kt`, `shared` frozen format-3 reader/writer, and relevant SQLCipher tests. Treat old roadmap historical fragments as superseded.
2. Work **only in this existing Draft PR branch**, do not open a second implementation PR. Start with written design describing trusted frozen source, render model, section registry, provider interfaces, preview-vs-PDF parity and platform adapter boundaries. If existing architecture has a better module split, implement equivalent safe semantics, not named interfaces by rote.
3. Implement in small logical commits; update impacted tests **with the code** (#118), run local checks, then final-SHA native CI. Report evidence honestly, including host limits. Do not mark owner APK approval yourself.
4. Leave #44 open until P4C4, #114 future issuer settings, #95 UX, #42 hosts; do not implement deferred requests in this PR.
