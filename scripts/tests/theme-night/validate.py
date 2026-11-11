#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Validate the isolated theme-night candidate; never enables a production hook."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import sys

sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
CPP = REPO / "app/src/main/cpp"
from generate_contracts import Fixture, ROLES, role


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--library", type=Path, action="append", required=True)
    parser.add_argument("--reject-library", type=Path, action="append", default=[],
                        help="Assert that an external image remains outside the candidate contracts")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--verify-generated", action="store_true",
                        help="Require the complete fixture set to reproduce candidate contracts")
    args = parser.parse_args()
    if not __debug__:
        parser.error("assertions must be enabled")
    compiler = os.environ.get("CXX") or shutil.which("clang++")
    if not compiler:
        parser.error("host C++17 compiler required")
    fixtures = [path.resolve() for path in args.library]
    rejected_fixtures = [path.resolve() for path in args.reject_library]
    for path in [*fixtures, *rejected_fixtures]:
        if not path.is_file():
            parser.error(f"missing external fixture: {path}")
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    sources = sorted([*HERE.rglob("*.py"), *HERE.rglob("*.cpp"), *HERE.rglob("*.h"),
                      *HERE.rglob("*.S"), CPP / "monet_image_identity.cpp",
                      CPP / "monet_image_identity.h", CPP / "selection_profile.h",
                      HERE.parent / "monet-image-trace/generate_contracts.py"])
    record = {
        "passed": False, "candidate_only": True, "production_integrated": False,
        "source_sha256": {str(p.relative_to(REPO)): hashlib.sha256(p.read_bytes()).hexdigest()
                          for p in sources},
        "fixtures": [], "rejected_fixtures": [], "commands": [],
        "limits": "Host source resolver and synthetic mutations only; no candidate bridge "
                  "execution, archive admission protocol, Dart GC or device acceptance.",
    }

    def run(command):
        command = list(map(str, command))
        result = subprocess.run(command, text=True, capture_output=True, cwd=REPO)
        record["commands"].append({"command": shlex.join(command), "exit": result.returncode})
        with (output / "commands.log").open("a") as log:
            log.write("$ " + shlex.join(command) + "\n" + result.stdout + result.stderr
                      + f"\n[exit {result.returncode}]\n")
        if result.returncode:
            raise RuntimeError(f"{shlex.join(command)}\n{result.stdout}{result.stderr}")
        return result.stdout

    (output / "commands.log").write_text("")
    try:
        command = [sys.executable, HERE / "generate_contracts.py",
                   "--output", output / "patterns.h", "--evidence", output / "contracts.json"]
        for path in fixtures:
            command += ["--library", path]
        run(command)
        if args.verify_generated:
            matches = ((output / "patterns.h").read_bytes() ==
                       (HERE / "candidate/theme_night_patterns.h").read_bytes())
            record["generated_contracts_match"] = matches
            assert matches, "complete fixture set does not reproduce candidate contracts"
        run([compiler, "-std=c++17", "-O2", "-UNDEBUG", "-Wall", "-Wextra", "-Werror",
             "-I" + str(CPP), "-I" + str(HERE / "candidate"),
             HERE / "test_resolver.cpp", HERE / "candidate/theme_night_resolver.cpp",
             CPP / "monet_image_identity.cpp", "-o", output / "resolver"])
        fields = {"Init": "init", "Create": "create", "Seed": "seed",
                  "ReadMode": "read_mode", "SystemSetter": "system_setter",
                  "NodeSetter": "node_setter", "Apply": "apply",
                  "SystemBitmap": "system_bitmap", "NodeBitmap": "node_bitmap",
                  "BitmapLocal": "bitmap_local", "GetSystem": "get_system",
                  "Stream": "stream", "HasFile": "has_file", "StringEquals": "string_equals"}
        for index, path in enumerate(fixtures):
            row = json.loads(run([output / "resolver", path]))
            fixture = Fixture(path)
            for key, name in ROLES:
                assert row[fields[key]] == role(fixture, name)[0], (path, key)
            assert row["positives"] >= 6 and row["rejects"] >= 32
            assert row["config"][0] == 0, "candidate must remain disabled"
            row.update(library=str(path), sha256=hashlib.sha256(path.read_bytes()).hexdigest(),
                       independent_symbol_audit=True)
            record["fixtures"].append(row)
            (output / f"layout-{index}.json").write_text(json.dumps(row, indent=2) + "\n")
            print(f"{path}: {row['positives']} positive / {row['rejects']} rejected; "
                  "candidate disabled", flush=True)
        for path in rejected_fixtures:
            row = json.loads(run([output / "resolver", path, "--expect-rejection"]))
            assert row["accepted"] is False
            row.update(library=str(path), sha256=hashlib.sha256(path.read_bytes()).hexdigest())
            record["rejected_fixtures"].append(row)
            print(f"{path}: expected rejection: {row['reason']}", flush=True)
        record["passed"] = True
    except (OSError, RuntimeError, AssertionError, ValueError, KeyError) as error:
        record["error"] = str(error)
        print(str(error), file=sys.stderr)
    finally:
        (output / "validation.json").write_text(json.dumps(record, indent=2) + "\n")
    return 0 if record["passed"] else 1


if __name__ == "__main__":
    sys.exit(main())
