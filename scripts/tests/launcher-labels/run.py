#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Compile current label resolver, check independent symbols, execute linked ARM64."""
import argparse
import hashlib
import json
import lzma
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(Path(__file__).parents[1] / "icon-highlight"))
from elf_fixture import Image
from payload import authenticate

# These names are external fixture oracles, never production resolver inputs.
ROLES = {
    "builder": "ShortcutIconWidget._buildTextWidget",
    "lines": "GridController.titleLines",
    "measure": "TitleShadowUtils._measureText",
    "editing": "ScreenLayerGetXController._isInNormalEditing",
    "fadeWrap": "_MarqueeState._wrapWithFadingEdgeScrollView",
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("images", nargs="*", type=Path)
    parser.add_argument("--inventory", type=Path, default=Path(__file__).with_name("fixtures.json"))
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--payload", required=True, type=Path)
    parser.add_argument("--ndk", required=True, type=Path, help="NDK LLVM bin directory")
    parser.add_argument("--cxx", default=os.environ.get("CXX", "clang++"))
    args = parser.parse_args()
    images = args.images or [ROOT / ".artifacts/inputs/launchers" / v / "libapp.so"
                            for v in json.loads(args.inventory.read_text())["launchers"]]
    args.output.mkdir(parents=True, exist_ok=True)
    entry_test = args.output / "entry-index-test"
    subprocess.run([args.cxx, "-std=c++17", "-Wall", "-Wextra", "-Werror", "-O2",
                    "-fsanitize=address,undefined", "-fno-omit-frame-pointer",
                    "-I" + str(ROOT / "app/src/main/cpp"),
                    str(ROOT / "app/src/test/cpp/arm64_dart_entry_index_test.cpp"),
                    "-o", str(entry_test)], check=True)
    subprocess.run([str(entry_test)], check=True)
    _, authentication = authenticate(args.payload, args.ndk, args.output, "labels")
    (args.output / "payload.json").write_text(json.dumps(authentication, indent=2) + "\n")
    print(json.dumps({"payload": authentication}), flush=True)
    resolver = args.output / "label-profile"
    subprocess.run([args.cxx, "-std=c++17", "-Wall", "-Wextra", "-Werror", "-O2",
                    "-I" + str(ROOT / "app/src/main/cpp"),
                    str(ROOT / "app/src/test/cpp/launcher_label_profile_test.cpp"),
                    str(ROOT / "app/src/main/cpp/launcher_label_profile.cpp"),
                    str(ROOT / "app/src/main/cpp/icon_highlight_profile.cpp"),
                    "-o", str(resolver)], check=True)
    results, expanded = [], []
    for path in images:
        completed = subprocess.run([str(resolver), str(path)], text=True, capture_output=True, check=True)
        print(completed.stdout, end="", flush=True)
        (args.output / f"{path.parent.name}-resolver.log").write_text(completed.stdout + completed.stderr)
        row = dict(pair.split("=") for pair in completed.stdout.splitlines()[0].split())
        image = Image(path)
        for field, name in ROLES.items():
            [(address, _)] = image.syms[name]
            assert int(row[field], 16) == address, (path, field, name)
        if image.syms[ROLES["lines"]][0][1] == 0x9c:
            expanded.extend(["--library", str(path)])
        # The unpacked exact debug.elf must be the same independent oracle.
        debug = path.with_name("debug.elf")
        assert debug.read_bytes() == lzma.decompress(image.section(".gnu_debugdata")), path
        results.append({"image": str(path), "sha256": hashlib.sha256(image.data).hexdigest(),
                        "debug_sha256": hashlib.sha256(debug.read_bytes()).hexdigest(),
                        "symbol_oracles": ROLES, "profile": row,
                        "resolver_rejection_checks": 19})
    if expanded:
        generated = args.output / "launcher_label_variants.inc"
        subprocess.run([sys.executable, str(Path(__file__).with_name("generate_variants.py")),
                        *expanded, "--output", str(generated)], check=True)
        assert generated.read_bytes() == (ROOT / "app/src/main/cpp/launcher_label_variants.inc").read_bytes()
        print("Expanded label variants reproduce current production patterns.", flush=True)
    for kind, bridge in (("source", args.output / "labels-source-bridge.so"),
                         ("linked", Path(authentication["snapshot"]))):
        completed = subprocess.run(
            [sys.executable, str(Path(__file__).with_name("test_bridge.py")),
             "--payload", str(bridge), "--resolver", str(resolver), *map(str, images)],
            check=True, text=True, capture_output=True)
        (args.output / f"{kind}-bridge.log").write_text(completed.stdout + completed.stderr)
        print(kind, completed.stdout, sep="\n", end="", flush=True)
    (args.output / "results.json").write_text(json.dumps(results, indent=2) + "\n")


if __name__ == "__main__":
    main()
