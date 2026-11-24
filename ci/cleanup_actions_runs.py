#!/usr/bin/env python3
"""Conservative GitHub Actions history retention; designed for scheduled runs.

Runs/records can be irreversibly deleted. Selection is intentionally fail-closed:
- never touch incomplete or recent runs, or the cleanup workflow itself;
- preserve latest results per workflow and per branch, plus FULL baselines;
- check for non-expired artifacts and verify the run has not changed;
- delete only a bounded number each day.

Uses only Python standard library and GITHUB_TOKEN (Actions: write).
"""

from __future__ import annotations

import argparse
from collections import defaultdict
from datetime import datetime, timedelta, timezone
import json
import os
import re
import sys
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen

API_BASE = "https://api.github.com"
COMPLETED = "completed"
FAILURES = frozenset({"failure", "timed_out", "startup_failure", "action_required", "stale"})
KNOWN_CONCLUSIONS = FAILURES | frozenset({"success", "cancelled", "skipped", "neutral"})
FULL_WORKFLOWS = frozenset({"android-debug-apk.yml", "cmp-kmp-platforms.yml"})
CLEANUP_WORKFLOW = "cleanup-actions-history.yml"
RECENT_WORKFLOW_KEEP = 10
RECENT_BRANCH_KEEP = 5
MAIN_FULL_BASELINES_KEEP = 2  # two successes and two failures per FULL workflow
PAGE_SIZE = 100
MAX_PAGES = 100
MAX_DAILY_DELETE = 50


def timestamp(value: object) -> datetime | None:
    if not isinstance(value, str) or not value:
        return None
    try:
        result = datetime.fromisoformat(value.replace("Z", "+00:00"))
        return result.astimezone(timezone.utc) if result.tzinfo else None
    except ValueError:
        return None


def workflow_filename(run: dict) -> str:
    path = run.get("path")
    if not isinstance(path, str) or not path.startswith(".github/workflows/"):
        return ""
    return path.split("@", 1)[0].rsplit("/", 1)[-1]


def full_run(run: dict) -> bool:
    return (workflow_filename(run) in FULL_WORKFLOWS
            and run.get("event") in ("push", "workflow_dispatch"))


def retention_days(run: dict) -> int:
    """Age after the LAST activity, rather than the original run creation."""
    failed = run.get("conclusion") in FAILURES
    if full_run(run):
        return 180 if failed else 120
    if run.get("event") == "workflow_dispatch":
        return 180 if failed else 90
    if failed:
        return 90
    if run.get("conclusion") in ("cancelled", "skipped", "neutral"):
        return 14
    return 30


def last_activity(run: dict) -> datetime | None:
    created = timestamp(run.get("created_at"))
    updated = timestamp(run.get("updated_at"))
    return max(created, updated) if created is not None and updated is not None else None


def protected_ids(runs: list[dict]) -> set[int]:
    """Newer results always remain, even if they are many months old."""
    keep: set[int] = set()
    workflow_counts: dict[int, int] = defaultdict(int)
    branch_counts: dict[tuple[int, str], int] = defaultdict(int)
    full_baselines: dict[tuple[int, str], int] = defaultdict(int)
    ordered = sorted(runs, key=lambda r: (r.get("created_at") or "", r.get("id") or 0), reverse=True)
    for run in ordered:
        run_id, wf = run.get("id"), run.get("workflow_id")
        if not isinstance(run_id, int) or not isinstance(wf, int):
            continue
        branch = run.get("head_branch") or "<unknown>"
        wb = (wf, str(branch))
        if workflow_counts[wf] < RECENT_WORKFLOW_KEEP:
            keep.add(run_id)
        workflow_counts[wf] += 1
        if branch_counts[wb] < RECENT_BRANCH_KEEP:
            keep.add(run_id)
        branch_counts[wb] += 1
        if full_run(run) and branch == "main" and run.get("status") == COMPLETED:
            result = "failed" if run.get("conclusion") in FAILURES else "passed" if run.get("conclusion") == "success" else None
            if result is not None:
                key = (wf, result)
                if full_baselines[key] < MAIN_FULL_BASELINES_KEEP:
                    keep.add(run_id)
                full_baselines[key] += 1
    return keep


def candidates(runs: list[dict], now: datetime) -> list[dict]:
    if now.tzinfo is None:
        raise ValueError("now must be timezone-aware")
    keep = protected_ids(runs)
    eligible: list[dict] = []
    for run in runs:
        run_id = run.get("id")
        if (not isinstance(run_id, int) or run_id in keep
                or run.get("status") != COMPLETED
                or run.get("conclusion") not in KNOWN_CONCLUSIONS
                or not isinstance(run.get("workflow_id"), int)
                or not workflow_filename(run)
                or workflow_filename(run) == CLEANUP_WORKFLOW):
            continue
        activity = last_activity(run)
        if activity is None or activity > now - timedelta(days=retention_days(run)):
            continue
        eligible.append(run)
    # Oldest first; bounded incremental cleanup limits request rate and risk.
    return sorted(eligible, key=lambda r: (last_activity(r), r["id"]))


def safe_repository(value: str) -> str:
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", value):
        raise ValueError("Invalid GITHUB_REPOSITORY")
    if any(part in (".", "..") for part in value.split("/")):
        raise ValueError("Invalid GITHUB_REPOSITORY segment")
    return value


class GitHubAPI:
    def __init__(self, repository: str, token: str) -> None:
        self.repository = safe_repository(repository)
        if not token:
            raise ValueError("GITHUB_TOKEN is required")
        self.token = token
        self.root = f"/repos/{self.repository}"

    def request(self, method: str, path: str, params: dict | None = None) -> dict | None:
        url = f"{API_BASE}{self.root}{path}"
        if params:
            url += "?" + urlencode(params)
        request = Request(url, method=method, headers={
            "Accept": "application/vnd.github+json",
            "Authorization": f"Bearer {self.token}",
            "X-GitHub-Api-Version": "2022-11-28",
            "User-Agent": "andrey-ceilings-actions-retention",
        })
        try:
            with urlopen(request, timeout=30) as response:
                if method == "DELETE":
                    if response.status != 204:
                        raise RuntimeError(f"Unexpected delete HTTP {response.status}")
                    return None
                if response.status != 200:
                    raise RuntimeError(f"Unexpected API HTTP {response.status}")
                return json.load(response)
        except (HTTPError, URLError) as exc:
            # Do not log tokens or unrestricted response bodies.
            raise RuntimeError(f"GitHub Actions API {method} failed: {type(exc).__name__}, status={getattr(exc, 'code', 'network')}") from exc

    def list_runs(self) -> list[dict]:
        runs: list[dict] = []
        expected: int | None = None
        for page in range(1, MAX_PAGES + 1):
            payload = self.request("GET", "/actions/runs", {"per_page": PAGE_SIZE, "page": page})
            if not isinstance(payload, dict) or not isinstance(payload.get("workflow_runs"), list) or not isinstance(payload.get("total_count"), int):
                raise RuntimeError("Incomplete Actions listing; refusing to delete")
            if expected is None:
                expected = payload["total_count"]
            batch = payload["workflow_runs"]
            runs.extend(batch)
            if len(runs) >= expected:
                if len(runs) != expected or len({r.get("id") for r in runs}) != expected:
                    raise RuntimeError("Actions listing changed during pagination; refusing to delete")
                return runs
            if not batch:
                break
        raise RuntimeError(f"Actions listing incomplete ({len(runs)} of {expected}); refusing to delete")

    def get_run(self, run_id: int) -> dict:
        value = self.request("GET", f"/actions/runs/{run_id}")
        if not isinstance(value, dict):
            raise RuntimeError("Cannot verify run before deletion")
        return value

    def has_live_artifacts(self, run_id: int) -> bool:
        data = self.request("GET", f"/actions/runs/{run_id}/artifacts", {"per_page": 100})
        if not isinstance(data, dict) or not isinstance(data.get("total_count"), int) or not isinstance(data.get("artifacts"), list):
            raise RuntimeError("Cannot verify run artifacts")
        # More than one API page: fail closed, rather than missing an artifact.
        if data["total_count"] > len(data["artifacts"]):
            return True
        return any(a.get("expired") is not True for a in data["artifacts"])

    def delete_run(self, run_id: int) -> None:
        self.request("DELETE", f"/actions/runs/{run_id}")


def still_same_completed_run(original: dict, fresh: dict, now: datetime) -> bool:
    """Do not delete a run which has been re-run or changed since selection."""
    fields = ("id", "workflow_id", "head_sha", "head_branch", "event", "path", "created_at", "updated_at", "run_attempt", "conclusion")
    return (isinstance(original.get("run_attempt"), int)
            and original["run_attempt"] >= 1
            and all(original.get(field) == fresh.get(field) for field in fields)
            and fresh.get("status") == COMPLETED
            and last_activity(fresh) is not None
            and last_activity(fresh) <= now - timedelta(days=retention_days(fresh)))


def execute(api: GitHubAPI, now: datetime, limit: int, dry_run: bool) -> dict:
    if limit not in range(1, MAX_DAILY_DELETE + 1):
        raise ValueError(f"max-delete must be between 1 and {MAX_DAILY_DELETE}")
    runs = api.list_runs()
    selected = candidates(runs, now)
    stats: dict = {"total": len(runs), "eligible": len(selected), "checked": 0,
                   "protected_artifacts": 0, "changed": 0, "deleted": 0, "dry_run": dry_run, "ids": []}
    # Only validate up to the bounded number on each scheduled execution.
    for run in selected:
        # A few oldest candidates may still hold unexpired artifacts. Continue
        # past them, but keep both the scan and the deletion batch bounded.
        if stats["checked"] >= 3 * limit or len(stats["ids"]) >= limit:
            break
        stats["checked"] += 1
        run_id = run["id"]
        fresh = api.get_run(run_id)
        if not still_same_completed_run(run, fresh, now):
            stats["changed"] += 1
            continue
        if api.has_live_artifacts(run_id):
            stats["protected_artifacts"] += 1
            continue
        stats["ids"].append(run_id)
        if not dry_run:
            api.delete_run(run_id)
            stats["deleted"] += 1
        print(f"{'DRY-RUN would delete' if dry_run else 'Deleted'} run #{run_id}: "
              f"{workflow_filename(run)}, event={run.get('event')}, conclusion={run.get('conclusion')}, "
              f"updated={run.get('updated_at')}")
    return stats


def write_summary(stats: dict) -> None:
    text = ["## GitHub Actions history retention", "",
            f"- Mode: **{'DRY RUN (no deletions)' if stats['dry_run'] else 'LIVE cleanup'}**",
            f"- Runs listed: **{stats['total']}**; age-eligible after protected snapshots: **{stats['eligible']}**",
            f"- Checked this execution: **{stats['checked']}** (at most 3 checks per deletion slot)",
            f"- Skipped for non-expired artifacts: **{stats['protected_artifacts']}**; changed since listing: **{stats['changed']}**",
            f"- {'Would delete' if stats['dry_run'] else 'Deleted'}: **{len(stats['ids'])}**",
            "", "Protected: non-completed runs; 10 latest per workflow; 5 latest per workflow/branch;",
            "2 latest FULL main successes and failures; live artifacts; cleanup workflow itself.",
            "Retention from last activity: PR successful 30d, cancelled 14d, failed 90d;",
            "manual 90d/180d (failure); FULL 120d/180d (failure).",
            "", "Selected run IDs: " + (", ".join(map(str, stats["ids"][:30])) or "none")]
    rendered = "\n".join(text) + "\n"
    print(rendered)
    output = os.environ.get("GITHUB_STEP_SUMMARY")
    if output:
        with open(output, "a", encoding="utf-8") as stream:
            stream.write(rendered)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dry-run", action="store_true", help="preview decisions without deleting any run")
    parser.add_argument("--max-delete", type=int, default=40)
    args = parser.parse_args()
    # Prevent invoking this script as a delete-anywhere utility by accident.
    if os.environ.get("GITHUB_EVENT_NAME") not in ("schedule", "workflow_dispatch"):
        parser.error("only schedule or manual workflow_dispatch is allowed")
    if os.environ.get("GITHUB_REF") != "refs/heads/main":
        parser.error("cleanup only runs from the protected default branch main")
    try:
        api = GitHubAPI(os.environ.get("GITHUB_REPOSITORY", ""), os.environ.get("GITHUB_TOKEN", ""))
        result = execute(api, datetime.now(timezone.utc), args.max_delete, args.dry_run)
        write_summary(result)
        return 0
    except (ValueError, RuntimeError) as error:
        print(f"Actions cleanup stopped safely: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
