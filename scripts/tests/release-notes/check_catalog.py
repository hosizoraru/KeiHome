#!/usr/bin/env python3
"""Verify bundled release identities against local tags without using Git on Android."""

import re
import subprocess
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[3]
source = (ROOT / "app/src/main/kotlin/os/kei/hyper/home/data/ReleaseNotesCatalog.kt").read_text()
entries = re.findall(
    r'ReleaseNote\(\s*version = "([^"]+)",\s*tag = "([^"]+)",'
    r'\s*committedAt = "([^"]+)"', source,
)
assert entries, "No release catalog entries found"
versions = [tuple(map(int, version.split("."))) for version, *_ in entries]
assert versions == sorted(set(versions), reverse=True)
assert min(versions) == (1, 4, 0), "History must start with the first public release"
for version, tag, timestamp in entries:
    actual = subprocess.check_output(
        ["git", "show", "-s", "--format=%cI", f"{tag}^{{commit}}"], cwd=ROOT, text=True,
    ).splitlines()
    assert actual == [timestamp], (version, actual, timestamp)
    assert tag == f"v{version}"
current = re.search(r'^keihome.versionName=(.+)$', (ROOT / "gradle.properties").read_text(), re.M)[1]
assert entries[0][0] == current, "The current release must have its own catalog entry"
for filename in ("release_notes_content.xml", "release_notes_ui.xml"):
    catalogs = []
    for locale in ("values", "values-zh-rCN", "values-ja"):
        tree = ET.parse(ROOT / "app/src/main/res" / locale / filename)
        catalogs.append([(node.tag, node.attrib["name"], len(node) if node.tag == "string-array" else None)
                         for node in tree.getroot()])
    assert catalogs[0] == catalogs[1] == catalogs[2], filename
print(f"Verified {len(entries)} tag targets and commit timestamps; three-locale catalog parity passed")
