#!/usr/bin/env python3
"""Audit Android resource translations using only the Python standard library."""

import argparse
from collections import defaultdict
from dataclasses import dataclass, field
from pathlib import Path
import re
import sys
from xml.parsers import expat


ROOT = Path(__file__).resolve().parents[2]
ANDROID_NAME = "http://schemas.android.com/apk/res/android|name"
PUBLISHED = frozenset(("en", "zh-CN", "ja"))
TEXT_TYPES = frozenset(("string", "string-array", "plurals"))
FORMAT = re.compile(
    r"%(?:(?P<index>[0-9]+)\$)?(?P<flags>[-#+ 0,(<]*)"
    r"(?P<width>[0-9]+)?(?:\.(?P<precision>[0-9]+))?"
    r"(?P<conversion>[bBhHsScCdoxXeEfgGaA%n]|[tT][HIklMSLNpzZsQBbhAaCYyjmdeRTrDFc])"
)
REFERENCE = re.compile(r"@string/([A-Za-z0-9_]+)\Z")


@dataclass
class Node:
    tag: str
    attrs: dict
    path: Path
    line: int
    children: list = field(default_factory=list)
    text: str = ""

    @property
    def location(self):
        return f"{self.path}:{self.line}"


@dataclass
class Finding:
    level: str
    location: str
    message: str

    def __str__(self):
        return f"{self.location}: {self.level}: {self.message}"


def parse_xml(path):
    """Retain source lines and inline markup text without external entity loading."""
    parser = expat.ParserCreate(namespace_separator="|")
    stack = []
    roots = []

    def start(tag, attrs):
        node = Node(tag, attrs, path, parser.CurrentLineNumber)
        (stack[-1].children if stack else roots).append(node)
        stack.append(node)

    def text(value):
        # Ancestors need the full rendered text, including nested <xliff:g>/<b>.
        for node in stack:
            node.text += value

    parser.StartElementHandler = start
    parser.EndElementHandler = lambda _: stack.pop()
    parser.CharacterDataHandler = text
    with path.open("rb") as source:
        parser.ParseFile(source)
    return roots[0]


def locale_configuration(directory):
    """Return BCP-47 locale plus remaining qualifiers (night, vNN, etc.)."""
    parts = directory.split("-")[1:]
    for index, part in enumerate(parts):
        if part.startswith("b+"):
            return part[2:].replace("+", "-"), tuple(parts[:index] + parts[index + 1:])
        if re.fullmatch(r"[a-z]{2,3}", part) and part != "car":
            tag = part
            end = index + 1
            if end < len(parts) and re.fullmatch(r"r(?:[A-Z]{2}|[0-9]{3})", parts[end]):
                tag += "-" + parts[end][1:]
                end += 1
            return tag, tuple(parts[:index] + parts[end:])
    return None, tuple(parts)


def resource_key(node):
    kind = node.attrs.get("type", node.tag) if node.tag == "item" else node.tag
    if kind in ("array", "string-array", "integer-array"):
        kind = "array"
    name = node.attrs.get("name")
    # <public> declares visibility; it does not define a second resource.
    return (kind, name) if name and node.tag not in ("public", "public-group") else None


def text_type(node):
    return node.attrs.get("type") if node.tag == "item" else node.tag


def signature(text, formatted=True):
    """Map Java Formatter argument indexes to conversion families; allow reordering."""
    if not formatted or text.strip().startswith("@"):
        return {}
    args = defaultdict(set)
    implicit = 0
    previous = None
    consumed = set()
    for match in FORMAT.finditer(text):
        consumed.update(range(match.start(), match.end()))
        conversion = match["conversion"]
        if conversion in ("%", "n"):
            continue
        if "<" in match["flags"]:
            if previous is None or match["index"]:
                raise ValueError("relative formatter %< needs a preceding argument and no index")
            index = previous
        elif match["index"]:
            index = int(match["index"])
            if index == 0:
                raise ValueError("formatter argument indexes start at 1")
        else:
            implicit += 1
            index = implicit
        previous = index
        lower = conversion.lower()
        family = (
            "integer" if lower in ("d", "o", "x") else
            "float" if lower in ("e", "f", "g", "a") else
            "datetime" if len(conversion) == 2 else lower
        )
        args[index].add(family)
    # Ordinary percentages are legal resource text. Once formatting is in use,
    # every remaining percent must be escaped as %% or explicitly formatted=false.
    if args or re.search(r"%[0-9]+\$", text):
        if any(char == "%" and index not in consumed for index, char in enumerate(text)):
            raise ValueError("unrecognized formatter token or literal % (use %% when formatting)")
    return dict(args)


def audit(res_dir, require_complete=()):
    findings = []

    def report(level, node, message):
        location = node.location if isinstance(node, Node) else str(node)
        findings.append(Finding(level, location, message))

    config_path = res_dir / "xml/locales_config.xml"
    parsed = {}
    for path in sorted(res_dir.rglob("*.xml")):
        try:
            parsed[path] = parse_xml(path)
        except (expat.ExpatError, OSError) as error:
            report("ERROR", f"{path}:{getattr(error, 'lineno', 1)}", f"invalid XML: {error}")
    config = parsed.get(config_path)
    registered = []
    if config is None:
        report("ERROR", config_path, "cannot read locale registration")
    else:
        if config.tag != "locale-config":
            report("ERROR", config, "expected <locale-config>")
        for node in config.children:
            tag = node.attrs.get(ANDROID_NAME)
            if node.tag != "locale" or not tag:
                report("ERROR", node, "expected <locale android:name=\"BCP-47 tag\">")
            elif tag in registered:
                report("ERROR", node, f"duplicate registered locale {tag}")
            else:
                registered.append(tag)
        for tag in sorted(PUBLISHED - set(registered)):
            report("ERROR", config, f"published locale {tag} is absent from locales_config")

    catalogs = defaultdict(dict)
    configurations = {}
    for path, root in parsed.items():
        if path.parent.parent != res_dir or not (
            path.parent.name == "values" or path.parent.name.startswith("values-")
        ):
            continue
        directory = path.parent.name
        configurations[directory] = locale_configuration(directory)
        if root.tag != "resources":
            report("ERROR", root, "expected <resources>")
            continue
        for node in root.children:
            key = resource_key(node)
            if key is None:
                continue
            if key in catalogs[directory]:
                report("ERROR", node, f"duplicate {key[0]}/{key[1]}; first at "
                       f"{catalogs[directory][key].location}")
            else:
                catalogs[directory][key] = node

    base = catalogs.get("values", {})
    if not base:
        report("ERROR", res_dir / "values", "missing default resource catalog")
    text_base = {key: node for key, node in base.items() if text_type(node) in TEXT_TYPES}
    required = PUBLISHED | set(require_complete)
    locales = set(registered) | set(require_complete) | {
        tag for tag, _ in configurations.values() if tag
    }
    for tag in sorted(locales):
        # Android falls back from regional/script resources to their language.
        effective = dict(base) if tag.split("-")[0] == "en" else {}
        for level in (tag.split("-")[0], tag):
            for directory, (locale, qualifiers) in configurations.items():
                if locale == level and not qualifiers:
                    effective.update(catalogs[directory])
        missing = [
            (key, node) for key, node in text_base.items()
            if node.attrs.get("translatable") != "false" and key not in effective
        ]
        for key, node in missing:
            report("ERROR" if tag in required else "WARNING", node,
                   f"{tag}: missing {key[0]}/{key[1]} (base definition)")
        if tag not in registered:
            report("WARNING", config_path, f"{tag}: resources exist but locale is not registered")

    def resolve(node, catalog, trail=()):
        reference = REFERENCE.fullmatch(node.text.strip())
        if not reference:
            return node.text
        key = ("string", reference[1])
        if key in trail or key not in catalog:
            report("ERROR", node, f"unresolved or cyclic string reference {node.text.strip()}")
            return ""
        return resolve(catalog[key], catalog, trail + (key,))

    def parts(node):
        if text_type(node) == "string":
            return {"text": node}
        items = {}
        for index, child in enumerate(node.children):
            if child.tag != "item":
                continue
            label = child.attrs.get("quantity", "") if node.tag == "plurals" else index
            if node.tag == "plurals":
                if label not in ("zero", "one", "two", "few", "many", "other"):
                    report("ERROR", child, f"{node.attrs['name']}: invalid plural quantity {label!r}")
                if label in items:
                    report("ERROR", child, f"{node.attrs['name']}: duplicate plural quantity {label}")
            items[label] = child
        if node.tag == "plurals" and "other" not in items:
            report("ERROR", node, f"{node.attrs['name']}: plurals require quantity=\"other\"")
        return items

    for directory, catalog in sorted(catalogs.items()):
        locale, qualifiers = configurations[directory]
        fallback = dict(base)
        if locale:
            for level in (locale.split("-")[0], locale):
                for name, (tag, extra) in configurations.items():
                    if tag == level and not extra:
                        fallback.update(catalogs[name])
        fallback.update(catalog)
        for key, node in catalog.items():
            if text_type(node) not in TEXT_TYPES:
                continue
            original = text_base.get(key)
            if original is None and locale:
                report("ERROR", node, f"{locale}: {key[0]}/{key[1]} has no base definition")
            current_parts = parts(node)
            original_parts = parts(original) if original and original is not node else current_parts
            if original and text_type(original) != text_type(node):
                report("ERROR", node, f"{key[1]}: resource type differs from {original.location}")
                continue
            if original and node.tag == "string-array" and len(current_parts) != len(original_parts):
                report("WARNING", node, f"{key[1]}: array length {len(current_parts)} differs from "
                       f"base {len(original_parts)}; confirm index-based consumers")
            for label, child in current_parts.items():
                expected = original_parts.get(label)
                if node.tag == "plurals" and expected is None:
                    expected = original_parts.get("other")
                enabled = node.attrs.get("formatted") != "false"
                if original and original.attrs.get("formatted") == "false":
                    enabled = False
                try:
                    actual = signature(resolve(child, fallback), enabled)
                    if expected is None or original is node or original is None:
                        continue
                    wanted = signature(
                        resolve(expected, base), original.attrs.get("formatted") != "false")
                    # A plural may spell out a count ("No files"), so omission is
                    # legal. Categories also differ between languages.
                    compatible = (
                        all(index in wanted and types == wanted[index]
                            for index, types in actual.items())
                        if node.tag == "plurals" else actual == wanted
                    )
                    if not compatible:
                        report("ERROR", child, f"{key[1]}[{label}]: formatter {actual} differs "
                               f"from base {wanted} at {expected.location}")
                except ValueError as error:
                    report("ERROR", child, f"{key[1]}[{label}]: {error}")
    return findings, len(text_base), registered


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--res-dir", type=Path, default=ROOT / "app/src/main/res")
    parser.add_argument("--require-complete", action="append", default=[], metavar="LOCALE",
                        help="also fail on missing translations for this new BCP-47 locale")
    args = parser.parse_args(argv)
    findings, count, locales = audit(args.res_dir, args.require_complete)
    for finding in findings:
        print(finding)
    errors = sum(f.level == "ERROR" for f in findings)
    warnings = len(findings) - errors
    print(f"i18n: {count} base text resources; registered {', '.join(locales)}; "
          f"{errors} error(s), {warnings} warning(s)")
    return int(errors > 0)


if __name__ == "__main__":
    sys.exit(main())
