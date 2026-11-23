# Ручне підписування PR APK (без очікування iOS)

**Призначення:** отримати підписаний Android APK для фізичного smoke-тесту відкритого PR, коли iOS-раннер чекає в черзі. **Це не дозволяє merge та не робить CI зеленим.**

## Як запускати

1. У GitHub Actions відкрий **Sign PR Android release APK**.
2. Натисни **Run workflow**; **Branch: main** (цей trusted workflow завжди виконується з main).
3. У поле **source_run_id** введи **номер FAST workflow run**, наприклад `45220494603` із адреси сторінки `actions/runs/45220494603`. Це **не номер PR**.
4. Запусти. У завершеному запуску відкрий **Artifacts → pr-fast-android-release-apk** (в архіві `app-release.apk`). Завантаж і протестуй підписаний файл на Android.
5. Якщо відображається помилка source/HEAD, переконайся, що FAST run відповідає **останньому** коміту відкритого PR. Якщо APK зник або протермінований — потрібна нова FAST-збірка.

## Вимоги безпеки (fail closed)

- Ніколи не виконується код/скрипт із PR у trusted signing job; workflow читається з `main`, має тільки `actions: read` та `contents: read`.
- `source_run_id` повинен належати справжньому `PR fast checks (no FULL)` у цьому репозиторії та поточному SHA відкритого PR, спрямованого в `main`; форки та застарілі коміти відхиляються.
- **Manual:** успішні Classify PR risk, Android release/JVM і Windows compile/scene jobs **того самого run attempt**; iOS може бути у стані очікування/виконання або успішним, **але не failed/cancelled**. Автоматичний `workflow_run` шлях, як і раніше, вимагає **повного success**.
- Підписується тільки однозначно визначений, непротермінований **unsigned** APK артефакт цього run; перевіряються ZIP, zipalign, package name, відсутність попереднього підпису; перевіряється результат `apksigner`.
- Production signing secrets існують тільки в trusted workflow на GitHub runner, не в PR. Вони не друкуються в логах і не завантажуються в артефакти.
- Підписаний APK — **для тестування**, а не перевірка iOS, не дозвіл публікації чи об'єднання.

**Активація:** нова кнопка ручного запуску з'являється лише **після об'єднання цього окремого PR з `main`**, оскільки GitHub бере `workflow_dispatch` із default branch. Немає автоматичних змін до PR #157.
