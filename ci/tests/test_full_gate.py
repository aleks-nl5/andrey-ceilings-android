"""Gate decisions: a skipped job can never satisfy a required FULL check."""
import subprocess
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from verify_full_gate import decide

SCRIPT = Path(__file__).resolve().parents[1] / "verify_full_gate.py"
SHA = "a" * 40


class FullGateTest(unittest.TestCase):
    def test_ready_and_merge_events_require_all_real_jobs(self):
        for event, draft in (
            ("pull_request", "false"), ("push", ""), ("merge_group", ""),
            ("workflow_dispatch", ""),
        ):
            for mode in ("FAST", "TARGETED", "FULL"):
                with self.subTest(event=event, mode=mode):
                    ok = decide(event, draft, "success", mode, ("success", "success"))
                    self.assertTrue(ok.passed)
                    self.assertTrue(ok.is_full_gate)
                    for bad in ("skipped", "failure", "cancelled", "neutral", ""):
                        self.assertFalse(decide(event, draft, "success", mode,
                                                (bad, "success")).passed)

    def test_draft_noncritical_can_skip_full_jobs_only(self):
        for mode in ("FAST", "TARGETED"):
            with self.subTest(mode=mode):
                ok = decide("pull_request", "true", "success", mode, ("skipped", "skipped"))
                self.assertTrue(ok.passed)
                self.assertFalse(ok.is_full_gate)
                self.assertIn("not merge authorization", ok.message)
                self.assertFalse(decide("pull_request", "true", "success", mode,
                                        ("success", "skipped")).passed)

    def test_draft_critical_requires_full_but_cannot_claim_merge_gate(self):
        ok = decide("pull_request", "true", "success", "FULL", ("success", "success"))
        self.assertTrue(ok.passed)
        self.assertFalse(ok.is_full_gate)
        self.assertFalse(decide("pull_request", "true", "success", "FULL",
                                ("skipped", "skipped")).passed)

    def test_fail_closed_on_unknown_mode_event_missing_results_or_classifier(self):
        for item in (
            ("pull_request", "false", "failure", "FULL", ("success",)),
            ("pull_request", "true", "success", "", ("skipped",)),
            ("pull_request", "", "success", "FULL", ("success",)),
            ("pull_request", "true", "success", "TARGETED", ()),
            ("pull_request", "true", "success", "TARGETED", ("foo",)),
            ("weird", "false", "success", "FULL", ("success",)),
        ):
            with self.subTest(item=item):
                self.assertFalse(decide(*item).passed)

    def test_real_cli_verifies_exact_checkout_sha(self):
        with tempfile.TemporaryDirectory() as d:
            summary = str(Path(d) / "summary.md")
            args = [sys.executable, str(SCRIPT), "--event", "pull_request",
                    "--draft", "false", "--impact-result", "success",
                    "--mode", "FULL", "--job-result", "success",
                    "--expected-sha", SHA, "--actual-sha", SHA,
                    "--summary", summary]
            good = subprocess.run(args, capture_output=True, text=True)
            self.assertEqual(good.returncode, 0, good.stdout + good.stderr)
            self.assertIn("FULL verified", Path(summary).read_text())
            bad = subprocess.run(args[:-3] + ["b" * 40, "--summary", summary],
                                 capture_output=True, text=True)
            self.assertNotEqual(bad.returncode, 0)


if __name__ == "__main__":
    unittest.main()
