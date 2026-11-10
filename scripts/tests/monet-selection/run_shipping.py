#!/usr/bin/env python3
"""Check bridges in an existing immutable unstripped shipping SO; no native rebuild."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shlex
import subprocess
import sys

sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--elf", type=Path, required=True)
    parser.add_argument("--fixture-root", type=Path, required=True)
    parser.add_argument("--matrix", type=Path, required=True, help="Completed run_matrix.py output with resolver layouts")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if not __debug__:
        parser.error("assertions must be enabled")
    elf = args.elf.resolve()
    digest = hashlib.sha256(elf.read_bytes()).hexdigest()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    versions = json.loads((HERE / "fixture_inventory.json").read_text())
    commands = [("selection", [sys.executable, HERE / "test_bridge_paths.py", output, elf])]
    for index, version in enumerate(versions):
        tint_layout = args.matrix / f"tint-{version}/resolved-layout.json"
        image_layout = args.matrix / f"image/layout-{index}.json"
        for layout in (tint_layout, image_layout):
            if not layout.is_file():
                parser.error(f"missing independent resolver layout: {layout}")
        commands += [
            ("tint-" + version, [sys.executable, HERE.parent / "monet-tint/test_bridges.py",
                                "--elf", elf, "--report", output / f"tint-{version}.json",
                                "--layout", tint_layout.resolve()]),
            ("image-" + version, [sys.executable, HERE.parent / "monet-image-recovery/test_bridge.py",
                                 "--elf", elf, "--library", (args.fixture_root / version / "libapp.so").resolve(),
                                 "--layout", image_layout.resolve(), "--report", output / f"image-{version}.json"]),
        ]
    results = []
    for name, command in commands:
        command = list(map(str, command))
        with (output / (name + ".log")).open("w") as log:
            log.write("$ " + shlex.join(command) + "\n")
            log.flush()
            result = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT,
                                    env={**os.environ, "PYTHONDONTWRITEBYTECODE": "1"})
        results.append({"case": name, "command": shlex.join(command), "exit": result.returncode})
        print(f"{name}: {result.returncode}", flush=True)
    assert hashlib.sha256(elf.read_bytes()).hexdigest() == digest, "shipping payload changed during validation"
    passed = all(result["exit"] == 0 for result in results)
    (output / "report.json").write_text(json.dumps({
        "passed": passed, "elf": str(elf), "elf_sha256": digest,
        "results": results,
        "limits": "Selection uses a bounded decoded instruction model; tint and image use Unicorn. "
                  "Resolver layouts come from host production source tests; no resolver in this SO is executed. "
                  "Synthetic heap/globals and explicit allocation/handle mocks; no Dart GC or device acceptance.",
    }, indent=2) + "\n")
    return 0 if passed else 1


if __name__ == "__main__":
    sys.exit(main())
