#!/usr/bin/env python3
"""Fail-closed decision for an explicit FULL CI gate.

A green Draft advisory must never impersonate the required full merge check.
Consumes GitHub needs results; no status is inferred from an earlier run,
cached artifact, matching branch name, or skipped job.
"""
from __future__ import annotations

import argparse
from dataclasses import dataclass
from pathlib import Path

VALID_MODES = frozenset({"FULL", "FAST", "TARGETED"})
VALID_RESULTS = frozenset({"success", "failure", "skipped", "cancelled", "timed_out", "neutral", "action_required"})


@dataclass(frozen=True)
class GateDecision:
    passed: bool
    is_full_gate: bool
    message: str


def decide(event: str, draft: str, classifier_result: str, mode: str,
           jobs: tuple[str, ...]) -> GateDecision:
    if event not in {"pull_request", "push", "merge_group", "workflow_dispatch"}:
        return GateDecision(False, False, "unrecognized event")
    if draft not in {"true", "false", ""}:
        return GateDecision(False, False, "invalid PR draft state")
    if event == "pull_request" and draft not in {"true", "false"}:
        return GateDecision(False, False, "missing PR draft state")
    if classifier_result != "success" or mode not in VALID_MODES:
        return GateDecision(False, False, "impact classifier failed or returned an unknown mode")
    if not jobs or any(result not in VALID_RESULTS for result in jobs):
        return GateDecision(False, False, "missing or invalid job result")
    # Only PR drafts can be FAST/TARGETED without a full run. A manual run,
    # merge queue candidate, ready PR, and main push require real FULL tests.
    is_full_gate = event != "pull_request" or draft == "false"
    must_run = is_full_gate or mode == "FULL"
    if must_run:
        if any(result != "success" for result in jobs):
            return GateDecision(False, is_full_gate,
                                "FULL requested but at least one real platform job did not succeed")
        return GateDecision(True, is_full_gate,
                            "FULL completed on this run" if is_full_gate
                            else "Draft critical changes: FULL completed but not merge authorization")
    if any(result != "skipped" for result in jobs):
        return GateDecision(False, False,
                            "Draft FAST/TARGETED expected FULL jobs to be skipped")
    return GateDecision(True, False,
                        "Draft FAST/TARGETED only; no full result and not merge authorization")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--event", required=True)
    parser.add_argument("--draft", required=True)
    parser.add_argument("--impact-result", required=True)
    parser.add_argument("--mode", required=True)
    parser.add_argument("--job-result", action="append", required=True)
    parser.add_argument("--summary", type=Path)
    parser.add_argument("--expected-sha", required=True)
    parser.add_argument("--actual-sha", required=True)
    args = parser.parse_args(argv)
    choice = decide(args.event, args.draft, args.impact_result, args.mode,
                    tuple(args.job_result))
    # For a PR use pull_request.head.sha; for merge_group use merge queue github.sha.
    sha_matches = (len(args.expected_sha) == 40
                   and args.expected_sha == args.actual_sha)
    passed = choice.passed and sha_matches
    label = "FULL verified" if passed and choice.is_full_gate else "Draft only" if passed else "FAILED"
    message = choice.message if sha_matches else "checked-out code SHA differs from required event SHA"
    print(f"{label}: {message}; tested checkout {args.actual_sha}; requested {args.expected_sha}")
    if args.summary:
        with args.summary.open("a", encoding="utf-8") as out:
            out.write(f"## {label}\n\n{message}\n\n")
            out.write(f"Expected commit: {args.expected_sha}\n\n")
            out.write(f"Actual checkout: {args.actual_sha}\n\n")
            out.write(f"Platform job conclusions: {', '.join(args.job_result)}\n\n")
            out.write("A Draft-only result is never the stable required FULL check.\n")
    return 0 if passed else 1


if __name__ == "__main__":
    raise SystemExit(main())
