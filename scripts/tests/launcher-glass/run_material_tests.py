#!/usr/bin/env python3
"""Material source/rejection suite: retained AOTs and a native base negative image."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import material_fixture_metadata as fixture_meta

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
CPP = ROOT / "app/src/main/cpp"
ORACLES = fixture_meta.OUT
VERSIONS = fixture_meta.VERSIONS


def fields(path):
    return {key: int(value, 16)
            for key, value in (line.split() for line in path.read_text().splitlines())}


def fingerprints():
    return {name: hashlib.sha256((CPP / name).read_bytes()).hexdigest()
            for name in ("launcher_glass_material_profile.h",
                         "launcher_glass_material_profile.cpp")}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sanitize", action="store_true")
    parser.add_argument("--versions", nargs="+", choices=VERSIONS, default=VERSIONS,
                        help="fixture subset; defaults to the complete retained matrix")
    parser.add_argument("--export-dir", type=Path, default=ORACLES,
                        help="write resolved TSVs, test log and JSON report")
    parser.add_argument("--negative-image", type=Path,
                        default=ROOT / ".artifacts/inputs/launchers/9209/libapp_launcher.so",
                        help="retained native base ELF that must reject every material role")
    parser.add_argument("--shipping-only", action="store_true",
                        help="verify shipping Dock/Search and skip retired material experiments")
    args = parser.parse_args()
    args.export_dir.mkdir(parents=True, exist_ok=True)
    if not args.negative_image.is_file():
        parser.error(f"missing native base negative image: {args.negative_image}")
    log_path = args.export_dir / "material-profile-test.log"
    report_path = args.export_dir / "material-profile-test-report.json"
    original = fingerprints()
    report = dict(passed=False, sanitized=args.sanitize,
                  production_sha256=original, fixtures=[],
                  negative_image=str(args.negative_image.resolve()))
    with tempfile.TemporaryDirectory(prefix="keihome-material-host-") as directory:
        binary = str(Path(directory) / "material-tests")
        cmd = ["clang++", "-std=c++17", "-O2", "-g", "-Wall", "-Wextra", "-Werror",
               "-I", str(CPP)]
        cmd += [str(CPP / name) for name in (
            "icon_highlight_profile.cpp", "launcher_label_profile.cpp",
            "dock_glass_refresh.cpp", "launcher_glass_material_profile.cpp")]
        cmd += [str(ROOT / "app/src/test/cpp/launcher_glass_material_profile_test.cpp"),
                "-o", binary]
        if args.sanitize:
            cmd += ["-fsanitize=address,undefined", "-fno-omit-frame-pointer"]
        compile_result = subprocess.run(cmd, text=True, capture_output=True)
        log_path.write_text(compile_result.stdout + compile_result.stderr)
        if compile_result.returncode:
            print(log_path.read_text(), end="", flush=True)
            report["failure"] = "host compilation failed"
            report_path.write_text(json.dumps(report, indent=2) + "\n")
            compile_result.check_returncode()
        fixtures = []
        for version in args.versions:
            fixtures += [str(ROOT / ".artifacts/inputs/launchers" / version / "libapp.so"),
                         str(ORACLES / f"material-{version}.tsv")]
        environment = dict(os.environ, KEIHOME_MATERIAL_EXPORT_DIR=str(args.export_dir.resolve()))
        if args.shipping_only:
            environment["KEIHOME_MATERIAL_SHIPPING_ONLY"] = "1"
        result = subprocess.run([binary, *fixtures, "--negative",
                                 str(args.negative_image.resolve())],
                                text=True, capture_output=True, env=environment)
        output = result.stdout + result.stderr
        with log_path.open("a") as log:
            log.write(output)
        print(output, end="", flush=True)
        if result.returncode:
            report["failure"] = "source/rejection suite failed"
            report_path.write_text(json.dumps(report, indent=2) + "\n")
            result.check_returncode()
        cases = {}
        for kind, path, checks, rejections in re.findall(
                r"^(CASE|NEGATIVE) (.+) checks=(\d+) rejections=(\d+)$",
                output, re.MULTILINE):
            key = Path(path).parent.name if kind == "CASE" else "negative"
            if key in cases:
                raise AssertionError(f"duplicate fixture result: {key}")
            cases[key] = dict(checks=int(checks), rejections=int(rejections),
                              image=path)
        if set(cases) != set(args.versions) | {"negative"}:
            raise AssertionError(f"missing fixture results: {cases.keys()}")
        for version in args.versions:
            extra_path = args.export_dir / f"material-extra-resolved-{version}.tsv"
            folder_path = args.export_dir / f"material-folder-refresh-resolved-{version}.tsv"
            folder = fields(folder_path)
            if args.shipping_only or "folder.source" not in fields(ORACLES / f"material-extra-{version}.tsv"):
                assert len(folder) == 48 and not folder["refreshSubjectLiveAcrossAdd"]
                report["fixtures"].append(dict(
                    version=version, **cases[version], shipping_search_count=1,
                    folder_refresh_fields=len(folder),
                    folder_refresh_report=str(folder_path.resolve())))
                continue
            extra = fields(extra_path)
            assert len(extra) == 61 and len(folder) == 48, (version, len(extra), len(folder))
            assert (extra["count"], extra["admittedMask"], extra["ambiguousMask"]) == (3, 7, 0)
            assert [extra[f"sites[{i}].kind"] for i in range(3)] == [1, 2, 3]
            assert all(extra[f"sites[{i}].lightSchemeTarget"] and
                       extra[f"sites[{i}].darkSchemeTarget"] for i in range(3))
            assert not folder["refreshSubjectLiveAcrossAdd"]
            report["fixtures"].append(dict(
                version=version, **cases[version], choose_count=3,
                admitted_mask=7, ambiguous_mask=0, choose_fields=len(extra),
                folder_refresh_fields=len(folder), choose_report=str(extra_path.resolve()),
                folder_refresh_report=str(folder_path.resolve())))
        totals = re.findall(r"material profile: (\d+) checks, (\d+) rejection cases", output)
        assert len(totals) == 1, totals
        total_checks, total_rejections = map(int, totals[0])
        assert total_rejections == sum(case["rejections"] for case in cases.values())
        assert total_checks == 1 + sum(case["checks"] for case in cases.values())
        assert original == fingerprints(), "production profile changed during test-only run"
        report.update(passed=True, checks=total_checks, rejections=total_rejections,
                      harness_checks=1, negative=cases["negative"],
                      production_unchanged=True)
        report_path.write_text(json.dumps(report, indent=2) + "\n")
        print(f"Reports: {report_path} ; {log_path}", flush=True)


if __name__ == "__main__":
    main()
