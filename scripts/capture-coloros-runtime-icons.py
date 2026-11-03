#!/usr/bin/env python3
"""Read the inspected ColorOS runtime artwork cache without changing the source device.

uv run --with pillow python scripts/capture-coloros-runtime-icons.py --serial emulator-5574
Then regenerate with scripts/prepare-coloros-builtins.py. OEM artwork stays in .artifacts/.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import re
import shlex
import subprocess
import tarfile
import tempfile

from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
FRAMEWORK_SHA256 = "52c4783bc6027756b56dcb7780d838dd4b36b9726b28f4db2f0a03d49237a0ae"
PACKAGE = re.compile(r"[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+")
ICON = re.compile(r"[^/\\\x00-\x1f\x7f]{1,200}\.png")
MAX_FILES = 50000
MAX_FILE_BYTES = 4 * 1024 * 1024
MAX_TOTAL_BYTES = 512 * 1024 * 1024


def digest(data):
    return hashlib.sha256(data).hexdigest()


def unpack(archive, destination):
    """Copy only validated package/PNG pairs, never tar paths, links or staging contents."""
    files, total = {}, 0
    with tarfile.open(archive, "r:gz") as source:
        for member in source:
            path = PurePosixPath(member.name)
            if path.is_absolute() or ".." in path.parts:
                raise ValueError("Unsafe runtime archive path: " + member.name)
            if len(path.parts) != 2 or not PACKAGE.fullmatch(path.parts[0]) or not ICON.fullmatch(path.name):
                continue
            if not member.isfile() or not 24 <= member.size <= MAX_FILE_BYTES:
                raise ValueError("Unsupported runtime icon: " + member.name)
            total += member.size
            key = path.as_posix()
            if key in files or len(files) >= MAX_FILES or total > MAX_TOTAL_BYTES:
                raise ValueError("Duplicate icon or runtime snapshot limit exceeded")
            with source.extractfile(member) as stream:
                data = stream.read(MAX_FILE_BYTES + 1)
            if len(data) != member.size:
                raise ValueError("Incomplete runtime icon: " + key)
            with Image.open(io.BytesIO(data)) as image:
                if image.format not in {"PNG", "JPEG", "WEBP"} or getattr(image, "is_animated", False) or max(image.size) > 4096:
                    raise ValueError("Unsupported runtime image: " + key)
                size, image_format = list(image.size), image.format
                image.verify()
            target = destination / key
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
            files[key] = {"sha256": digest(data), "bytes": len(data), "size": size, "format": image_format}
    if not files:
        raise ValueError("Empty runtime snapshot; previous artwork was preserved")
    return dict(sorted(files.items()))


def publish(stage, destination, stamp):
    """Publish a completely validated snapshot, retaining the old local source as a backup."""
    if destination.is_symlink() or destination.exists() and not destination.is_dir():
        raise ValueError("Runtime destination must be a real directory")
    backup = destination.with_name(destination.name + ".previous-" + stamp)
    if backup.exists():
        raise ValueError("Snapshot backup already exists: " + str(backup))
    previous = destination.exists()
    if previous:
        destination.rename(backup)
    try:
        stage.rename(destination)
    except BaseException:
        if previous:
            backup.rename(destination)
        raise
    return backup if previous else None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True, help="Explicit ColorOS ADB source; no implicit device selection")
    parser.add_argument("--output", type=Path, default=ROOT / ".artifacts/inputs/coloros/runtime-uxicons")
    args = parser.parse_args()
    destination = args.output.absolute()
    destination.parent.mkdir(parents=True, exist_ok=True)

    def command(shell_command, **kwargs):
        return subprocess.run(["adb", "-s", args.serial, "shell", "-T", shell_command],
                              check=True, timeout=60, **kwargs)

    def root(shell_command, **kwargs):
        return command("su -c " + shlex.quote(shell_command), **kwargs)

    framework = root("sha256sum /system/framework/oplus-framework.jar", capture_output=True, text=True).stdout.split()[0]
    if framework != FRAMEWORK_SHA256:
        raise ValueError("Source framework differs from the inspected ColorOS contracts")
    fingerprint = command("getprop ro.build.fingerprint", capture_output=True, text=True).stdout.strip()
    now = datetime.now(timezone.utc)
    stamp = now.strftime("%Y%m%dT%H%M%S%fZ")
    with tempfile.TemporaryDirectory(prefix=".runtime-capture-", dir=destination.parent) as scratch:
        scratch = Path(scratch)
        archive = scratch / "runtime.tar.gz"
        with archive.open("wb") as output:
            root("tar -czf - -C /data/oplus/uxicons .", stdout=output)
        if archive.stat().st_size > MAX_TOTAL_BYTES:
            raise ValueError("Runtime archive exceeds snapshot limit")
        stage = scratch / "validated"
        stage.mkdir()
        files = unpack(archive, stage)
        previous = {p.relative_to(destination).as_posix(): digest(p.read_bytes())
                    for p in destination.glob("*/*.png") if p.is_file() and not p.is_symlink()}
        changes = {
            "added": sorted(set(files) - set(previous)),
            "changed": sorted(key for key in files.keys() & previous.keys() if files[key]["sha256"] != previous[key]),
            "removed": sorted(set(previous) - set(files)),
        }
        report = {"schema": 1, "capturedAt": now.isoformat(), "serial": args.serial,
                  "fingerprint": fingerprint, "frameworkSha256": framework,
                  "source": "/data/oplus/uxicons", "archiveSha256": digest(archive.read_bytes()),
                  "files": files, "changes": changes}
        backup = publish(stage, destination, stamp)
        report["previousSnapshot"] = str(backup) if backup else None
        report_path = destination.with_name(destination.name + ".snapshot.json")
        report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(f"Captured {len(files)} PNGs for {len({key.split('/')[0] for key in files})} packages")
    print("Changes:", ", ".join(f"{key}={len(value)}" for key, value in changes.items()))
    print("Snapshot:", report_path)
    if backup:
        print("Previous artwork:", backup)


if __name__ == "__main__":
    main()
