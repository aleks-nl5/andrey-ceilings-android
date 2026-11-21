"""Small stdlib regression tests for GitHub Windows timing reports."""

from pathlib import Path
import tempfile
import unittest
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from windows_test_timings import main, read_results, report


class WindowsTestTimingsTest(unittest.TestCase):
    def test_rank_case_and_class_with_failures_and_skips(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root / "TEST-sample.xml").write_text(
                '<testsuite name="Suite" tests="3">'
                '<testcase classname="pkg.A" name="one" time="60.1" />'
                '<testcase classname="pkg.B" name="broken" time="2"><failure /></testcase>'
                '<testcase classname="pkg.A" name="skip" time="0"><skipped /></testcase>'
                '</testsuite>', encoding="utf-8")
            rows, count = read_results([root])
            self.assertEqual((count, len(rows)), (1, 3))
            md = report(rows, count, 3)
            self.assertIn("failures: **1**; skipped: **1**", md)
            self.assertIn("pkg.A", md)
            self.assertLess(md.index("pkg.A"), md.index("pkg.B"))
            summary = root / "summary.md"
            self.assertEqual(main(["--root", str(root), "--summary", str(summary)]), 0)
            self.assertIn("pkg.A.one", summary.read_text(encoding="utf-8"))

    def test_empty_results_does_not_mask_original_gradle_failure(self):
        with tempfile.TemporaryDirectory() as folder:
            self.assertEqual(main(["--root", folder]), 0)
            self.assertIn("No JUnit XML", report([], 0, 2))

    def test_bad_xml_and_nonfinite_duration_fail_diagnostics_explicitly(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            f = root / "TEST-bad.xml"
            f.write_text("<broken>", encoding="utf-8")
            self.assertEqual(main(["--root", folder]), 2)
            f.write_text('<testsuite><testcase name="bad" time="nan"/></testsuite>', encoding="utf-8")
            self.assertEqual(main(["--root", folder]), 2)

    def test_multiple_roots_do_not_conflate_test_names(self):
        with tempfile.TemporaryDirectory() as folder:
            a, b = Path(folder) / "shared", Path(folder) / "desktop"
            a.mkdir(); b.mkdir()
            for p in (a, b):
                (p / "TEST-x.xml").write_text(
                    '<testsuite><testcase classname="C" name="n" time="1"/></testsuite>', encoding="utf-8")
            rows, count = read_results([a,b])
            self.assertEqual((count,len(rows)), (2,2))

if __name__ == "__main__":
    unittest.main()
