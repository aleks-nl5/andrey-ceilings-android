#!/usr/bin/env python3
"""Require current Android JUnit reports to prove the selected classes ran."""
import argparse
from collections import Counter
from pathlib import Path
import xml.etree.ElementTree as ET


def verify_reports(report_dir: Path, expected: set[str]) -> Counter[str]:
    if not expected or "" in expected:
        raise ValueError("Android smoke requires a nonempty class filter")

    passed = Counter()
    for path in sorted(report_dir.rglob("TEST-*.xml")):
        root = ET.parse(path).getroot()
        for case in root.iter("testcase"):
            classname = case.get("classname")
            name = case.get("name")
            if classname not in expected or not name:
                raise ValueError(f"Unexpected Android testcase: {classname}.{name}")
            if case.find("failure") is not None or case.find("error") is not None:
                raise ValueError(f"Failed Android testcase: {classname}.{name}")
            if case.find("skipped") is not None:
                raise ValueError(f"Skipped Android testcase: {classname}.{name}")
            passed[classname] += 1

    missing = expected - passed.keys()
    if missing:
        raise ValueError(f"No passing Android testcases for: {', '.join(sorted(missing))}")
    return passed


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--reports", type=Path, required=True)
    parser.add_argument("--classes", required=True, help="Comma-separated instrumented test classes")
    args = parser.parse_args()
    try:
        passed = verify_reports(args.reports, set(args.classes.split(",")))
    except (ValueError, OSError, ET.ParseError) as error:
        parser.exit(1, f"{error}\n")
    print("### Android focused instrumentation results")
    for classname, count in sorted(passed.items()):
        print(f"- `{classname}`: {count} passing testcase(s)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
