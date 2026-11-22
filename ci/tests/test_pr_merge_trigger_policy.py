"""Regression policy: only main pushes/manual dispatch may run full native workflows."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
HEAVY = (
    ROOT / ".github/workflows/android-debug-apk.yml",
    ROOT / ".github/workflows/cmp-kmp-platforms.yml",
)
QUICK = ROOT / ".github/workflows/draft-impact-feedback.yml"


class PrMergeTriggerPolicyTest(unittest.TestCase):
    def test_native_full_workflows_do_not_run_on_pr_or_merge_queue(self):
        for path in HEAVY:
            with self.subTest(workflow=path.name):
                contents = path.read_text(encoding="utf-8")
                triggers = contents.split("\nconcurrency:", 1)[0]
                self.assertIn("  push:\n    branches: [ main ]", triggers)
                self.assertIn("  workflow_dispatch:", triggers)
                self.assertNotIn("  pull_request:", triggers)
                self.assertNotIn("  merge_group:", triggers)
                self.assertIn("echo 'mode=FULL'", contents)
                self.assertIn("echo 'android_depth=deep'", contents)
                self.assertIn("echo 'desktop_depth=deep'", contents)
                self.assertIn("echo 'ios_depth=deep'", contents)
                self.assertIn("cancel-in-progress: false", contents)

    def test_pr_quick_runs_on_ready_and_all_impact_depths(self):
        contents = QUICK.read_text(encoding="utf-8")
        triggers = contents.split("\nconcurrency:", 1)[0]
        self.assertIn("  pull_request:", triggers)
        self.assertIn("ready_for_review", triggers)
        self.assertNotIn("  push:", triggers)
        self.assertNotIn("  workflow_dispatch:", triggers)
        for platform in ("android", "desktop", "ios"):
            self.assertIn(f"needs.impact.outputs.{platform}_depth != 'skip'", contents)
        self.assertIn("PR quick verification (NO FULL before merge)", contents)
        self.assertIn("native FULL not run", contents)

    def test_pr_quick_does_not_use_native_db_or_all_instrumentation(self):
        contents = QUICK.read_text(encoding="utf-8")
        self.assertIn(":app:assembleDebug", contents)
        self.assertIn(":app:testDebugUnitTest", contents)
        self.assertIn(":desktopApp:compileKotlin", contents)
        self.assertIn(":shared:linkDebugFrameworkIosSimulatorArm64", contents)
        for banned in (
            ":shared:jvmTest", ":shared:iosSimulatorArm64Test",
            "connectedDebugAndroidTest", ":shared:verifySqlDelightMigration",
            ":app:assembleRelease", "ci/run-sqlcipher-instrumentation.sh",
        ):
            self.assertNotIn(banned, contents)


if __name__ == "__main__":
    unittest.main()
