import json
from pathlib import Path
import subprocess
import tempfile
import unittest

from ci import localization as lint

APP = "app/src/main/java/example"
SHARED = "shared/src/commonMain/kotlin/example"


class ScannerTest(unittest.TestCase):
    def test_ui_and_semantics_with_escapes_and_nested_comments(self):
        source = r'''
            /* Text("Ignored") /* nested */ */
            Text( /* comment */ "\u0417берегти" )
            Icon(icon, contentDescription = "Say \"hello\"")
            Text("Greeting: ${user.format("formal")}")
            Text("""Actual text""") // Text("Ignored")
            NotificationCompat.Builder(context).setContentTitle("Reminder")
            Text("Update settings")
        '''
        self.assertEqual(
            [("ui", "Зберегти"), ("accessibility", 'Say "hello"'),
             ("ui", 'Greeting: ${user.format("formal")}'), ("ui", "Actual text"),
             ("notification", "Reminder"), ("ui", "Update settings")],
            [entry.key for entry in lint.scan(f"{SHARED}/Screen.kt", source)],
        )

    def test_escaped_dollar_is_visible_but_a_template_variable_alone_is_not(self):
        entries = lint.scan(f"{SHARED}/Screen.kt", r'Text("\$Literal"); Text("$variable")')
        self.assertEqual(
            [("ui", r"\$Literal"), ("technical", "$variable")],
            [entry.key for entry in entries],
        )

    def test_technical_catalog_and_test_exclusions(self):
        source = '''
            Text(row.name)
            Modifier.testTag("Новий тег")
            val wireId = "Новий код"
            println("Технічна діагностика")
            Log.i("tag", "Старт")
            db.execSQL("INSERT INTO items VALUES ('Назва')")
        '''
        self.assertEqual(
            ["technical", "technical", "technical", "log", "log", "technical"],
            [entry.category for entry in lint.scan(f"{APP}/Screen.kt", source)],
        )
        self.assertEqual(["test"], [e.category for e in lint.scan(
            "app/src/androidTest/java/ScreenTest.kt", 'Text("Тест")')])
        for path in lint.CATALOG_DATA:
            self.assertEqual(["catalog_data"], [e.category for e in lint.scan(path, 'name = "Полотно"')])

    def test_android_xml_resources_and_accessibility(self):
        source = '''<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android">
            <TextView android:text="@string/title" android:contentDescription="Деталі" />
            <TextView android:text="Привіт &amp; дякую" />
            <string name="title">Привіт</string>
        </LinearLayout>'''
        self.assertEqual(
            [("accessibility", "Деталі"), ("ui", "Привіт & дякую"), ("resource", "title=Привіт")],
            [entry.key for entry in lint.scan("app/src/main/res/layout/screen.xml", source)],
        )


class GitBaselineTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.repo = Path(temp.name)
        self.run_git("init", "-q")
        self.run_git("config", "user.email", "fixture@example.invalid")
        self.run_git("config", "user.name", "Fixture")

    def run_git(self, *args):
        return subprocess.check_output(["git", "-C", str(self.repo), *args], text=True).strip()

    def write(self, path, source):
        target = self.repo / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(source, encoding="utf-8")

    def commit(self):
        self.run_git("add", "-A")
        self.run_git("commit", "-qm", "fixture")
        return self.run_git("rev-parse", "HEAD")

    def check(self, base, head):
        from unittest.mock import patch
        git = lint.git
        def git_at_fixture(*args, **kwargs):
            return git("-C", str(self.repo), *args, **kwargs)
        with patch.object(lint, "git", git_at_fixture):
            return lint.check(base, head)

    def install_baseline(self, base):
        from unittest.mock import patch
        git = lint.git
        with patch.object(lint, "git", lambda *args, **kw: git("-C", str(self.repo), *args, **kw)):
            self.write(lint.BASELINE, json.dumps(lint.inventory(base)))

    def test_rename_delete_and_whitespace_allow_existing_but_not_duplicates_or_additions(self):
        old = f"{APP}/Old.kt"
        new = f"{APP}/Renamed.kt"
        deleted = f"{APP}/Removed.kt"
        body = 'fun placeholderForGitRenameDetection() {\n' + '    // unchanged source row\n' * 12
        self.write(old, body + '    Text("Existing")\n}')
        self.write(deleted, 'Text("Removed")')
        base = self.commit()
        self.install_baseline(base)
        baseline = self.commit()
        self.run_git("mv", old, new)
        self.run_git("rm", deleted)
        self.write(new, body + '    Text( /* spacing */ "Existing" )\n}')
        self.assertEqual([], self.check(baseline, self.commit()))
        self.write(new, body + '    Text("Existing")\n    Text("Existing")\n}')
        self.write(f"{SHARED}/New.kt", 'Text("New")')
        failures = self.check(baseline, self.commit())
        self.assertEqual(2, len(failures))
        self.assertTrue(failures[0].endswith("ui: 'Existing'"))
        self.assertTrue(failures[1].endswith("ui: 'New'"))

    def test_bootstrap_inventory_cannot_allow_new_literal(self):
        path = f"{APP}/Screen.kt"
        self.write(path, 'Text("Existing")')
        base = self.commit()
        self.install_baseline(base)
        self.assertEqual([], self.check(base, self.commit()))
        self.write(path, 'Text("New")')
        self.assertEqual([f"{path}:1: ui: 'New'"], self.check(base, self.commit()))
        with self.assertRaisesRegex(ValueError, "differs from the merge base"):
            self.install_baseline(self.run_git("rev-parse", "HEAD"))
            self.check(base, self.commit())


if __name__ == "__main__":
    unittest.main()
