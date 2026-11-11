#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Compare host resolver output to the fixtures' independent native debugdata."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import struct
import subprocess

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location(
    "elf_fixture", HERE.parent / "icon-highlight/elf_fixture.py")
elf = importlib.util.module_from_spec(spec)
spec.loader.exec_module(elf)


def inspect(version, entry, launcher_root, binary):
    path = launcher_root / version / "libapp.so"
    image = elf.Image(path)  # Symbols come from decompressed .gnu_debugdata.

    def symbol(name):
        [(at, size)] = image.syms[name]
        return at, size

    def word(at):
        return struct.unpack("<I", image.read(at, 4))[0]

    def branch(at):
        value = word(at)
        if value & 0xfc000000 != 0x94000000:
            return None
        delta = value & 0x3ffffff
        return at + ((delta ^ 0x2000000) - 0x2000000) * 4

    build, build_size = symbol("_HotseatWidgetState.build")
    items, _ = symbol("_HotseatWidgetState._buildItems")
    background, _ = symbol("_HotseatWidgetState._buildBackground")
    sync, _ = symbol("_HotseatWidgetState._syncRemoveAnimationState")
    # Anonymous Dart closures have no debug symbol. Find them independently
    # from calls to named native methods in the enclosing build/items interval.
    starts = [at for at in range(build + build_size, items, 4)
              if word(at) == 0xa9bf79fd and word(at + 4) == 0xaa0f03fd]

    def caller(target):
        calls = [at for at in range(build + build_size, items, 4)
                 if branch(at) == target]
        assert len(calls) == 1, (version, hex(target), calls)
        call = calls[0]
        start = max(at for at in starts if at < call)
        return start, call

    phone, background_call = caller(background)
    outer, sync_call = caller(sync)
    outer_end = min(at for at in starts + [items] if at > outer)
    logger, _ = symbol("_LoggerImpl.logWithLevel")
    [outer_call] = [at for at in range(outer, outer_end, 4)
                    if branch(at) == logger]
    phone_end = min(at for at in starts + [items] if at > phone)
    assert phone == int(entry["inner"], 16)
    assert outer == int(entry["outer"], 16)
    assert phone_end - phone == int(entry.get("phone_size", "0x4c0"), 16)
    expected = {
        "phone": phone,
        "bg": background_call,
        "outer": outer_call,
        "mode": symbol("BlurCapabilityCheckerImpl.computeMode")[0],
        "glass": symbol("GlassOs4Background.build")[0],
        "key_caller": symbol("IconCacheV2.desktopIconCacheKey")[0],
        "key_equals": symbol("ValueKey.==")[0],
        "unlock_create": symbol("UnlockAnimWidget.createState")[0],
        "unlock_location": symbol("_UnlockWidgetState._resolveCellLocationInfo")[0],
        "unlock_center": symbol("SearchBarGetXController.getCenterLocation")[0],
    }
    run = subprocess.run([str(binary), str(path), "--resolve-only"],
                         capture_output=True, text=True, check=True)
    actual = {key: int(value, 16)
              for key, value in re.findall(r"(\w+)=([0-9a-f]+)", run.stdout)}
    for key, value in expected.items():
        assert actual[key] == value, (version, key, actual.get(key), value)
    return {
        "fixture": version,
        "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
        "oracle": ".gnu_debugdata named BL targets and anonymous closure boundaries",
        "phone_size": hex(phone_end - phone),
        "outer": hex(outer),
        "sync_call": hex(sync_call),
        "verified_fields": {key: hex(value) for key, value in expected.items()},
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--binary", type=Path, required=True)
    parser.add_argument("--launcher-root", type=Path,
                        default=HERE.parents[2] / ".artifacts/inputs/launchers")
    fixtures = json.loads((HERE / "fixtures.json").read_text())
    parser.add_argument("--versions", nargs="+", choices=tuple(fixtures),
                        default=tuple(fixtures), help="fixture subset; defaults to all")
    args = parser.parse_args()
    print(json.dumps([inspect(version, entry, args.launcher_root, args.binary)
                      for version, entry in fixtures.items() if version in args.versions], indent=2))


if __name__ == "__main__":
    main()
