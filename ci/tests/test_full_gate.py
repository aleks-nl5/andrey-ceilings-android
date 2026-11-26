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

    def test_combined_apple_job_cannot_hide_unverified_ios(self):
        groups = ("windows", "ios", "macos")
        windows_only = (("windows", "deep", "success"), ("ios", "skip", "skipped"),
                        ("macos", "deep", "success"))
        self.assertTrue(decide("pull_request", "false", "success", "TARGETED",
                               windows_only, groups).passed)
        # The Apple runner passed macOS JVM tests, not iOS tests.
        self.assertFalse(decide("pull_request", "false", "success", "TARGETED",
                                (windows_only[0], ("ios", "deep", "skipped"),
                                 windows_only[2]), groups).passed)
        self.assertFalse(decide("pull_request", "false", "success", "TARGETED",
                                (windows_only[0], ("ios", "skip", "success"),
                                 windows_only[2]), groups).passed)
        ios_only = (("windows", "skip", "skipped"), ("ios", "smoke", "success"),
                    ("macos", "skip", "skipped"))
        self.assertTrue(decide("pull_request", "false", "success", "TARGETED",
                               ios_only, groups).passed)

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
        # Draft smoke may execute together with another deep Apple group.
        self.assertTrue(decide("pull_request", "true", "success", "TARGETED",
                               (("windows", "smoke", "success"), ("apple", "skip", "skipped")),
                               GROUPS).passed)
        full_draft = decide("pull_request", "true", "success", "FULL",
                            (("windows", "deep", "success"), ("apple", "deep", "success")),
                            GROUPS)
        self.assertTrue(full_draft.passed)
        self.assertFalse(full_draft.is_acceptance)

    def test_draft_database_changes_require_real_deep_security_tests(self):
        for group in ("android", "windows", "ios"):
            with self.subTest(group=group):
                ok = decide("pull_request", "true", "success", "TARGETED",
                            ((group, "deep", "success"),), (group,))
                self.assertTrue(ok.passed)
                self.assertFalse(ok.is_acceptance)
                for result in ("skipped", "failure", "cancelled", "neutral"):
                    self.assertFalse(decide("pull_request", "true", "success", "TARGETED",
                                            ((group, "deep", result),), (group,)).passed)
        # Shared Android DB + Windows JDBC: no deep suite can be skipped.
        groups = ("android", "windows", "ios")
        self.assertTrue(decide("pull_request", "true", "success", "TARGETED",
                               (("android", "deep", "success"),
                                ("windows", "deep", "success"),
                                ("ios", "skip", "skipped")), groups).passed)
        self.assertFalse(decide("pull_request", "true", "success", "TARGETED",
                                (("android", "deep", "success"),
                                 ("windows", "deep", "skipped"),
                                 ("ios", "skip", "skipped")), groups).passed)

    def test_draft_mixed_smoke_and_deep_on_combined_apple_runner(self):
        groups = ("windows", "ios", "macos")
        coverage = (("windows", "deep", "success"), ("ios", "smoke", "success"),
                    ("macos", "deep", "success"))
        result = decide("pull_request", "true", "success", "TARGETED", coverage, groups)
        self.assertTrue(result.passed)
        self.assertFalse(result.is_acceptance)
        self.assertFalse(decide("pull_request", "true", "success", "TARGETED",
                                (("windows", "deep", "success"), ("ios", "smoke", "failure"),
                                 ("macos", "deep", "success")), groups).passed)

    def test_sqlcipher_full_retains_recovery_without_workspace_ui(self):
        root = Path(__file__).resolve().parents[2]
        security_script = (root / "ci/run-sqlcipher-instrumentation.sh").read_text()
        android_workflow = (root / ".github/workflows/android-debug-apk.yml").read_text()
        self.assertFalse((root / ".github/workflows/android-workspace-screenshots.yml").exists())
        self.assertNotIn("screencap", security_script)
        self.assertIn("bash ci/verify-sqlcipher-process-death.sh", security_script)
        self.assertIn("bash ci/verify-production-raw-recovery.sh", security_script)
        self.assertNotIn("bash ci/verify-production-v9-upgrade.sh", security_script)
        self.assertIn("bash ci/verify-sqlcipher-enospc.sh", security_script)
        self.assertNotIn("p5a-android-workspace", android_workflow)
        self.assertIn("android-failure-diagnostics", android_workflow)

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
