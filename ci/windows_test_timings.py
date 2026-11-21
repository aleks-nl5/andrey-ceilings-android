#!/usr/bin/env python3
"""Summarize existing Gradle JUnit XML; no test execution or data mutation.

The summary deliberately distinguishes summed testcase time from wall-clock job time:
Gradle workers may execute tests in parallel and different Gradle invocations
may rerun a class. Test failures still belong to Gradle's own exit status.
"""

from __future__ import annotations

import argparse
import math
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

DEFAULT_ROOTS = (
    Path("shared/build/test-results/jvmTest"),
    Path("desktopApp/build/test-results/test"),
)


def read_results(roots: list[Path]):
    rows = []
    xml_count = 0
    for root in roots:
        for path in sorted(root.glob("TEST-*.xml")):
            xml_count += 1
            tree = ET.parse(path)
            suite = tree.getroot()
            if suite.tag not in ("testsuite", "testsuites"):
                raise ValueError(f"Unexpected JUnit root in {path}: {suite.tag}")
            # Both a <testsuite> root and nested suites are allowed by JUnit.
            for test in suite.iter("testcase"):
                raw = test.get("time", "0")
                duration = float(raw)
                if not math.isfinite(duration) or duration < 0:
                    raise ValueError(f"Invalid test duration in {path}: {raw}")
                rows.append(
                    (test.get("classname") or suite.get("name") or "unknown",
                     test.get("name") or "unnamed",
                     duration,
                     bool(test.find("failure") is not None or test.find("error") is not None),
                     test.find("skipped") is not None,
                     "shared" if path.parts[0] == "shared" or "shared" in path.parts else "desktop")
                )
    return rows, xml_count


def report(rows, count: int, limit: int) -> str:
    if not count:
        return "## Windows JVM test timing summary\n\nNo JUnit XML was produced (build may have failed before tests).\n"
    total = sum(x[2] for x in rows)
    failures = sum(x[3] for x in rows)
    skipped = sum(x[4] for x in rows)
    grouped = {}
    for klass, _name, time, _failed, _skipped, platform in rows:
        key = (platform, klass)
        duration, n = grouped.get(key, (0.0, 0))
        grouped[key] = (duration + time, n + 1)
    def esc(s):
        return str(s).replace("|", "\\|").replace("\n", " ").replace("\r", " ")
    lines = ["## Windows JVM test timing summary", "",
             f"XML suites/files: **{count}**; testcases: **{len(rows)}**; "
             f"failures: **{failures}**; skipped: **{skipped}**.",
             f"Sum of individual testcase durations: **{total / 60:.1f} min**. "
             "This is *not* job wall time; parallel tasks may overlap.", "",
             "### Slowest test classes", "", "| Module | Test class | Cases | Summed time |",
             "| --- | --- | ---: | ---: |"]
    for (platform, klass), (sec, n) in sorted(grouped.items(), key=lambda kv: -kv[1][0])[:limit]:
        lines.append(f"| {esc(platform)} | {esc(klass)} | {n} | {sec:.1f} s |")
    lines += ["", "### Slowest individual tests", "",
              "| Module | Test | Duration |", "| --- | --- | ---: |"]
    for klass, name, sec, _failed, _skipped, platform in sorted(rows, key=lambda x: -x[2])[:limit]:
        lines.append(f"| {esc(platform)} | {esc(klass)}.{esc(name)} | {sec:.1f} s |")
    lines += ["", "*Timings are from the latest JUnit XML files only. "
              "A cancelled job can contain a partial subset; do not infer success from this summary.*", ""]
    return "\n".join(lines)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, action="append", help="JUnit directory (repeatable)")
    parser.add_argument("--summary", type=Path, help="Append Markdown to GITHUB_STEP_SUMMARY")
    parser.add_argument("--top", type=int, default=15)
    args = parser.parse_args(argv)
    if args.top < 1 or args.top > 100:
        parser.error("--top must be between 1 and 100")
    try:
        rows, count = read_results(args.root or list(DEFAULT_ROOTS))
        result = report(rows, count, args.top)
        print(result)
        if args.summary is not None:
            with args.summary.open("a", encoding="utf-8") as f:
                f.write(result + "\n")
        return 0
    except (OSError, ET.ParseError, ValueError) as exc:
        print(f"CI diagnostics: unable to summarize JUnit XML: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
