"""Fast PR APK policy: unsigned release build, isolated trusted signing, safe upgrades."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]
PR = ROOT / ".github/workflows/draft-impact-feedback.yml"
SIGN = ROOT / ".github/workflows/pr-sign-release.yml"
GRADLE = ROOT / "app/build.gradle.kts"


class PrReleaseArtifactPolicyTest(unittest.TestCase):
    def test_quick_builds_release_variant_without_production_secrets(self):
        workflow = PR.read_text(encoding="utf-8")
        self.assertIn("-PquickUnsignedRelease=true :app:assembleRelease", workflow)
        self.assertIn(":app:testDebugUnitTest", workflow)
        self.assertNotIn(":app:assembleDebug", workflow)
        self.assertNotIn("pr-fast-android-debug-apk", workflow)
        self.assertIn("pr-internal-unsigned-release-apk", workflow)
        self.assertIn("app-release-unsigned.apk", workflow)
        self.assertNotIn("secrets.ANDROID_KEYSTORE", workflow)
        self.assertNotIn("workflow_dispatch:", workflow.split("concurrency:", 1)[0])

    def test_signing_uses_only_trusted_workflow_without_checkout_or_build(self):
        signer = SIGN.read_text(encoding="utf-8")
        self.assertIn("  workflow_run:", signer)
        self.assertIn('workflows: ["PR fast checks (no FULL)"]', signer)
        self.assertIn("workflow_run.conclusion == 'success'", signer)
        self.assertIn("workflow_run.head_repository.full_name == github.repository", signer)
        self.assertIn("workflow_run.event == 'pull_request'", signer)
        self.assertIn("actions/download-artifact@v7", signer)
        self.assertIn("artifact-ids: ${{ steps.candidate.outputs.artifact_id }}", signer)
        self.assertIn("gh api", signer)
        self.assertIn("apksigner", signer)
        self.assertIn("pr-fast-android-release-apk", signer)
        self.assertIn("secrets.ANDROID_KEYSTORE_BASE64", signer)
        self.assertNotIn("actions/checkout", signer)
        self.assertNotIn("gradle --", signer)
        self.assertNotIn("pull_request_target:", signer)
        self.assertNotIn("permissions:\n  contents: write", signer)

    def test_manual_signing_resolves_pr_branch_without_run_ids(self):
        signer = SIGN.read_text(encoding="utf-8")
        self.assertIn("  issue_comment:", signer)
        self.assertIn("types: [created]", signer)
        self.assertIn("github.event.comment.body == '/sign-apk'", signer)
        self.assertIn("github.actor == github.repository_owner", signer)
        self.assertIn("github.event.issue.pull_request != null", signer)
        self.assertIn("  workflow_dispatch:", signer)
        self.assertIn("      pr_number:", signer)
        self.assertNotIn("source_run_id:", signer)
        self.assertIn('PR_NUMBER: ${{ inputs.pr_number || github.event.issue.number }}', signer)
        self.assertIn('expected_sha="$(jq -r', signer)
        self.assertIn('source_branch="$(jq -r', signer)
        self.assertIn('actions/workflows/$quick_workflow_id/runs', signer)
        self.assertIn('sort_by([.run_number, .run_attempt])', signer)
        self.assertIn('.head_sha == $sha', signer)
        self.assertIn('.head.sha == $sha', signer)
        self.assertIn('.head.repo.full_name == $repo', signer)
        self.assertIn('pr_number', signer)
        self.assertIn("github.ref == 'refs/heads/main'", signer)

    def test_manual_signing_rejects_ci_failures_and_keeps_signing_trusted(self):
        signer = SIGN.read_text(encoding="utf-8")
        for required in (
            'name == "Classify PR risk (quick checks only)"',
            'name == "PR Android release candidate and JVM unit tests"',
            'name == "PR Windows compile and mapped scene tests"',
            '.status == "completed" and .conclusion == "success"',
            '.run_attempt == $attempt',
            '.conclusion == "cancelled"',
            '(.runner_id // 0) == 0',
            '((.steps // []) | length) == 0',
            'pr-internal-unsigned-release-apk',
            '.expired == false',
            'Unexpected release package name',
            'apksigner',
        ):
            self.assertIn(required, signer)
        self.assertIn('gh api "repos/$GITHUB_REPOSITORY/pulls/$pr_number"', signer)
        self.assertIn("secrets.ANDROID_KEYSTORE_BASE64", signer)
        self.assertNotIn("actions/checkout", signer)
        self.assertNotIn("gradle --", signer)

    def test_same_version_code_family_for_both_ci_workflows(self):
        gradle = GRADLE.read_text(encoding="utf-8")
        self.assertIn('System.getenv("GITHUB_RUN_ID")', gradle)
        self.assertIn("id.toLongOrNull()?.div(1000L)", gradle)
        self.assertIn("versionCode = ciVersionCode ?: (600000 + actionsRunNumber)", gradle)
        self.assertIn("if (quickUnsignedRelease) null else signingConfigs.getByName(\"release\")", gradle)
        self.assertIn('providers.gradleProperty("quickUnsignedRelease")', gradle)
        # The current global GitHub run id is well within Play's max version code.
        sample_runs = (45046603523, 45062464127, 45064977461)
        codes = [run // 1000 for run in sample_runs]
        self.assertEqual(codes, sorted(codes))
        self.assertTrue(all(600_000 <= code <= 2_099_999_999 for code in codes))
        self.assertGreater(codes[-1], 600_000 + 1339)


if __name__ == "__main__":
    unittest.main()
