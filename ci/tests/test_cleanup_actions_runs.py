"""Offline, fake-API regression checks: no live Actions run is ever deleted."""
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from cleanup_actions_runs import (
    CLEANUP_WORKFLOW, GitHubAPI, MAX_DAILY_DELETE, candidates,
    execute, full_run, protected_ids, retention_days, safe_repository,
    still_same_completed_run,
)

NOW = datetime(2026, 11, 23, 23, 0, tzinfo=timezone.utc)


def run(number, age, *, filename="draft-impact-feedback.yml", event="pull_request",
        status="completed", conclusion="success", branch="feature", workflow=1,
        updated_age=None, attempts=1):
    created = NOW - timedelta(days=age)
    updated = NOW - timedelta(days=age if updated_age is None else updated_age)
    return {
        "id": number, "workflow_id": workflow,
        "path": f".github/workflows/{filename}@refs/heads/{branch}",
        "event": event, "head_branch": branch, "head_sha": f"{number:040x}",
        "status": status, "conclusion": conclusion,
        "created_at": created.isoformat(), "updated_at": updated.isoformat(),
        "run_attempt": attempts,
    }


class FakeAPI:
    def __init__(self, runs, *, live_artifacts=(), changed=()):
        self.runs = runs
        self.live_artifacts = set(live_artifacts)
        self.changed = set(changed)
        self.deleted = []
        self.checked = []

    def list_runs(self):
        return self.runs

    def get_run(self, identifier):
        self.checked.append(identifier)
        source = next(r for r in self.runs if r["id"] == identifier)
        return dict(source, run_attempt=source["run_attempt"] + (1 if identifier in self.changed else 0))

    def has_live_artifacts(self, identifier):
        return identifier in self.live_artifacts

    def delete_run(self, identifier):
        self.deleted.append(identifier)


class RetentionRules(unittest.TestCase):
    def test_retention_categories(self):
        self.assertEqual(30, retention_days(run(1, 50)))
        self.assertEqual(14, retention_days(run(2, 50, conclusion="cancelled")))
        self.assertEqual(90, retention_days(run(3, 50, conclusion="failure")))
        self.assertEqual(90, retention_days(run(4, 50, event="workflow_dispatch")))
        self.assertEqual(180, retention_days(run(5, 50, event="workflow_dispatch", conclusion="timed_out")))
        self.assertEqual(120, retention_days(run(6, 50, filename="android-debug-apk.yml", event="push", branch="main")))
        self.assertEqual(180, retention_days(run(7, 50, filename="cmp-kmp-platforms.yml", event="workflow_dispatch", conclusion="failure")))
        self.assertTrue(full_run(run(8, 200, filename="cmp-kmp-platforms.yml", event="workflow_dispatch")))
        self.assertFalse(full_run(run(9, 200, filename="cmp-kmp-platforms.yml", event="pull_request")))

    def test_recent_and_branch_baseline_protection_even_when_old(self):
        records = [run(i, 90 + i, branch="feature", workflow=1) for i in range(1, 16)]
        kept = protected_ids(records)
        self.assertEqual(set(range(1, 11)), kept)
        self.assertEqual(list(range(15, 10, -1)), [r["id"] for r in candidates(records, NOW)])
        records += [run(i + 100, 160 + i, branch="second", workflow=1) for i in range(1, 7)]
        self.assertTrue({101, 102, 103, 104, 105}.issubset(protected_ids(records)))

    def test_full_main_last_two_successes_and_failures_preserved_indefinitely(self):
        records = [run(i, 150 + i, filename="android-debug-apk.yml", event="push",
                       branch="main", workflow=15, conclusion="success" if i % 2 else "failure")
                   for i in range(1, 40)]
        preserved = protected_ids(records)
        # 10 newest from workflow + additional baseline protection if not among them.
        self.assertIn(1, preserved)
        self.assertIn(2, preserved)
        self.assertNotIn(30, preserved)
        self.assertNotIn(28, {r["id"] for r in candidates(records, NOW)})  # failed, age=178 < 180 days
        self.assertIn(30, {r["id"] for r in candidates(records, NOW)})  # exact 180-day boundary
        self.assertIn(32, {r["id"] for r in candidates(records, NOW)})

    def test_in_progress_missing_data_and_self_workflow_are_never_candidates(self):
        records = [run(i, 200 + i, workflow=i, filename="random.yml") for i in range(1, 6)]
        records += [run(50, 999, status="in_progress"),
                    run(51, 999, status="queued", conclusion=None),
                    run(52, 999, filename=CLEANUP_WORKFLOW),
                    dict(run(53, 999), updated_at="invalid"),
                    dict(run(54, 999), path="not-workflow"),
                    dict(run(55, 999), conclusion="unknown-result")]
        # Even with 100+ other runs, none of the unsafe records can be removed.
        records += [run(i, 100 + i, workflow=999) for i in range(100, 130)]
        selected = {r["id"] for r in candidates(records, NOW)}
        self.assertTrue(set(range(50, 56)).isdisjoint(selected))

    def test_recent_rerun_refresh_protects_old_creation(self):
        records = [run(i, 100 + i, workflow=1) for i in range(1, 30)]
        records[-1] = run(29, 130, updated_age=1)
        self.assertNotIn(29, {x["id"] for x in candidates(records, NOW)})
        self.assertFalse(still_same_completed_run(records[0], dict(records[0], status="queued"), NOW))
        self.assertFalse(still_same_completed_run(records[0], dict(records[0], run_attempt=2), NOW))
        self.assertFalse(still_same_completed_run(records[0], dict(records[0], updated_at=NOW.isoformat()), NOW))

    def test_dry_run_never_deletes_and_preserves_artifacts(self):
        records = [run(i, 100 + i, workflow=1) for i in range(1, 30)]
        fake = FakeAPI(records, live_artifacts={29}, changed={28})
        result = execute(fake, NOW, 20, dry_run=True)
        self.assertEqual([], fake.deleted)
        self.assertEqual(1, result["protected_artifacts"])
        self.assertEqual(1, result["changed"])
        self.assertEqual(19, result["checked"])  # 29 total - 10 protected
        self.assertEqual(len(result["ids"]), 17)

    def test_protected_artifacts_do_not_starve_older_cleanup_batch(self):
        records = [run(i, 120 + i, workflow=1) for i in range(1, 90)]
        # Oldest 8 would-be targets still have artifacts; process later ones.
        preserved = set(range(82, 90))
        fake = FakeAPI(records, live_artifacts=preserved)
        result = execute(fake, NOW, 4, dry_run=False)
        self.assertEqual(4, result["deleted"])
        self.assertEqual(8, result["protected_artifacts"])
        self.assertEqual(12, result["checked"])
        self.assertTrue(preserved.isdisjoint(fake.deleted))

    def test_live_mode_bounded_and_does_not_delete_protected_runs(self):
        records = [run(i, 100 + i, workflow=1) for i in range(1, 80)]
        fake = FakeAPI(records)
        result = execute(fake, NOW, 7, dry_run=False)
        self.assertEqual(7, len(fake.deleted))
        self.assertEqual(7, result["deleted"])
        self.assertEqual(fake.deleted, result["ids"])
        self.assertTrue(all(i not in protected_ids(records) for i in fake.deleted))

    def test_reject_bad_limits_and_repo(self):
        for limit in (0, -1, MAX_DAILY_DELETE + 1):
            with self.assertRaises(ValueError):
                execute(FakeAPI([]), NOW, limit, False)
        for repo in ("owner/name/extra", "../repo", "owner repo", "", "github.com/owner/repo"):
            with self.assertRaises(ValueError):
                safe_repository(repo)
        self.assertEqual("aleks-nl5/andrey-ceilings-android", safe_repository("aleks-nl5/andrey-ceilings-android"))

    def test_incomplete_pagination_aborts_without_deletion(self):
        class FakeGitHub(GitHubAPI):
            def __init__(self):
                pass
            def request(self, method, path, params=None):
                return {"total_count": 3, "workflow_runs": [run(1, 180)]} if params["page"] == 1 else {"total_count": 3, "workflow_runs": []}
        with self.assertRaises(RuntimeError):
            FakeGitHub().list_runs()


if __name__ == "__main__":
    unittest.main()
