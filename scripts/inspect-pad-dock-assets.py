#!/usr/bin/env python3
"""Inventory original Pad Dock SVGs without importing them as app mappings."""

import argparse
import hashlib
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET
from zipfile import ZipFile


def inspect(apk):
    assets = []
    with ZipFile(apk) as archive:
        for info in sorted(archive.infolist(), key=lambda item: item.filename):
            name = info.filename
            if not re.fullmatch(
                r"assets/flutter_assets/assets/images/ic_dock_[a-z_]+\.svg", name
            ):
                continue
            if info.file_size > 512 * 1024:
                raise ValueError(f"Unexpectedly large Dock SVG: {name}")
            data = archive.read(info)
            root = ET.fromstring(data)
            tags = sorted({element.tag.rsplit("}", 1)[-1] for element in root.iter()})
            colors = sorted({
                element.attrib[key]
                for element in root.iter()
                for key in ("fill", "stroke", "stop-color")
                if key in element.attrib
            })
            alpha = sorted({
                element.attrib[key]
                for element in root.iter()
                for key in ("opacity", "fill-opacity", "stroke-opacity", "stop-opacity")
                if key in element.attrib
            })
            assets.append({
                "path": name,
                "bytes": len(data),
                "sha256": hashlib.sha256(data).hexdigest(),
                "viewBox": root.attrib.get("viewBox"),
                "tags": tags,
                "colors": colors,
                "alpha": alpha,
                "hasSvgFilter": any(tag == "filter" or tag.startswith("fe") for tag in tags),
            })
    # Resource suffixes describe artwork variants, not a complete renderer.
    pairs = {}
    other = []
    for item in assets:
        match = re.search(r"ic_dock_(.+)_(light|dark)_blur\.svg$", item["path"])
        if match:
            pairs.setdefault(match[1], {})[match[2]] = item["path"]
        else:
            other.append(item["path"])
    with Path(apk).open("rb") as source:
        digest = hashlib.file_digest(source, "sha256").hexdigest()
    return {
        "schema": 1,
        "sourceApkSha256": digest,
        "assetCount": len(assets),
        "pairedRoles": pairs,
        "otherAssets": other,
        "assets": assets,
        "boundary": "Static SVG inventory only; runtime material and app mapping are not inferred.",
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    result = inspect(args.apk)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2, ensure_ascii=False) + "\n")
    print(f'{result["assetCount"]} SVGs, {len(result["pairedRoles"])} paired roles')


if __name__ == "__main__":
    main()
