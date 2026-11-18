"""Sync the Android-safe Joplin Clipper Plus assets into this repository.

Usage: python tools/sync_clipper_android.py <upstream-checkout> [android-clip-assets]

Only the three clipping libraries and site-rules.json are written. All source
files and all rule tables are validated before any destination file is touched.
"""
from __future__ import annotations

import datetime as dt
import json
import os
from pathlib import Path
import re
import sys
import tempfile

TABLES = {
    "content": "SITE_CONTENT_SELECTORS",
    "cleanup": "SITE_CLEANUP_SELECTORS",
    "label": "SITE_LABEL_FROM_TITLE_SELECTORS",
    "iframe": "SITE_IFRAME_SELECTORS",
    "alias": "MOBILE_HOST_ALIAS",
}
LIBRARIES = ("Readability.js", "turndown.js", "turndown-plugin-gfm.js")
EXPECTED_KEYS = set(TABLES) | {"updated"}


def read(path: Path) -> bytes:
    return path.read_bytes()


def rules_from_content(content: bytes) -> dict:
    text = content.decode("utf-8").replace("\r\n", "\n")
    out = {}
    for key, name in TABLES.items():
        match = re.search(r"^const " + re.escape(name) + r" = (\{.*?^\});", text, re.S | re.M)
        if not match:
            raise ValueError(f"Missing rule table in content.js: {name}")
        out[key] = parse_table_literal(match.group(1))
    validate_rules({**out, "updated": "2000-01-01"})
    return out


def parse_table_literal(source: str):
    """Parse the JSON-like object/array/string subset used by the source tables.

    This deliberately rejects executable JavaScript rather than evaluating
    checked-out upstream text in the write-enabled workflow.
    """
    token_re = re.compile(r"\s+|//[^\n]*|/\*[\s\S]*?\*/|\"(?:\\.|[^\"\\])*\"|'(?:\\.|[^'\\])*'|[{}\[\]:,]")
    tokens = []
    position = 0
    while position < len(source):
        match = token_re.match(source, position)
        if not match:
            raise ValueError(f"Unsupported JavaScript syntax near: {source[position:position + 40]!r}")
        token = match.group(0)
        position = match.end()
        if token.isspace() or token.startswith("//") or token.startswith("/*"):
            continue
        tokens.append(token)
    index = 0

    def parse_value():
        nonlocal index
        if index >= len(tokens):
            raise ValueError("Unexpected end of site rule table")
        token = tokens[index]
        index += 1
        if token.startswith(('"', "'")):
            return decode_js_string(token)
        if token == "{":
            result = {}
            while index < len(tokens) and tokens[index] != "}":
                key = parse_value()
                if not isinstance(key, str) or index >= len(tokens) or tokens[index] != ":":
                    raise ValueError("Site rule object keys must be quoted strings")
                index += 1
                if key in result:
                    raise ValueError(f"Duplicate site rule key: {key}")
                result[key] = parse_value()
                if index < len(tokens) and tokens[index] == ",":
                    index += 1
                elif index >= len(tokens) or tokens[index] != "}":
                    raise ValueError("Expected comma in site rule object")
            if index >= len(tokens):
                raise ValueError("Unclosed site rule object")
            index += 1
            return result
        if token == "[":
            result = []
            while index < len(tokens) and tokens[index] != "]":
                result.append(parse_value())
                if index < len(tokens) and tokens[index] == ",":
                    index += 1
                elif index >= len(tokens) or tokens[index] != "]":
                    raise ValueError("Expected comma in site rule array")
            if index >= len(tokens):
                raise ValueError("Unclosed site rule array")
            index += 1
            return result
        raise ValueError(f"Unsupported site rule value: {token}")

    value = parse_value()
    if index != len(tokens) or not isinstance(value, dict):
        raise ValueError("Site rule table must contain exactly one object")
    return value


def decode_js_string(token: str) -> str:
    if token.startswith('"'):
        return json.loads(token)
    body = token[1:-1]
    out = []
    i = 0
    escapes = {"n": "\n", "r": "\r", "t": "\t", "b": "\b", "f": "\f", "v": "\v", "0": "\0"}
    while i < len(body):
        if body[i] != "\\":
            out.append(body[i])
            i += 1
            continue
        i += 1
        if i >= len(body):
            raise ValueError("Invalid escape in site rule string")
        char = body[i]
        if char == "u" and i + 4 < len(body):
            out.append(chr(int(body[i + 1:i + 5], 16)))
            i += 5
        elif char == "x" and i + 2 < len(body):
            out.append(chr(int(body[i + 1:i + 3], 16)))
            i += 3
        else:
            out.append(escapes.get(char, char))
            i += 1
    return "".join(out)


def validate_rules(data: dict) -> None:
    if set(data) != EXPECTED_KEYS:
        raise ValueError(f"Unexpected rule JSON keys: {sorted(data)}")
    try:
        dt.date.fromisoformat(data["updated"])
    except (TypeError, ValueError):
        raise ValueError("updated must be a YYYY-MM-DD date")
    for key in TABLES:
        if not isinstance(data[key], dict):
            raise ValueError(f"Rule table {key} must be an object")
        expected_type = list if key in ("cleanup", "label") else str
        if not all(isinstance(host, str) and isinstance(value, expected_type)
                   for host, value in data[key].items()):
            raise ValueError(f"Rule table {key} contains an invalid entry")
        if expected_type is list and not all(
            all(isinstance(item, str) for item in value) for value in data[key].values()
        ):
            raise ValueError(f"Rule table {key} entries must contain only strings")


def normalized(data: bytes) -> bytes:
    return data.replace(b"\r\n", b"\n")


def sync(source: Path, destination: Path) -> list[str]:
    content_path = source / "content.js"
    if not content_path.is_file():
        raise FileNotFoundError(f"Upstream file not found: {content_path}")
    library_data = {}
    for name in LIBRARIES:
        path = source / "lib" / name
        if not path.is_file():
            raise FileNotFoundError(f"Upstream library not found: {path}")
        library_data[name] = read(path)

    generated_tables = rules_from_content(read(content_path))
    rules_path = destination / "site-rules.json"
    old_rules = None
    if rules_path.is_file():
        old_rules = json.loads(read(rules_path).decode("utf-8"))
        validate_rules(old_rules)
    # Keep the existing timestamp whenever selector content is unchanged. This
    # makes scheduled no-op runs byte-for-byte stable.
    if old_rules and all(old_rules[key] == generated_tables[key] for key in TABLES):
        updated = old_rules["updated"]
    else:
        updated = dt.datetime.now(dt.timezone.utc).date().isoformat()
    rule_bytes = (json.dumps({"updated": updated, **generated_tables}, ensure_ascii=False,
                             indent=1, sort_keys=True) + "\n").encode("utf-8")
    validate_rules(json.loads(rule_bytes.decode("utf-8")))

    desired = {**library_data, "site-rules.json": rule_bytes}
    changed = []
    for name, data in desired.items():
        path = destination / name
        if not path.is_file() or normalized(read(path)) != normalized(data):
            changed.append(name)

    # Stage every generated file only after all generation and validation has
    # succeeded, then replace the allowlisted assets.
    destination.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="clipper-android-", dir=destination) as temp_dir:
        staged = Path(temp_dir)
        for name in changed:
            (staged / name).write_bytes(desired[name])
        for name in changed:
            os.replace(staged / name, destination / name)
    return changed


def main(argv: list[str]) -> int:
    if len(argv) not in (1, 2):
        print(__doc__.strip(), file=sys.stderr)
        return 2
    source = Path(argv[0]).resolve()
    destination = Path(argv[1]).resolve() if len(argv) == 2 else Path(__file__).resolve().parents[1] / "app/src/main/assets/clip"
    try:
        changed = sync(source, destination)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        print(f"Android clipper sync failed: {exc}", file=sys.stderr)
        return 1
    if changed:
        print("Updated Android assets: " + ", ".join(changed))
    else:
        print("Android clipper assets are already current.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
