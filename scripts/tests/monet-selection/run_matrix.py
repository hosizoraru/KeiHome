#!/usr/bin/env python3
"""Run all four offline Monet suites on the complete external fixture inventory."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
import os
from pathlib import Path
import shlex
import subprocess
import sys

sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fixture-root", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if not __debug__:
        parser.error("assertions must be enabled")
    versions = json.loads((HERE / "fixture_inventory.json").read_text())
    fixtures = [(args.fixture_root / version).resolve() for version in versions]
    for fixture in fixtures:
        for name in ("libapp.so", "debug.elf"):
            if not (fixture / name).is_file():
                parser.error(f"missing external fixture: {fixture / name}")
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    selection = [sys.executable, HERE / "run.py", "--output-dir", output / "selection"]
    image = [sys.executable, HERE.parent / "monet-image-recovery/validate.py",
             "--verify-generated", "--output", output / "image"]
    gate = [sys.executable, HERE.parent / "monet-gate/run_tests.py"]
    tint = []
    for fixture in fixtures:
        selection += ["--fixture", fixture / "libapp.so", fixture / "debug.elf"]
        image += ["--library", fixture / "libapp.so"]
        gate += [fixture / "libapp.so"]
        tint.append([sys.executable, HERE.parent / "monet-tint/validate.py",
                     "--library", fixture / "libapp.so", "--require-emulation",
                     "--output", output / ("tint-" + fixture.name)])
    selection_generator = [sys.executable, HERE / "generate_contracts.py",
                           "--output", output / "selection-patterns.h",
                           "--evidence", output / "selection-contracts.json"]
    tint_generator = [sys.executable, HERE.parent / "monet-tint/generate_variants.py",
                      "--output", output / "tint-variants.h"]
    for fixture in fixtures:
        selection_generator += ["--fixture", fixture / "libapp.so", fixture / "debug.elf"]
        tint_generator += ["--library", fixture / "libapp.so"]
    generated = {"selection": (output / "selection-patterns.h", "monet_selection_patterns.h"),
                 "tint": (output / "tint-variants.h", "monet_tint_variants.h")}
    commands = {"selection": [selection_generator, selection], "tint": [tint_generator, *tint],
                "image": [image], "gate": [gate]}

    def run_suite(item):
        name, suite = item
        rows = []
        with (output / (name + ".log")).open("w") as log:
            for command in suite:
                command = list(map(str, command))
                log.write("$ " + shlex.join(command) + "\n")
                log.flush()
                result = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT,
                                        env={**os.environ, "PYTHONDONTWRITEBYTECODE": "1"})
                rows.append({"command": shlex.join(command), "exit": result.returncode})
            if name in generated:
                generated_path, source_name = generated[name]
                matches = (generated_path.is_file() and generated_path.read_bytes() ==
                           (REPO / "app/src/main/cpp" / source_name).read_bytes())
                rows.append({"command": f"compare generated {source_name}", "exit": 0 if matches else 1})
                log.write(f"Generated {source_name} reproducible: {matches}\n")
        print(f"{name}: {'passed' if all(r['exit'] == 0 for r in rows) else 'FAILED'}", flush=True)
        return name, rows

    with ThreadPoolExecutor(max_workers=4) as executor:
        results = dict(executor.map(run_suite, commands.items()))
    passed = all(row["exit"] == 0 for rows in results.values() for row in rows)
    sources = [*REPO.joinpath("app/src/main/cpp").glob("*")]
    sources += [p for name in ("monet-selection", "monet-tint", "monet-image-recovery", "monet-gate")
                for p in (HERE.parent / name).rglob("*") if "__pycache__" not in p.parts]
    report = {
        "passed": passed, "offline_only": True, "versions": versions, "results": results,
        "source_sha256": {str(p.relative_to(REPO)): hashlib.sha256(p.read_bytes()).hexdigest()
                          for p in sorted(sources) if p.is_file()},
        "fixtures_sha256": {str(f / n): hashlib.sha256((f / n).read_bytes()).hexdigest()
                            for f in fixtures for n in ("libapp.so", "debug.elf")},
    }
    (output / "report.json").write_text(json.dumps(report, indent=2) + "\n")
    return 0 if passed else 1


if __name__ == "__main__":
    sys.exit(main())
