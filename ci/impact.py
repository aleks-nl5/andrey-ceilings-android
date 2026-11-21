#!/usr/bin/env python3
"""Fail-closed PR change impact advisory; never a merge authorization.

Use the merge base (BASE...HEAD), rather than a simple branch tip diff.
Unknown/critical changes require existing FULL workflows, not an optimistic
platform-only subset. This script never claims that FULL passed.
"""
from __future__ import annotations

import argparse
from dataclasses import dataclass
from pathlib import Path
import re
import subprocess
import sys

SHA = re.compile(r"^[0-9a-fA-F]{40}(?:[0-9a-fA-F]{24})?$")
TARGETS = ("android", "desktop", "ios")


@dataclass(frozen=True)
class Change:
    status: str
    path: str


@dataclass(frozen=True)
class Impact:
    mode: str
    targets: tuple[str, ...]
    reason: str
    paths: tuple[str, ...]


def parse_changes(data: bytes) -> list[Change]:
    """Parse git diff name-status -z --find-renames without tab/space ambiguity."""
    if not data:
        return []
    tokens = data.split(b"\0")
    if tokens.pop() != b"":
        raise ValueError("unterminated git name-status output")
    changes = []
    i = 0
    while i < len(tokens):
        status = tokens[i].decode("ascii")
        i += 1
        if not re.fullmatch(r"[AMDTU]|[RC][0-9]{1,3}", status):
            raise ValueError("unexpected git status")
        width = 2 if status[0] in "RC" else 1
        if i + width > len(tokens):
            raise ValueError("incomplete git name-status output")
        names = [token.decode("utf-8") for token in tokens[i:i + width]]
        i += width
        if any(not p or "\n" in p or "\r" in p for p in names):
            raise ValueError("invalid path in git diff")
        # Rename/copy must be FULL, never silently considered a safe modified file.
        changes.append(Change(status, names[-1]))
    return changes


def critical(path: str) -> bool:
    """Changes touching build, persistence, security, tests or shared UI are FULL."""
    lower = path.lower()
    return (path.startswith((".github/", "ci/", "gradle/"))
            or path in {"AGENTS.md", "gradle.properties", "settings.gradle.kts",
                        "build.gradle.kts", "gradlew", "gradlew.bat", ".gitignore"}
            or path.endswith((".gradle.kts", ".gradle", ".toml", ".properties", ".sql", ".sq"))
            or "/src/" in path and any(fragment in lower for fragment in (
                "/db/", "/database/", "/backup/", "sqlcipher", "migration", "keystore", "keychain",
                "encryption", "credential", "/test/", "/androidtest/", "/commontest/",
                "/jvmtest/", "/iostest/", "/androidinstrumentedtest/", "/testfixtures/"))
            or lower.endswith("/androidmanifest.xml"))


def target_for(path: str) -> str | None:
    if path == "README.md" or path.startswith("docs/") and Path(path).suffix.lower() in {".md", ".txt", ".png", ".jpg", ".svg"}:
        return "docs"
    if path == "iosApp/README.md":
        return "docs"
    if path.startswith(("app/src/main/", "app/src/androidMain/", "shared/src/androidMain/")):
        return "android"
    if path.startswith(("desktopApp/src/main/", "shared/src/jvmMain/")):
        return "desktop"
    if path.startswith(("shared/src/iosMain/",)):
        return "ios"
    # Any commonMain (especially theme, navigation, Canvas, pricing/PDF) can
    # affect Android/Desktop/iOS and needs FULL, not an underestimated subset.
    return None


def classify(changes: list[Change]) -> Impact:
    paths = tuple(c.path for c in changes)
    if not changes:
        return Impact("FULL", (), "empty or unverifiable change set", paths)
    targets: set[str] = set()
    for c in changes:
        if c.status not in ("A", "M"):
            return Impact("FULL", (), f"deleted/renamed/copied/type-changed path: {c.path}", paths)
        if critical(c.path):
            return Impact("FULL", (), f"critical/test/build/security change: {c.path}", paths)
        target = target_for(c.path)
        if target is None:
            return Impact("FULL", (), f"unknown or cross-platform path: {c.path}", paths)
        if target != "docs":
            targets.add(target)
    if not targets:
        return Impact("FAST", (), "documentation-only change; run classifier regression tests", paths)
    return Impact("TARGETED", tuple(t for t in TARGETS if t in targets),
                  "platform-specific source changes; union of targets", paths)


def impact_from_git(base: str, head: str) -> Impact:
    if not SHA.fullmatch(base) or not SHA.fullmatch(head):
        return Impact("FULL", (), "missing or malformed base/head SHA", ())
    try:
        output = subprocess.run(
            ["git", "diff", "--name-status", "-z", "--find-renames", f"{base}...{head}", "--"],
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True, timeout=60,
        )
        return classify(parse_changes(output.stdout))
    except (OSError, ValueError, subprocess.SubprocessError, UnicodeError) as error:
        return Impact("FULL", (), f"could not verify complete git diff ({type(error).__name__})", ())


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", default="")
    parser.add_argument("--head", default="")
    parser.add_argument("--github-output", type=Path)
    parser.add_argument("--summary", type=Path)
    args = parser.parse_args(argv)
    result = impact_from_git(args.base, args.head)
    fields = {
        "mode": result.mode,
        "android": str("android" in result.targets).lower(),
        "desktop": str("desktop" in result.targets).lower(),
        "ios": str("ios" in result.targets).lower(),
    }
    if args.github_output:
        with args.github_output.open("a", encoding="utf-8") as f:
            for key, value in fields.items():
                f.write(f"{key}={value}\n")
    print(f"Change impact: {result.mode}; targets={','.join(result.targets) or 'none'}; reason={result.reason}")
    if args.summary:
        lines = ["## Draft change impact - advisory only", "",
                 f"Commit: {args.head or 'unknown'}", f"Decision: **{result.mode}**",
                 f"Target groups: {', '.join(result.targets) or 'none'}",
                 f"Reason: {result.reason.replace(chr(96), '')}",
                 "", "This is **not a FULL merge gate**. Existing Android/Windows/iOS FULL checks remain mandatory.",
                 "", f"Changed paths: {len(result.paths)}", ""]
        lines += [f"- {p.replace(chr(96), '')}" for p in result.paths[:40]]
        if len(result.paths) > 40:
            lines.append(f"- ... and {len(result.paths) - 40} more")
        with args.summary.open("a", encoding="utf-8") as f:
            f.write("\n".join(lines) + "\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
