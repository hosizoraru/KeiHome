#!/usr/bin/env python3
"""Repair explicitly confirmed Monday-first calendar artwork in a standalone icons ZIP."""

import argparse
import hashlib
import json
import re
import shutil
import tempfile
import zipfile
from pathlib import Path


PREDICATE = re.compile(
    rb"#day_of_week-1==([0-6]),'((?:dark/)?calendar_bg_([1-7])\.png)'"
)


def repair_manifest(data):
    """Change only the verified predicates; do not parse or execute supplied MAML."""
    matches = list(PREDICATE.finditer(data))
    expected = {
        (str(index).encode(), prefix + f"calendar_bg_{index + 1}.png".encode())
        for prefix in (b"", b"dark/") for index in range(7)
    }
    actual = [(m[1], m[2]) for m in matches]
    if len(actual) != 14 or set(actual) != expected:
        raise ValueError("Expected exactly seven ordered weekday predicates in each appearance")

    def replace(match):
        # Artwork 1 is Monday (native value 2); artwork 7 is Sunday (native value 1).
        native_weekday = int(match[3]) % 7 + 1
        return f"#day_of_week=={native_weekday},'".encode() + match[2] + b"'"

    return PREDICATE.sub(replace, data)


def repair(source, destination, packages):
    source, destination = Path(source), Path(destination)
    if source.resolve() == destination.resolve() or destination.exists():
        raise ValueError("Use a new output path; source and existing files are never overwritten")
    if not packages or len(packages) != len(set(packages)):
        raise ValueError("Select unique calendar packages with confirmed Monday-first artwork")
    if any(not re.fullmatch(r"[A-Za-z0-9_$]+(?:\.[A-Za-z0-9_$]+)+", p) for p in packages):
        raise ValueError("Invalid calendar package")
    if not 0 < source.stat().st_size <= 256 * 1024 * 1024:
        raise ValueError("Archive size limit")
    changed = {}
    with zipfile.ZipFile(source) as archive:
        entries = archive.infolist()
        names = [entry.filename for entry in entries]
        if len(entries) > 20000 or len(names) != len(set(names)):
            raise ValueError("Duplicate entries or archive entry limit")
        if any(name.startswith("/") or "\\" in name or
               any(part in (".", "..") for part in name.rstrip("/").split("/"))
               for name in names):
            raise ValueError("Nonlocal archive path")
        if sum(entry.file_size for entry in entries) > 512 * 1024 * 1024:
            raise ValueError("Expanded archive size limit")
        for package in packages:
            root = f"fancy_icons/{package}/"
            manifest = archive.getinfo(root + "manifest.xml")
            if not 0 < manifest.file_size <= 256 * 1024:
                raise ValueError("Calendar manifest size limit")
            for prefix in ("", "dark/"):
                for index in range(1, 8):
                    image = archive.getinfo(root + prefix + f"calendar_bg_{index}.png")
                    if image.is_dir() or not 0 < image.file_size <= 8 * 1024 * 1024:
                        raise ValueError("Missing or oversized weekday artwork")
            changed[manifest.filename] = repair_manifest(archive.read(manifest))
        # Stage beside the destination. A failed read/CRC/write never publishes a partial ZIP.
        destination.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix=".calendar-repair-", dir=destination.parent) as temp:
            staged = Path(temp) / "icons.zip"
            with zipfile.ZipFile(staged, "w") as output:
                output.comment = archive.comment
                for entry in entries:
                    if entry.filename in changed:
                        output.writestr(entry, changed[entry.filename])
                    else:
                        with archive.open(entry) as reader, output.open(entry, "w") as writer:
                            shutil.copyfileobj(reader, writer, 64 * 1024)
            # Exclusive publication also protects against a concurrently created destination.
            with destination.open("xb") as writer, staged.open("rb") as reader:
                try:
                    shutil.copyfileobj(reader, writer, 64 * 1024)
                except BaseException:
                    destination.unlink(missing_ok=True)
                    raise
    def digest(path):
        with path.open("rb") as stream:
            return hashlib.file_digest(stream, "sha256").hexdigest()
    return {"sourceSha256": digest(source), "outputSha256": digest(destination),
            "changedManifests": sorted(changed), "unchangedEntryCount": len(entries) - len(changed)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("destination", type=Path)
    parser.add_argument("--calendar-package", action="append", required=True)
    parser.add_argument("--confirm-monday-first-artwork", action="store_true", required=True,
                        help="Required: visually verify weekday artwork 1..7 is Monday..Sunday")
    args = parser.parse_args()
    print(json.dumps(repair(args.source, args.destination, args.calendar_package), indent=2))


if __name__ == "__main__":
    main()
