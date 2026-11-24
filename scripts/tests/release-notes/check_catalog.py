#!/usr/bin/env python3
"""Verify bundled release identities against local tags without using Git on Android."""

import re
import subprocess
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[3]
source = (ROOT / "app/src/main/kotlin/os/kei/hyper/home/data/ReleaseNotesCatalog.kt").read_text()
entries = re.findall(
    r'^    ReleaseNote\(\s*version = "([^"]+)",(.*?)^    \),',
    source, re.M | re.S,
)
assert entries and len(entries) == source.count("    ReleaseNote("), "Incomplete release catalog"
versions = [tuple(map(int, version.split("."))) for version, _ in entries]
assert versions == sorted(set(versions), reverse=True)
assert min(versions) == (1, 4, 0), "History must start with the first public release"
current = re.search(r'^keihome.versionName=(.+)$', (ROOT / "gradle.properties").read_text(), re.M)[1]
assert entries[0][0] == current, "The current release must have its own catalog entry"
verified = 0
for version, body in entries:
    tag = f"v{version}"
    has_tag = subprocess.run(
        ["git", "show-ref", "--verify", "--quiet", f"refs/tags/{tag}"], cwd=ROOT,
    ).returncode == 0
    metadata = re.findall(r'^\s*(tag|committedAt) = "([^"]+)",$', body, re.M)
    if not has_tag:
        assert version == current and not metadata, (version, "Only the current untagged release can omit metadata")
        assert f"changes = R.array.release_notes_{version.replace('.', '_')}_changes," in body
        continue
    assert len(metadata) == 2 and dict(metadata).get("tag") == tag, (version, metadata)
    timestamp = dict(metadata)["committedAt"]
    actual = subprocess.check_output(
        ["git", "show", "-s", "--format=%cI", f"{tag}^{{commit}}"], cwd=ROOT, text=True,
    ).splitlines()
    assert actual == [timestamp], (version, actual, timestamp)
    verified += 1
    if version == current:
        tagged_properties = subprocess.check_output(
            ["git", "show", f"{tag}:gradle.properties"], cwd=ROOT, text=True,
        )
        assert f"keihome.versionName={current}" in tagged_properties.splitlines()
for filename in ("release_notes_content.xml", "release_notes_ui.xml"):
    catalogs = []
    for locale in ("values", "values-zh-rCN", "values-ja"):
        tree = ET.parse(ROOT / "app/src/main/res" / locale / filename)
        if filename == "release_notes_content.xml" and verified < len(entries):
            name = f"release_notes_{current.replace('.', '_')}_changes"
            changes = tree.findall(f"./string-array[@name='{name}']/item")
            assert changes and all((item.text or "").strip() for item in changes), (locale, name)
        catalogs.append([(node.tag, node.attrib["name"], len(node) if node.tag == "string-array" else None)
                         for node in tree.getroot()])
    assert catalogs[0] == catalogs[1] == catalogs[2], filename
print(f"Verified {verified} tag targets, {len(entries) - verified} untagged entry, and three-locale catalog parity")
