#!/usr/bin/env python3
"""Extract optional PNG-only assets from inspected, user-owned HyperOS native packs.

OEM bytes remain in ignored .artifacts/. Native scripts are never bundled or evaluated.
Default icons are read from the target firmware at runtime, not this host's snapshots.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import struct
from zipfile import ZipFile, ZIP_DEFLATED, ZipInfo
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--classic", type=Path, required=True)
    parser.add_argument("--boundless", type=Path, required=True)
    parser.add_argument("--output", type=Path, default=ROOT / ".artifacts/generated/assets/system-icon-fallback")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    proof = {}
    for style in ("classic", "boundless"):
        path = getattr(args, style)
        entries = {}
        pattern = (r"layer_animating_icons/([^/]+)/quiet/quietImage\.png" if style == "classic"
                   else r"res/drawable-xxhdpi/([^/]+)\.png")
        with ZipFile(path) as source:
            if style == "classic":
                config = source.read("transform_config.xml")
                if len(config) > 16 * 1024:
                    raise ValueError("Classic shape config exceeds bounds")
                masks = [e.attrib["value"] for e in ET.fromstring(config).findall("Config")
                         if e.attrib.get("name") == "ConfigIconMask"]
                if len(masks) != 1 or not 1 <= len(masks[0]) <= 4096:
                    raise ValueError("Missing unique native Classic mask")
                (args.output / "classic-mask.txt").write_text(masks[0] + "\n")
            for name in source.namelist():
                match = re.fullmatch(pattern, name)
                if not match:
                    continue
                key = match.group(1)
                if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_.]*", key) or "." not in key:
                    continue
                info = source.getinfo(name)
                if not 24 <= info.file_size <= 4 * 1024 * 1024:
                    raise ValueError(f"PNG size limit: {name}")
                data = source.read(name)
                if data[:8] != b"\x89PNG\r\n\x1a\n":
                    raise ValueError(f"Not a PNG: {name}")
                width, height = struct.unpack(">II", data[16:24])
                if width != height or not 1 <= width <= 512:
                    raise ValueError(f"Unsupported PNG dimensions: {name}")
                if key in entries:
                    raise ValueError(f"Duplicate native name: {key}")
                entries[key] = data
        if not entries or len(entries) > 1000:
            raise ValueError(f"Unexpected {style} resource count")
        target = args.output / f"{style}.zip"
        with ZipFile(target, "w", compression=ZIP_DEFLATED) as output:
            for key, data in sorted(entries.items()):
                entry = ZipInfo(key + ".png", date_time=(1980, 1, 1, 0, 0, 0))
                entry.compress_type = ZIP_DEFLATED
                output.writestr(entry, data)
        proof[style] = {
            "source_sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
            "archive_sha256": hashlib.sha256(target.read_bytes()).hexdigest(),
            "entries": len(entries),
            "source": "HyperOS native icons archive; static PNG artwork only; live clock/calendar excluded at runtime",
        }
        print(style, len(entries), target)
    (args.output / "provenance.json").write_text(json.dumps(proof, indent=2) + "\n")


if __name__ == "__main__":
    main()
