"""The Android smoke receipt needs passing cases for each selected class."""
from pathlib import Path
import sys
from tempfile import TemporaryDirectory
import unittest
import xml.etree.ElementTree as ET

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from verify_android_smoke_reports import verify_reports


class AndroidSmokeReportsTest(unittest.TestCase):
    def setUp(self):
        directory = TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.reports = Path(directory.name)
        self.device = self.reports / "emulator"
        self.device.mkdir()

    def write_report(self, cases):
        (self.device / "TEST-device.xml").write_text(
            f"<testsuite>{cases}</testsuite>", encoding="utf-8",
        )

    def test_all_selected_classes_have_passing_cases(self):
        self.write_report(
            '<testcase classname="app.A" name="one"/>'
            '<testcase classname="app.A" name="two"/>'
            '<testcase classname="app.B" name="one"/>'
        )
        self.assertEqual(verify_reports(self.reports, {"app.A", "app.B"}), {"app.A": 2, "app.B": 1})

    def test_missing_reports_or_empty_filter_fail(self):
        for expected in ({"app.A"}, set(), {"app.A", ""}):
            with self.subTest(expected=expected), self.assertRaises(ValueError):
                verify_reports(self.reports, expected)

    def test_each_selected_class_needs_a_passing_case(self):
        self.write_report('<testcase classname="app.A" name="one"/>')
        with self.assertRaisesRegex(ValueError, "No passing Android testcases for: app.B"):
            verify_reports(self.reports, {"app.A", "app.B"})

    def test_selected_class_with_passed_and_skipped_cases_fails(self):
        self.write_report(
            '<testcase classname="app.A" name="one"/>'
            '<testcase classname="app.A" name="two"><skipped/></testcase>'
        )
        with self.assertRaisesRegex(ValueError, "Skipped Android testcase: app.A.two"):
            verify_reports(self.reports, {"app.A"})

    def test_unselected_or_failed_cases_fail(self):
        for case, message in (
            ('<testcase classname="app.B" name="one"/>', "Unexpected Android testcase"),
            ('<testcase classname="app.A" name="two"><failure/></testcase>', "Failed Android testcase"),
            ('<testcase classname="app.A" name="two"><error/></testcase>', "Failed Android testcase"),
        ):
            with self.subTest(case=case), self.assertRaisesRegex(ValueError, message):
                self.write_report('<testcase classname="app.A" name="one"/>' + case)
                verify_reports(self.reports, {"app.A"})

    def test_malformed_xml_fails(self):
        self.write_report("<testcase>")
        with self.assertRaises(ET.ParseError):
            verify_reports(self.reports, {"app.A"})
