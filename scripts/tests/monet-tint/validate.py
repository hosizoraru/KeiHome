#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Build and run host tint checks using an external ARM64 libapp.so."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import shlex
import shutil
import subprocess
import sys
import tempfile

TEST_DIR = Path(__file__).resolve().parent
REPO = TEST_DIR.parents[2]
CPP = REPO / "app/src/main/cpp"
API = 37


def ndk_toolchain():
    """Prefer an explicit NDK, otherwise find the newest installed SDK NDK."""
    explicit = os.environ.get("NDK_DIR")
    if explicit:
        candidates = [Path(explicit).expanduser().resolve()]
    else:
        sdks = []
        for key in ("ANDROID_SDK_ROOT", "ANDROID_HOME"):
            if os.environ.get(key):
                sdks.append(Path(os.environ[key]).expanduser())
        properties = REPO / "local.properties"
        if properties.is_file():
            for line in properties.read_text().splitlines():
                if line.strip().startswith("sdk.dir="):
                    value = line.split("=", 1)[1].strip()
                    value = value.replace("\\:", ":").replace("\\ ", " ").replace("\\\\", "\\")
                    sdk = Path(value).expanduser()
                    sdks.append(sdk if sdk.is_absolute() else REPO / sdk)
        sdks.extend((Path.home() / "Library/Android/sdk", Path.home() / "Android/Sdk"))
        candidates = {p.resolve() for sdk in sdks for p in (sdk / "ndk").glob("*") if p.is_dir()}
        candidates = sorted(candidates, key=lambda p: tuple(int(n) for n in re.findall(r"\d+", p.name)), reverse=True)
    host = {"Darwin": "darwin", "Linux": "linux"}.get(platform.system())
    if host is None:
        raise RuntimeError("This host runner supports macOS and Linux.")
    for ndk in candidates:
        for prebuilt in sorted((ndk / "toolchains/llvm/prebuilt").glob(host + "-*")):
            tools = prebuilt / "bin"
            required = [f"aarch64-linux-android{API}-clang", f"aarch64-linux-android{API}-clang++", "ld.lld", "llvm-objdump"]
            if all((tools / name).is_file() and os.access(tools / name, os.X_OK) for name in required):
                return ndk, tools
    raise RuntimeError(f"No usable NDK with Android API {API} wrappers; set NDK_DIR to an installed NDK root.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--library", type=Path, required=True, help="External extracted libapp.so for dynamic resolution; never copied")
    parser.add_argument("--output", type=Path, help="Build/report directory; defaults to a new system temporary directory")
    emulation = parser.add_mutually_exclusive_group()
    emulation.add_argument("--require-emulation", action="store_true", help="Fail if optional emulation dependencies are missing (recommended in CI)")
    emulation.add_argument("--skip-emulation", action="store_true", help="Explicitly skip the 69 machine-code cases; still compile and test the resolver")
    args = parser.parse_args()
    if not __debug__:
        parser.error("Run Python without -O/PYTHONOPTIMIZE so test assertions stay enabled")
    library = args.library.expanduser().resolve()
    if not library.is_file():
        parser.error(f"External library not found: {library}")
    try:
        ndk, toolchain = ndk_toolchain()
    except RuntimeError as error:
        parser.error(str(error))
    compiler = os.environ.get("CXX") or shutil.which("clang++") or shutil.which("c++")
    if not compiler:
        parser.error("No host C++ compiler; set CXX to a compiler executable")
    output = args.output.expanduser().resolve() if args.output else Path(tempfile.mkdtemp(prefix="keihome-monet-tint-"))
    output.mkdir(parents=True, exist_ok=True)
    log = output / "commands.log"
    log.write_text("")
    print(f"Repository: {REPO}\nOutput: {output}\nNDK: {ndk}", flush=True)

    def run(command, allowed=(0,)):
        command = [str(arg) for arg in command]
        completed = subprocess.run(command, cwd=REPO, capture_output=True, text=True)
        with log.open("a") as stream:
            stream.write("$ " + shlex.join(command) + "\n")
            stream.write(completed.stdout + completed.stderr)
            stream.write(f"[exit {completed.returncode}]\n\n")
        if completed.stdout:
            print(completed.stdout, end="", flush=True)
        if completed.stderr:
            print(completed.stderr, end="", file=sys.stderr, flush=True)
        if completed.returncode not in allowed:
            raise RuntimeError(f"Command exited {completed.returncode}; details: {log}")
        return completed

    record = {
        "library": str(library),
        "library_sha256": hashlib.sha256(library.read_bytes()).hexdigest(),
        "python": sys.executable,
        "ndk": str(ndk),
        "android_abi": f"arm64-v8a, API {API}",
        "source_sha256": {},
        "overall": "failed",
    }
    sources = [CPP / name for name in (
        "monet_tint_bridge.S", "monet_tint_profile.h", "monet_tint_profile.cpp", "monet_tint_patterns.h",
        "monet_image_identity.h", "monet_image_identity.cpp", "monet_policy_server.h",
        "monet_hooks.cpp", "monet_policy_server.cpp",
    )]
    sources.extend(p for p in TEST_DIR.iterdir() if p.is_file())
    record["source_sha256"] = {str(p.relative_to(REPO)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(sources)}
    try:
        print("[1/3] Host resolver: dynamic graph and rejected ABI changes", flush=True)
        resolver = output / "test-resolver"
        run([compiler, "-std=c++17", "-UNDEBUG", "-O2", "-Wall", "-Wextra", "-Werror", "-I", CPP,
             TEST_DIR / "test_resolver.cpp", CPP / "monet_tint_profile.cpp",
             CPP / "monet_image_identity.cpp", "-o", resolver])
        result = run([resolver, "--library", library])
        record["resolver"] = json.loads(result.stdout.splitlines()[-1])
        assert record["resolver"]["resolver_rejections"] >= 17

        layout = output / "resolved-layout.json"
        layout.write_text(result.stdout.splitlines()[-1] + "\n")

        print("[2/3] Android compilation and standalone bridge link", flush=True)
        flags = ["-std=c++17", "-Wall", "-Wextra", "-Werror", "-fno-exceptions", "-fno-rtti",
                 "-fno-threadsafe-statics", "-fvisibility=hidden", "-fvisibility-inlines-hidden",
                 "-ffunction-sections", "-fdata-sections", "-mbranch-protection=standard"]
        for name in ("monet_tint_profile", "monet_image_identity", "monet_hooks", "monet_policy_server"):
            run([toolchain / f"aarch64-linux-android{API}-clang++", *flags,
                 "-c", CPP / (name + ".cpp"), "-o", output / (name + ".o")])
        bridge_object, symbols_object = output / "bridge.o", output / "test-symbols.o"
        run([toolchain / f"aarch64-linux-android{API}-clang", "-mbranch-protection=standard",
             "-c", CPP / "monet_tint_bridge.S", "-o", bridge_object])
        run([toolchain / f"aarch64-linux-android{API}-clang", "-c", TEST_DIR / "test_symbols.S", "-o", symbols_object])
        elf = output / "bridge-test.elf"
        run([toolchain / "ld.lld", "-Ttext=0x100000", "-e", "kh_monet_tint_bridge",
             bridge_object, symbols_object, "-o", elf])
        record["android_compile"] = "passed (including monet_hooks.cpp and monet_policy_server.cpp)"

        print("[3/3] Optional AArch64 machine-code emulation", flush=True)
        report = output / "bridge-tests.json"
        if args.skip_emulation:
            record["emulation"] = {"status": "skipped", "reason": "--skip-emulation"}
        else:
            result = run([sys.executable, TEST_DIR / "test_bridges.py", "--elf", elf, "--report", report, "--layout", layout], allowed=(0, 77))
            if result.returncode == 77:
                record["emulation"] = {"status": "skipped", "reason": "optional dependencies unavailable", "diagnostic": result.stderr.strip()}
            else:
                details = json.loads(report.read_text())
                assert details["unicorn_cases"] == 69 and details["all_passed"]
                record["emulation"] = {"status": "passed", "cases": details["unicorn_cases"]}
        if record["emulation"]["status"] == "skipped":
            report.write_text(json.dumps(record["emulation"], indent=2) + "\n")
            if args.require_emulation:
                raise RuntimeError("Emulation was required but its dependencies are unavailable")
            record["overall"] = "partial"
            print("PARTIAL: resolver and Android compilation passed; machine-code emulation SKIPPED.", flush=True)
        else:
            record["overall"] = "passed"
            print("PASS: dynamic resolver rejection cases and 69 AArch64 machine-code cases.", flush=True)
        return 0
    except (OSError, RuntimeError, AssertionError, ValueError) as error:
        record["error"] = str(error)
        print(f"FAIL: {error}", file=sys.stderr)
        return 1
    finally:
        receipt = output / "validation.json"
        receipt.write_text(json.dumps(record, indent=2) + "\n")
        print(f"Validation receipt: {receipt}", flush=True)


if __name__ == "__main__":
    sys.exit(main())
