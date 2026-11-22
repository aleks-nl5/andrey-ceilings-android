#!/usr/bin/env python3
"""Fail-closed per-platform CI result receipt for owner-controlled manual merge.

A green scoped result NEVER represents verification of skipped platforms.
Draft results are advisory, and only actual current-run job conclusions count.
"""
from __future__ import annotations

import argparse
from dataclasses import dataclass
from pathlib import Path
import re

VALID_MODES = frozenset({"FULL", "FAST", "TARGETED"})
VALID_LEVELS = frozenset({"skip", "smoke", "deep"})
VALID_RESULTS = frozenset({"success", "failure", "skipped", "cancelled", "timed_out",
                           "neutral", "action_required"})
VALID_GROUPS = frozenset({"android", "windows", "apple"})
SHA = re.compile(r"^[0-9a-f]{40}$")


@dataclass(frozen=True)
class GateDecision:
    passed: bool
    is_acceptance: bool
    message: str


def decide(event: str, draft: str, classifier_result: str, mode: str,
           coverage: tuple[tuple[str, str, str], ...],
           expected_groups: tuple[str, ...]) -> GateDecision:
    deny = lambda why: GateDecision(False, False, why)
    if event not in {"pull_request", "push", "merge_group", "workflow_dispatch"}:
        return deny("unknown event")
    if draft not in {"true", "false", ""} or (
            event == "pull_request" and draft not in {"true", "false"}):
        return deny("missing or invalid PR draft state")
    if classifier_result != "success" or mode not in VALID_MODES:
        return deny("impact classifier failed or returned unknown mode")
    if (not expected_groups or len(set(expected_groups)) != len(expected_groups)
            or any(g not in VALID_GROUPS for g in expected_groups)):
        return deny("invalid required job groups")
    names = tuple(x[0] for x in coverage)
    if (len(names) != len(expected_groups) or set(names) != set(expected_groups)
            or len(set(names)) != len(names)):
        return deny("missing, repeated or extra platform job group")
    if any(level not in VALID_LEVELS or status not in VALID_RESULTS
           for _, level, status in coverage):
        return deny("missing or invalid platform depth/job result")
    if mode == "FULL" and any(level != "deep" for _, level, _ in coverage):
        return deny("FULL fallback must run every platform suite deeply")
    if mode == "FAST" and any(level != "skip" for _, level, _ in coverage):
        return deny("FAST only valid for documentation without platform work")

    is_draft = event == "pull_request" and draft == "true"
    # Intermediate FAST/TARGETED Draft runs are handled by a separate advice
    # workflow; these expensive workflows must remain skipped, not turn green
    # by confusing target results with FULL completion.
    if is_draft and mode != "FULL":
        if any(status != "skipped" for _, _, status in coverage):
            return deny("draft scoped jobs must remain in independent advisory workflow")
        return GateDecision(True, False, "Draft classification only; no merge authorization")

    for group, level, status in coverage:
        if level == "skip" and status != "skipped":
            return deny(f"{group}: unselected job did not remain skipped ({status})")
        if level != "skip" and status != "success":
            return deny(f"{group}: required {level} job did not succeed ({status})")
    if is_draft:
        return GateDecision(True, False, "Draft FULL passed, not final merge authorization")
    return GateDecision(True, True, "All selected platform jobs succeeded on this run")


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--event", required=True)
    p.add_argument("--draft", required=True)
    p.add_argument("--impact-result", required=True)
    p.add_argument("--mode", required=True)
    p.add_argument("--expected-group", action="append", required=True)
    p.add_argument("--coverage", action="append", required=True,
                   help="group:skip|smoke|deep:job-result")
    p.add_argument("--summary", type=Path)
    p.add_argument("--expected-sha", required=True)
    p.add_argument("--actual-sha", required=True)
    args = p.parse_args(argv)

    # Invalid triples must fail closed instead of throwing or being ignored.
    triples = tuple(tuple(c.split(":")) for c in args.coverage)
    valid_shape = all(len(x) == 3 for x in triples)
    decision = (decide(args.event, args.draft, args.impact_result, args.mode,
                       triples, tuple(args.expected_group)) if valid_shape else
                GateDecision(False, False, "malformed platform coverage"))
    sha_ok = bool(SHA.fullmatch(args.expected_sha)) and args.expected_sha == args.actual_sha
    passed = decision.passed and sha_ok
    label = ("SCOPED ACCEPTANCE" if passed and decision.is_acceptance else
             "DRAFT ADVISORY" if passed else "FAILED")
    reason = decision.message if sha_ok else "checkout SHA does not match current event head"
    untested = [x[0] for x in triples if len(x) == 3 and x[1] == "skip"]
    print(f"{label}: {reason}; source={args.actual_sha}; expected={args.expected_sha}")
    if args.summary:
        with args.summary.open("a", encoding="utf-8") as out:
            out.write(f"## {label}\n\n{reason}\n\n")
            out.write(f"Head SHA: `{args.expected_sha}` (checkout: `{args.actual_sha}`)\n\n")
            out.write("| Platform job | Required depth | Actual result |\n| --- | --- | --- |\n")
            for group, level, status in triples:
                out.write(f"| {group} | {level} | {status} |\n")
            out.write("\nNot verified on this run: " +
                      (", ".join(untested) if untested else "none") +
                      ". A skipped Windows FULL is **not** Windows verified.\n\n")
            out.write("Owner manually reviews this scope and merges; there is no Branch Protection.\n")
    return 0 if passed else 1


if __name__ == "__main__":
    raise SystemExit(main())
