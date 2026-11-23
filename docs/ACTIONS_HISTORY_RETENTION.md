# Automatic GitHub Actions run history retention

Workflow: `.github/workflows/cleanup-actions-history.yml` (**Retain GitHub Actions history**).

## Enable and operate

Merge the dedicated PR into `main`. GitHub runs the workflow daily at **04:37 UTC** (the GitHub scheduler may delay a run). Scheduled runs delete eligible history; they are deliberately capped at **40 deletions per day** by default (maximum 50), checking up to three old candidates per deletion slot to skip still-protected artifacts. Nothing is deleted merely by opening the PR.

For a safe manual preview: **Actions → Retain GitHub Actions history → Run workflow → branch `main` → `dry_run=true` (default)**. The run's Summary lists candidate IDs. For manually accelerated cleanup on `main`, select `dry_run=false`; use `max_delete` 1–50.

The workflow cannot run against PR branches. It uses the GitHub-provided short-lived `GITHUB_TOKEN` with `actions: write` and `contents: read`, and does not require a personal access token.

## Retention and safety

The age threshold is calculated from a run's **last activity** (`updated_at`), not just its creation. Only runs with `status=completed` can be deleted:

| Kind | Success/neutral | Failure/timeout | Cancelled or skipped |
| --- | ---: | ---: | ---: |
| PR quick and other automatic workflows | 30 days | 90 days | 14 days |
| Explicit manual workflow dispatch, other than FULL | 90 days | 180 days | 90 days |
| FULL Android and Windows/iOS (manual or push) | 120 days | 180 days | 120 days |

Additional permanent guards independent of age:

- 10 newest runs of **every workflow** and 5 newest runs **per workflow/branch**.
- 2 newest successful and 2 newest failed FULL runs **on `main`** per workflow (baseline evidence).
- In-progress, queued, pending, or otherwise incomplete runs; cleanup workflow's own history.
- Runs with a non-expired artifact, including Android APKs and failing-test diagnostics. When the artifact expires normally, the run becomes eligible only after the applicable age threshold.
- Runs re-run or modified after listing: status, attempts, identity, timestamps and result are checked again immediately before any deletion.

Each invocation first enumerates the complete run history; if pagination is incomplete or GitHub's API fails, it stops instead of guessing. It deletes at most 50 runs per invocation, one at a time, with no bulk API. Logs/history deleted this way cannot be recovered, so the dry-run preview is provided by default for manual invocation.

**This cleans completed workflow run history and its logs; it is not the cache cleaner.** Artifact expiration is governed separately by `retention-days` in existing workflows (3, 7, 14 or 30 days). It does not change the PR FAST vs post-merge FULL policy, production signing or any build/test workflow.
