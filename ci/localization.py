#!/usr/bin/env python3
"""Inventory source literals and reject new hardcoded presentation strings."""

import argparse
from collections import Counter, defaultdict
from dataclasses import dataclass
import json
from pathlib import Path
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOTS = ("shared/src", "app/src", "desktopApp/src", "iosApp")
BASELINE = "ci/localization-baseline.json"
SOURCE_SUFFIXES = {".kt", ".java", ".swift", ".xml", ".strings", ".sql", ".sq", ".sqm"}
GUARDED = {"ui", "accessibility", "notification", "pdf"}
CYRILLIC = re.compile(r"[\u0400-\u052f]")
TOKEN = re.compile(r'//[^\n]*|/\*|"""|"|\'(?:\\.|[^\'\\])\'|[\w]+|[^\s]')
TEMPLATE = re.compile(r"(?<!\\)\$(?:\{[^}]*\}|[A-Za-z_]\w*)")
SQL = re.compile(
    r"\s*(?:SELECT\b.+\bFROM\b|INSERT\s+INTO\b|UPDATE\s+\w+\s+SET\b|DELETE\s+FROM\b|"
    r"(?:CREATE|ALTER|DROP)\s+(?:TABLE|INDEX|TRIGGER|VIEW)\b|PRAGMA\s+\w+\s*[=(]|"
    r"WITH\s+\w+\s+AS\b|ATTACH\s+DATABASE\b)", re.I | re.S)
TECH_VALUE = re.compile(r"(?:@?[a-z]+://\S+|[a-z]+/[+\w.-]+|[\w]+(?:[.][a-z0-9_]+){2,})", re.I)
LOG_CALL = re.compile(r"(?:Log|PerformanceDiagnosticsLog|logger)\.\w+")
ACCESSIBILITY = {"contentDescription", "stateDescription", "onClickLabel",
                 "onLongClickLabel", "paneTitle", "accessibilityLabel", "accessibilityHint"}
NOTIFICATION = {"setContentTitle", "setContentText", "setSubText", "setTicker", "NotificationChannel"}
VISIBLE_CALLS = {"Text", "BasicText", "AnnotatedString", "TextView", "setText", "makeText",
                 "Label", "setTitle", "setMessage", "setPositiveButton", "setNegativeButton",
                 "AppDestination", "Snackbar", "showSnackbar", "Window", "appendLine"}
VISIBLE_NAMES = {"text", "title", "subtitle", "caption", "label", "message", "hint", "errorMessage",
                 "placeholder", "supportingText", "emptyMessage", "tooltip", "quickCreateLabel"}
# Only follow positional bindings where the display argument is known.
DISPLAY_ARGUMENTS = {
    **{name: (0, "ui") for name in (
        "Text", "BasicText", "TextView", "Label", "setText", "setTitle", "setMessage",
        "setPositiveButton", "setNegativeButton", "showSnackbar",
    )},
    **{name: (0, "notification") for name in NOTIFICATION - {"NotificationChannel"}},
    "Icon": (1, "accessibility"),
    "makeText": (1, "ui"),
    "NotificationChannel": (1, "notification"),
}
TECH_CALLS = {"testTag", "Regex", "require", "check", "error", "println", "print",
              "getStringExtra", "getSharedPreferences", "setProperty", "stringResource",
              "pluralStringResource", "getString", "getPluralString", "execute",
              "executeQuery", "query", "execSQL", "rawQuery", "stringForKey", "removeObjectForKey"}
TECH_NAMES = {"id", "key", "tag", "testTag", "wireId", "route", "mimeType", "pattern"}
XML_LABELS = {"text", "title", "summary", "hint", "label", "contentDescription", "accessibilityPaneTitle"}
FORMATTING_FILES = {"DashboardDateTime.kt", "MeasurementFormatting.kt", "DecimalTextPolicy.kt"}
PDF_FILES = {"EstimateDocumentPages.kt", "ConsolidatedEstimateDocument.kt", "FrozenEstimateDocument.kt"}
# Exact files whose source literals are persisted catalog defaults or historical seed migration data.
CATALOG_DATA = {
    "shared/src/commonMain/kotlin/com/andreyceilings/shared/prices/StarterPriceCatalog.kt",
    "shared/src/commonMain/kotlin/com/andreyceilings/shared/prices/PriceNameMigration.kt",
}
IDENTIFIER = re.compile(r"[\w]+")
ESCAPE = re.compile(r"\\(?:u([0-9a-fA-F]{4})|(.))", re.S)


@dataclass(frozen=True)
class Entry:
    line: int
    category: str
    value: str

    @property
    def key(self):
        return self.category, self.value


def git(*args, allow_missing=False):
    result = subprocess.run(["git", *args], capture_output=True, check=not allow_missing)
    return result.stdout if not result.returncode else None


def sources(paths):
    return sorted(p for p in paths
                  if p.startswith(tuple(f"{root}/" for root in ROOTS))
                  and Path(p).suffix in SOURCE_SUFFIXES)


def decode_string(value):
    escapes = {"n": "\n", "r": "\r", "t": "\t", "b": "\b"}
    def decode(match):
        char = chr(int(match[1], 16)) if match[1] else escapes.get(match[2], match[2])
        return r"\$" if char == "$" else char

    return ESCAPE.sub(decode, value)


def kotlin_tokens(source):
    """Yield significant code tokens and strings, skipping nested comments."""
    offset = 0

    def read_string(start, delimiter):
        end = start + len(delimiter)
        while end < len(source) and not source.startswith(delimiter, end):
            if delimiter == '"' and source[end] == "\\":
                end += 2
            elif source.startswith("${", end):
                # Nested quotes/braces inside a template belong to the expression.
                depth = 1
                end += 2
                while end < len(source) and depth:
                    if source.startswith('"""', end):
                        end = read_string(end, '"""')
                        continue
                    if source[end] == '"':
                        end = read_string(end, '"')
                        continue
                    if source[end] == "{":
                        depth += 1
                    elif source[end] == "}":
                        depth -= 1
                    end += 1
            else:
                end += 1
        return min(end + len(delimiter), len(source))

    while match := TOKEN.search(source, offset):
        token = match[0]
        offset = match.end()
        if token.startswith("//"):
            continue
        if token == "/*":
            depth = 1
            while depth and (marker := re.search(r"/\*|\*/", source[offset:])):
                depth += 1 if marker[0] == "/*" else -1
                offset += marker.end()
            if depth:
                break
        elif token in {'"', '"""'}:
            offset = read_string(match.start(), token)
            value = source[match.end():offset - len(token)]
            yield None, value if token == '"""' else decode_string(value), match.start()
        else:
            yield token, None, match.start()


def bound_presentation_literals(source, tokens):
    """Map simple Kotlin string initializers used directly as display arguments."""
    scopes = [{}]
    frames = []
    closing = {"(": ")", "[": "]", "{": "}"}
    uses = {}
    words = [token for token, _, _ in tokens]

    for index, (token, _, offset) in enumerate(tokens):
        previous = words[index - 1] if index else None
        following = words[index + 1:index + 3]
        if token in {"val", "var"} and following and IDENTIFIER.fullmatch(following[0] or ""):
            name = following[0]
            initializer = index + 3
            if words[index + 2:index + 5] == [":", "String", "="]:
                initializer = index + 5
            # Record shadowing declarations even when their value is not a literal.
            scopes[-1][name] = None
            if token == "val" and words[initializer - 1:initializer + 1] == ["=", None]:
                literal_start = tokens[initializer][2]
                next_index = initializer + 1
                if (next_index == len(tokens) or words[next_index] in {";", "}"}
                        or (words[next_index] not in {".", "?", "+", "[", "("}
                            and "\n" in source[literal_start:tokens[next_index][2]])):
                    scopes[-1][name] = literal_start

        if token in closing:
            call = previous if IDENTIFIER.fullmatch(previous or "") else None
            if call and index >= 3 and words[index - 2] == ".":
                call = f"{words[index - 3]}.{call}"
            frames.append((token, call, 0))
            if token == "{":
                scopes.append({})
        elif frames and token == closing[frames[-1][0]]:
            frames.pop()
            if token == "}":
                scopes.pop()
        elif token == "," and frames and frames[-1][0] == "(":
            delimiter, call, argument = frames[-1]
            frames[-1] = (delimiter, call, argument + 1)
        elif token and IDENTIFIER.fullmatch(token) and frames and frames[-1][0] == "(":
            _, qualified_call, argument = frames[-1]
            if not qualified_call or LOG_CALL.fullmatch(qualified_call):
                continue
            call = qualified_call.rsplit(".", 1)[-1]
            if call in TECH_CALLS or not following or following[0] not in {",", ")"}:
                continue
            category = None
            if previous == "=" and index >= 3 and words[index - 3] in {"(", ","}:
                parameter = words[index - 2]
                if parameter in ACCESSIBILITY:
                    category = "accessibility"
                elif parameter in VISIBLE_NAMES and call in NOTIFICATION:
                    category = "notification"
                elif parameter in VISIBLE_NAMES and (call in VISIBLE_CALLS or call in DISPLAY_ARGUMENTS):
                    category = "ui"
            elif previous in {"(", ","}:
                position, display_category = DISPLAY_ARGUMENTS.get(call, (-1, None))
                if argument == position:
                    category = display_category
            if category:
                binding = next((scope[token] for scope in reversed(scopes) if token in scope), None)
                if binding is not None:
                    uses.setdefault(binding, category)
    return uses


def literal_category(path, value, call, parents, assignment, bound_category=None):
    file = Path(path).name
    if any("test" in component.lower() for component in path.split("/")[2:-1]):
        return "test"
    if path in CATALOG_DATA:
        return "catalog_data"
    if "/sqldelight/" in path or SQL.match(value):
        return "technical"
    if call.rsplit(".", 1)[-1] in TECH_CALLS or assignment in TECH_NAMES:
        return "technical"
    if LOG_CALL.fullmatch(call):
        return "log"
    visible = TEMPLATE.sub("", value) if file.endswith(".kt") else value
    has_letters = any(c.isalpha() for c in visible)
    if assignment in ACCESSIBILITY or call.rsplit(".", 1)[-1] in ACCESSIBILITY:
        return "accessibility" if has_letters else "technical"
    if any(parent.rsplit(".", 1)[-1] in NOTIFICATION for parent in parents):
        return "notification" if has_letters else "technical"
    if not has_letters or TECH_VALUE.fullmatch(visible.strip()):
        return "technical"
    if file in FORMATTING_FILES:
        return "formatting"
    if file in PDF_FILES or file.endswith("Pdf.kt"):
        return "pdf" if CYRILLIC.search(visible) or " " in visible else "technical"
    if (call.rsplit(".", 1)[-1] in VISIBLE_CALLS or assignment in VISIBLE_NAMES
            or any(parent.rsplit(".", 1)[-1] in VISIBLE_CALLS for parent in parents)):
        return "ui"
    if bound_category:
        return bound_category
    presentation_file = ("/ui/" in path or file.endswith(("Screen.kt", "Controls.kt", "Chrome.kt", "Ui.kt"))
                         or file in {"AppRoot.kt", "MainActivity.kt", "Main.kt", "AppNavigationState.kt"})
    if presentation_file and CYRILLIC.search(visible):
        return "ui"
    if "Notification" in file and CYRILLIC.search(visible):
        return "notification"
    return "domain" if CYRILLIC.search(visible) else "technical"


def code_entries(path, source, follow_bindings=False):
    tokens = kotlin_tokens(source)
    bindings = {}
    if follow_bindings and path.endswith(".kt"):
        tokens = list(tokens)
        bindings = bound_presentation_literals(source, tokens)
    # Keep the surrounding call and assignment across whitespace and comments.
    parents = []
    previous = []
    assignment = None
    for token, value, offset in tokens:
        if token is None:
            if value.strip():
                call = parents[-1] if parents else ""
                category = literal_category(path, value, call, parents, assignment, bindings.get(offset))
                yield Entry(source.count("\n", 0, offset) + 1, category, value)
        elif token == "(":
            name = previous[-1] if previous and IDENTIFIER.fullmatch(previous[-1]) else ""
            if name and len(previous) >= 3 and previous[-2] == ".":
                name = f"{previous[-3]}.{name}"
            parents.append(name)
        elif token == ")" and parents:
            parents.pop()
        if token == "=":
            assignment = previous[-1] if previous else None
        elif token in {None, ",", ")", "{", "}", ";", "("}:
            assignment = None
        previous.append(token or "")
        previous = previous[-3:]


def scan(path, source, follow_bindings=False):
    if Path(path).suffix == ".xml":
        for element in ET.fromstring(source).iter():
            tag = element.tag.rsplit("}", 1)[-1]
            if tag in {"string", "plurals", "string-array"}:
                value = "".join(element.itertext()).strip()
                if value:
                    yield Entry(0, "resource", f"{element.attrib.get('name', '')}={value}")
            for qualified, value in element.attrib.items():
                attribute = qualified.rsplit("}", 1)[-1]
                if attribute in XML_LABELS and value and not value.startswith(("@", "?", "${", "@{")):
                    category = "accessibility" if attribute.startswith(("accessibility", "contentDescription")) else "ui"
                    yield Entry(0, category, value)
    elif Path(path).suffix == ".strings":
        for entry in code_entries(path, source):
            yield Entry(entry.line, "resource", entry.value)
    elif Path(path).suffix in {".sql", ".sq", ".sqm"}:
        for match in re.finditer(r"'((?:''|[^'])+)'", re.sub(r"--[^\n]*|/\*.*?\*/", "", source, flags=re.S)):
            yield Entry(source.count("\n", 0, match.start()) + 1, "technical", match[1].replace("''", "'"))
    else:
        yield from code_entries(path, source, follow_bindings)


def ref_text(ref, path):
    return git("show", f"{ref}:{path}").decode("utf-8-sig")


def inventory(ref):
    files = git("ls-tree", "-rz", "--name-only", ref).decode("utf-8").rstrip("\0").split("\0")
    return {
        path: [[*key, count] for key, count in sorted(
            Counter((entry.category, entry.value if entry.category in GUARDED | {"resource"} else None)
                    for entry in scan(path, ref_text(ref, path))).items())]
        for path in sources(files)
    }


def changes(base, head):
    fields = git("diff", "--name-status", "-z", "--find-renames", base, head, "--").decode("utf-8")
    records = iter(fields.rstrip("\0").split("\0") if fields else ())
    for status in records:
        before = next(records)
        after = next(records) if status.startswith(("R", "C")) else before
        if not status.startswith("D") and sources([after]):
            yield before if status.startswith(("M", "R", "T")) else None, after


def additions(entries, allowed):
    used = Counter()
    for entry in entries:
        if entry.category in GUARDED:
            used[entry.key] += 1
            if used[entry.key] > allowed[entry.key]:
                yield entry


def check(base, head):
    merge_base = git("merge-base", base, head).decode("ascii").strip()
    trusted = git("show", f"{merge_base}:{BASELINE}", allow_missing=True)
    if trusted is None:
        # Bootstrap the checked-in inventory from the actual base, never the PR head.
        baseline = inventory(merge_base)
        if json.loads(ref_text(head, BASELINE)) != baseline:
            raise ValueError("Bootstrap inventory differs from the merge base")
    else:
        baseline = json.loads(trusted)
        if json.loads(ref_text(head, BASELINE)) != baseline:
            raise ValueError("Historical localization inventory must remain fixed")
    failures = []
    for before, after in changes(merge_base, head):
        allowed = Counter()
        if before and sources([before]):
            # Compare both revisions with the current guard. A newly recognized
            # historical use stays allowed while the inventory remains reproducible.
            allowed.update(entry.key for entry in scan(
                before, ref_text(merge_base, before), follow_bindings=True
            ))
        for entry in additions(scan(after, ref_text(head, after), follow_bindings=True), allowed):
            failures.append(f"{after}:{entry.line or 1}: {entry.category}: {entry.value!r}")
    return failures


def summary(data):
    counts = defaultdict(Counter)
    for path, entries in data.items():
        surface = "/".join(path.split("/")[:3]) if path.startswith("shared/src/") else path.split("/")[0]
        for category, _, count in entries:
            counts[surface][category] += count
    return {surface: dict(sorted(counts[surface].items())) for surface in sorted(counts)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    report = commands.add_parser("inventory")
    report.add_argument("--ref", required=True)
    report.add_argument("--out", type=Path, required=True)
    guard = commands.add_parser("check")
    guard.add_argument("--base", required=True)
    guard.add_argument("--head", required=True)
    args = parser.parse_args()
    if args.command == "inventory":
        data = inventory(args.ref)
        # One line per path keeps the static baseline compact and diffable.
        rows = [f"  {json.dumps(path)}: {json.dumps(entries, ensure_ascii=False)}" for path, entries in data.items()]
        args.out.write_text("{\n" + ",\n".join(rows) + "\n}\n", encoding="utf-8")
        print(json.dumps(summary(data), indent=2))
        return 0
    failures = check(args.base, args.head)
    for failure in failures:
        print(failure, file=sys.stderr)
    if not failures:
        print("No new hardcoded presentation strings found.")
    return int(bool(failures))


if __name__ == "__main__":
    sys.exit(main())
