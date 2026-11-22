"""An accepted scoped CI receipt must never claim skipped native suites passed."""
import subprocess
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from verify_full_gate import decide

SCRIPT = Path(__file__).resolve().parents[1] / "verify_full_gate.py"
SHA = "a" * 40
GROUPS = ("windows", "apple")


class FullGateTest(unittest.TestCase):
    def test_ready_scoped_windows_skip_is_safe_only_when_declared(self):
        ok = decide("pull_request", "false", "success", "TARGETED",
                    (("windows", "skip", "skipped"), ("apple", "skip", "skipped")),
                    GROUPS)
        # A targeted Android-only PR correctly leaves CMP untested; it must
        # not turn those skips into a false Windows/iOS verification claim.
        self.assertTrue(ok.passed)
        # CMP job receives FAST when all its groups are unselected by impact.
        docs = decide("pull_request", "false", "success", "FAST",
                      (("windows", "skip", "skipped"), ("apple", "skip", "skipped")),
                      GROUPS)
        self.assertTrue(docs.passed)
        self.assertTrue(docs.is_acceptance)

    def test_windows_only_jdbc_needs_actual_native_windows_and_mac_smoke(self):
        ok = decide("pull_request", "false", "success", "TARGETED",
                    (("windows", "deep", "success"), ("apple", "smoke", "success")),
                    GROUPS)
        self.assertTrue(ok.passed)
        for bad in ("skipped", "failed", "failure", "cancelled", "neutral", ""):
            with self.subTest(status=bad):
                self.assertFalse(decide("pull_request", "false", "success", "TARGETED",
                                        (("windows", "deep", bad), ("apple", "smoke", "success")),
                                        GROUPS).passed)

    def test_shared_ui_requires_all_selected_smoke(self):
        ok = decide("pull_request", "false", "success", "TARGETED",
                    (("windows", "smoke", "success"), ("apple", "smoke", "success")),
                    GROUPS)
        self.assertTrue(ok.passed)
        self.assertFalse(decide("pull_request", "false", "success", "TARGETED",
                                (("windows", "smoke", "skipped"), ("apple", "smoke", "success")),
                                GROUPS).passed)

    def test_critical_full_requires_all_deep_and_success(self):
        for event, draft in (("pull_request", "false"), ("push", ""),
                             ("merge_group", ""), ("workflow_dispatch", "")):
            with self.subTest(event=event):
                self.assertTrue(decide(event, draft, "success", "FULL",
                                       (("windows", "deep", "success"),
                                        ("apple", "deep", "success")), GROUPS).passed)
                self.assertFalse(decide(event, draft, "success", "FULL",
                                        (("windows", "deep", "success"),
                                         ("apple", "smoke", "success")), GROUPS).passed)
                self.assertFalse(decide(event, draft, "success", "FULL",
                                        (("windows", "deep", "success"),
                                         ("apple", "deep", "skipped")), GROUPS).passed)

    def test_intermediate_draft_is_advisory_not_merge_acceptance(self):
        good = decide("pull_request", "true", "success", "TARGETED",
                      (("windows", "smoke", "skipped"), ("apple", "skip", "skipped")),
                      GROUPS)
        self.assertTrue(good.passed)
        self.assertFalse(good.is_acceptance)
        self.assertFalse(decide("pull_request", "true", "success", "TARGETED",
                                (("windows", "smoke", "success"), ("apple", "skip", "skipped")),
                                GROUPS).passed)
        full_draft = decide("pull_request", "true", "success", "FULL",
                            (("windows", "deep", "success"), ("apple", "deep", "success")),
                            GROUPS)
        self.assertTrue(full_draft.passed)
        self.assertFalse(full_draft.is_acceptance)

    def test_unknown_inputs_and_missing_group_are_red(self):
        base = (("windows", "deep", "success"), ("apple", "deep", "success"))
        for event, draft, classification, mode, data, groups in (
            ("weird", "false", "success", "FULL", base, GROUPS),
            ("pull_request", "", "success", "FULL", base, GROUPS),
            ("pull_request", "false", "failure", "FULL", base, GROUPS),
            ("pull_request", "false", "success", "unknown", base, GROUPS),
            ("pull_request", "false", "success", "FULL", base[:1], GROUPS),
            ("pull_request", "false", "success", "FULL", base + base[:1], GROUPS),
            ("pull_request", "false", "success", "FULL", base, ("windows", "windows")),
            ("pull_request", "false", "success", "FULL", (("windows", "deep", ""), base[1]), GROUPS),
        ):
            with self.subTest(event=event, mode=mode, data=data):
                self.assertFalse(decide(event, draft, classification, mode, data, groups).passed)

    def test_cli_pin_and_summary_make_skipped_platform_explicit(self):
        with tempfile.TemporaryDirectory() as d:
            summary = str(Path(d) / "summary.md")
            args = [sys.executable, str(SCRIPT), "--event", "pull_request",
                    "--draft", "false", "--impact-result", "success",
                    "--mode", "TARGETED", "--expected-group", "android",
                    "--coverage", "android:smoke:success",
                    "--expected-sha", SHA, "--actual-sha", SHA,
                    "--summary", summary]
            good = subprocess.run(args, capture_output=True, text=True)
            self.assertEqual(good.returncode, 0, good.stdout + good.stderr)
            self.assertIn("SCOPED ACCEPTANCE", Path(summary).read_text())
            bad = subprocess.run(args[:-3] + ["b" * 40, "--summary", summary],
                                 capture_output=True, text=True)
            self.assertNotEqual(bad.returncode, 0)
            malformed = subprocess.run(args[:args.index("--coverage") + 1] +
                                       ["android:success"] + args[args.index("--expected-sha"):],
                                       capture_output=True, text=True)
            self.assertNotEqual(malformed.returncode, 0)


if __name__ == "__main__":
    unittest.main()
