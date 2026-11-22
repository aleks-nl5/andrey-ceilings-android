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
    depths: tuple[str, str, str] = ("skip", "skip", "skip")


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


def _full(reason: str, paths: tuple[str, ...]) -> Impact:
    """Global fail-closed fallback (CI/config/unknown/common business contracts)."""
    return Impact("FULL", TARGETS, reason, paths, ("deep", "deep", "deep"))


def _group(path: str) -> tuple[tuple[str, ...], str] | None:
    """Explicit source-set to consumer map. Unknown paths are NOT cheap.

    Depth is per affected platform: skip, smoke, deep. A Windows-only
    SQLCipher/JDBC change must not force Android/iOS instrumentation.
    """
    lower = path.lower()
    # Documentation is safe only in known documentation roots.
    if (path == "README.md"
            or path.startswith("docs/") and Path(path).suffix.lower()
               in {".md", ".txt", ".png", ".jpg", ".jpeg", ".svg"}
            or path == "iosApp/README.md"):
        return ((), "skip")
    # Build, dependency and CI policy changes have unbounded consumers.
    if (path.startswith((".github/", "ci/", "gradle/"))
            or path in {"AGENTS.md", "gradle.properties", "settings.gradle.kts",
                        "build.gradle.kts", "gradlew", "gradlew.bat", ".gitignore"}
            or lower.endswith((".gradle.kts", ".gradle", ".toml", ".properties"))):
        return None

    # Native Swift/Xcode app configuration is not validated by the current
    # Gradle iOS framework smoke; never label it as a scoped iOS verification.
    if path.startswith("iosApp/"):
        return None
    if (path.startswith(("app/src/", "desktopApp/src/", "shared/src/"))
            and Path(path).suffix.lower() not in {
                ".kt", ".java", ".xml", ".sql", ".sq", ".sqm",
                ".png", ".jpg", ".jpeg", ".webp", ".svg", ".json",
                ".ttf", ".otf"
            }):
        return None

    is_test = any(part in lower for part in (
        "/androidtest/", "/androidinstrumentedtest/", "/commontest/",
        "/jvmtest/", "/iostest/", "/testfixtures/", "/src/test/",
        "/src/androidunittest/"))
    is_database = (lower.endswith((".sql", ".sq", ".sqm"))
                   or "/sqldelight/" in lower
                   or any(fragment in lower for fragment in (
                       "/db/", "/database/", "/backup/", "sqlcipher", "migration",
                       "keystore", "keychain", "encryption", "credential",
                       "/persistence/", "/storage/", "/security/")))
    # Shared UI exceptions are carefully scoped to actual Compose presentation
    # code. Changes to data/geometry/finance contracts do NOT pass this rule.
    shared_ui = (
        "/theme/" in lower or "/navigation/" in lower or "/ui/" in lower
        or lower.endswith((
            "/measurementeditscreen.kt", "/measurementdrawing.kt",
            "/measurementdetailsscreen.kt", "/measurementelementsui.kt",
            "/measurementpricingui.kt", "/pricecatalogtheme.kt"))
    )

    if path.startswith(("app/src/", "shared/src/androidMain/",
                        "shared/src/androidTest/", "shared/src/androidInstrumentedTest/",
                        "shared/src/androidUnitTest/")):
        return (("android",), "deep" if is_database or is_test
                or lower.endswith("/androidmanifest.xml") else "smoke")
    if path.startswith(("desktopApp/src/", "shared/src/jvmMain/",
                        "shared/src/jvmTest/")):
        return (("desktop",), "deep" if is_database or is_test else "smoke")
    if path.startswith(("shared/src/iosMain/", "shared/src/iosTest/",
                        "iosApp/")):
        return (("ios",), "deep" if is_database or is_test else "smoke")
    if path.startswith(("shared/src/commonMain/", "shared/src/commonTest/")):
        if is_database or is_test:
            return (TARGETS, "deep")
        if shared_ui:
            return (TARGETS, "smoke")
        # Common business/geometry/finance code is coupled to native DB and
        # platform persistence. No optimistic category derived from its name.
        return (TARGETS, "deep")
    return None


def classify(changes: list[Change]) -> Impact:
    paths = tuple(c.path for c in changes)
    if not changes:
        return _full("empty or unverifiable change set", paths)
    depths = {target: "skip" for target in TARGETS}
    rank = {"skip": 0, "smoke": 1, "deep": 2}
    shared_deep = False
    for c in changes:
        if c.status not in ("A", "M"):
            return _full(f"deleted/renamed/copied/type-changed path: {c.path}", paths)
        group = _group(c.path)
        if group is None:
            return _full(f"CI/build or unknown path: {c.path}", paths)
        targets, depth = group
        for target in targets:
            if rank[depth] > rank[depths[target]]:
                depths[target] = depth
        if targets == TARGETS and depth == "deep":
            shared_deep = True
    selected = tuple(t for t in TARGETS if depths[t] != "skip")
    levels = tuple(depths[t] for t in TARGETS)
    if not selected:
        return Impact("FAST", (), "documentation-only; classifier tests", paths, levels)
    if shared_deep:
        return Impact("FULL", TARGETS, "shared business/database or test contract; all dependent suites", paths,
                      ("deep", "deep", "deep"))
    return Impact("TARGETED", selected, "per-platform source-set dependency union; scoped depth", paths, levels)


def impact_from_git(base: str, head: str) -> Impact:
    if not SHA.fullmatch(base) or not SHA.fullmatch(head):
        return _full("missing or malformed base/head SHA", ())
    try:
        output = subprocess.run(
            ["git", "diff", "--name-status", "-z", "--find-renames", f"{base}...{head}", "--"],
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True, timeout=60,
        )
        return classify(parse_changes(output.stdout))
    except (OSError, ValueError, subprocess.SubprocessError, UnicodeError) as error:
        return _full(f"could not verify complete git diff ({type(error).__name__})", ())


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
        **{f"{target}_depth": depth for target, depth in zip(TARGETS, result.depths)},
        "apple_depth": (result.depths[2] if result.depths[2] != "skip"
                        else "smoke" if result.depths[1] != "skip" else "skip"),
    }
    if args.github_output:
        with args.github_output.open("a", encoding="utf-8") as f:
            for key, value in fields.items():
                f.write(f"{key}={value}\n")
    print(f"Change impact: {result.mode}; targets={','.join(result.targets) or 'none'}; reason={result.reason}")
    if args.summary:
        lines = ["## Change impact - platform/depth classification (not CI proof)", "",
                 f"Commit: {args.head or 'unknown'}", f"Decision: **{result.mode}**",
                 f"Target groups: {', '.join(result.targets) or 'none'}",
                 f"Coverage: {', '.join(f'{t}={d}' for t, d in zip(TARGETS, result.depths))}",
                 f"Reason: {result.reason.replace(chr(96), '')}",
                 "", "This is a classification, not proof of execution. Skipped platforms are NOT verified; scoped acceptance depends on real matching-SHA job results.",
                 "", f"Changed paths: {len(result.paths)}", ""]
        lines += [f"- {p.replace(chr(96), '')}" for p in result.paths[:40]]
        if len(result.paths) > 40:
            lines.append(f"- ... and {len(result.paths) - 40} more")
        with args.summary.open("a", encoding="utf-8") as f:
            f.write("\n".join(lines) + "\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
