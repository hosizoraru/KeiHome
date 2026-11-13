#!/usr/bin/env python3
"""Run offline Monet suites on the complete external fixture inventory."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
import os
from pathlib import Path
import re
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
    parser.add_argument("--suite", action="append",
                        choices=("selection", "tint", "image", "gate", "theme-night"),
                        help="Repeat to select suites; default preserves selection/tint/image/gate")
    parser.add_argument("--fixture-version", action="append",
                        help="Repeat to test selected inventory fixtures; generators still use all fixtures")
    args = parser.parse_args()
    if not __debug__:
        parser.error("assertions must be enabled")
    inventory = json.loads((HERE / "fixture_inventory.json").read_text())
    if args.fixture_version and any(version not in inventory for version in args.fixture_version):
        parser.error("--fixture-version must name a fixture in fixture_inventory.json")
    versions = [version for version in inventory
                if not args.fixture_version or version in args.fixture_version]
    fixtures = [(args.fixture_root / version).resolve() for version in inventory]
    selected_fixtures = [(args.fixture_root / version).resolve() for version in versions]
    for fixture in fixtures:
        for name in ("libapp.so", "debug.elf"):
            if not (fixture / name).is_file():
                parser.error(f"missing external fixture: {fixture / name}")
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    selection = [sys.executable, HERE / "run.py", "--output-dir", output / "selection"]
    image = [sys.executable, HERE.parent / "monet-image-recovery/validate.py",
             "--output", output / "image"]
    theme = [sys.executable, HERE.parent / "theme-night/validate.py",
             "--output", output / "theme-night"]
    theme_inventory = json.loads((HERE.parent / "theme-night/fixture_inventory.json").read_text())
    theme_accepted = set(theme_inventory["accepted"])
    theme_rejected = set(theme_inventory["rejected"])
    if (theme_accepted & theme_rejected or
            theme_accepted | theme_rejected != set(inventory)):
        parser.error("theme-night acceptance/rejection inventory must partition the fixture inventory")
    gate = [sys.executable, HERE.parent / "monet-gate/run_tests.py"]
    tint = []
    for fixture in selected_fixtures:
        selection += ["--fixture", fixture / "libapp.so", fixture / "debug.elf"]
        image += ["--library", fixture / "libapp.so"]
        theme += ["--library" if fixture.name in theme_accepted else "--reject-library",
                  fixture / "libapp.so"]
        gate += [fixture / "libapp.so"]
        tint.append([sys.executable, HERE.parent / "monet-tint/validate.py",
                     "--library", fixture / "libapp.so", "--require-emulation",
                     "--output", output / ("tint-" + fixture.name)])
    selection_generator = [sys.executable, HERE / "generate_contracts.py",
                           "--output", output / "selection-patterns.h",
                           "--evidence", output / "selection-contracts.json"]
    tint_generator = [sys.executable, HERE.parent / "monet-tint/generate_variants.py",
                      "--output", output / "tint-variants.h"]
    image_generator = [sys.executable, HERE.parent / "monet-image-recovery/generate_contracts.py",
                       "--output", output / "image-patterns.h",
                       "--evidence", output / "image-contracts.json"]
    theme_generator = [sys.executable, HERE.parent / "theme-night/generate_contracts.py",
                       "--output", output / "theme-patterns.h",
                       "--evidence", output / "theme-contracts.json"]
    for fixture in fixtures:
        selection_generator += ["--fixture", fixture / "libapp.so", fixture / "debug.elf"]
        tint_generator += ["--library", fixture / "libapp.so"]
        image_generator += ["--library", fixture / "libapp.so"]
        theme_generator += ["--library", fixture / "libapp.so"]
    generated = {
        "selection": (output / "selection-patterns.h", REPO / "app/src/main/cpp/monet_selection_patterns.h"),
        "tint": (output / "tint-variants.h", REPO / "app/src/main/cpp/monet_tint_variants.h"),
        "image": (output / "image-patterns.h", REPO / "app/src/main/cpp/monet_image_recovery_patterns.h"),
        "theme-night": (output / "theme-patterns.h", HERE.parent / "theme-night/candidate/theme_night_patterns.h"),
    }
    commands = {"selection": [selection_generator, selection], "tint": [tint_generator, *tint],
                "image": [image_generator, image], "gate": [gate],
                "theme-night": [theme_generator, theme]}
    selected = args.suite or ("selection", "tint", "image", "gate")
    commands = {name: suite for name, suite in commands.items() if name in selected}

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
                generated_path, source_path = generated[name]
                matches = (generated_path.is_file() and generated_path.read_bytes() ==
                           source_path.read_bytes())
                mode = "byte-identical regeneration"
                if name == "theme-night" and generated_path.is_file():
                    # Match whole role/count/prefix/digest tuples, never independent lines.
                    def contracts(path):
                        entries = re.findall(
                            r"\{k\w+,\s*\d+,\s*\{[^}]*\},\s*\{[^}]*\}\}",
                            path.read_text())
                        return {re.sub(r"\s+", "", entry) for entry in entries}
                    checked = contracts(source_path)
                    matches = bool(checked) and checked.issubset(contracts(generated_path))
                    mode = "all checked-in candidate contracts reproduced; extra shapes unadmitted"
                rows.append({"command": f"compare generated {source_path.name}: {mode}",
                             "exit": 0 if matches else 1})
                log.write(f"Generated {source_path.name} {mode}: {matches}\n")
        print(f"{name}: {'passed' if all(r['exit'] == 0 for r in rows) else 'FAILED'}", flush=True)
        return name, rows

    with ThreadPoolExecutor(max_workers=4) as executor:
        results = dict(executor.map(run_suite, commands.items()))
    passed = all(row["exit"] == 0 for rows in results.values() for row in rows)
    sources = [*REPO.joinpath("app/src/main/cpp").glob("*")]
    sources += [p for name in ("monet-selection", "monet-tint", "monet-image-recovery", "monet-gate",
                              "theme-night")
                for p in (HERE.parent / name).rglob("*") if "__pycache__" not in p.parts]
    report = {
        "passed": passed, "offline_only": True, "versions": versions, "results": results,
        "suites": list(commands),
        "contract_fixture_versions": inventory,
        "source_sha256": {str(p.relative_to(REPO)): hashlib.sha256(p.read_bytes()).hexdigest()
                          for p in sorted(sources) if p.is_file()},
        "fixtures_sha256": {str(f / n): hashlib.sha256((f / n).read_bytes()).hexdigest()
                            for f in fixtures for n in ("libapp.so", "debug.elf")},
    }
    (output / "report.json").write_text(json.dumps(report, indent=2) + "\n")
    return 0 if passed else 1


if __name__ == "__main__":
    sys.exit(main())
