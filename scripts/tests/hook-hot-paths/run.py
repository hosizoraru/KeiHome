#!/usr/bin/env python3
"""Compile exact production helpers. --serial additionally runs isolated ART probes."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import shlex
import subprocess

ROOT = Path(__file__).resolve().parents[3]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--out", type=Path, required=True)
parser.add_argument("--serial")
args = parser.parse_args()
out = args.out.resolve()
out.mkdir(parents=True, exist_ok=True)
sdk = Path(os.environ.get("ANDROID_HOME", str(Path.home() / "Library/Android/sdk")))
android = sdk / "platforms/android-37.2/android.jar"
kotlinc = Path(shutil.which("kotlinc")).resolve()
stdlib = next(p for p in (
    kotlinc.parent.parent / "lib/kotlin-stdlib.jar",
    kotlinc.parent.parent / "libexec/lib/kotlin-stdlib.jar") if p.is_file())
classes = out / "classes"
classes.mkdir(exist_ok=True)
source = ROOT / "app/src/main/kotlin/os/kei/hyper/home/xposed"
sources = [source / f"{name}.kt" for name in ("SurfaceIconKeyIndex", "NativeMonetPlatformMembers")]
sources += [Path(__file__).resolve().parent / f"{name}.java" for name in ("KeyBenchmark", "PlatformMembersProbe")]
commands = []


def run(command, log):
    commands.append(command)
    result = subprocess.run(command, cwd=ROOT, text=True, capture_output=True)
    (out / log).write_text(result.stdout + result.stderr)
    result.check_returncode()


try:
    run(["kotlinc", *map(str, sources[:2]), "-cp", str(android), "-d", str(classes)], "compile-kotlin.log")
    classpath = os.pathsep.join(map(str, (classes, stdlib, android)))
    run(["javac", "--release", "8", "-cp", classpath, "-d", str(classes),
         *map(str, sources[2:])], "compile-java.log")
    run(["java", "-cp", os.pathsep.join(map(str, (classes, stdlib))),
         "os.kei.hyper.home.xposed.KeyBenchmark"], "host.jsonl")
    if args.serial:
        run(["jar", "cf", str(out / "classes.jar"), "-C", str(classes), "."], "jar.log")
        dex = out / "dex"
        dex.mkdir(exist_ok=True)
        run([str(sdk / "build-tools/37.0.0/d8"), "--min-api", "29", "--lib", str(android),
             "--output", str(dex), str(out / "classes.jar"), str(stdlib)], "d8.log")
        run(["jar", "cf", str(out / "probe.jar"), "-C", str(dex), "."], "dex-jar.log")
        remote = f"/data/local/tmp/keihome-hot-path-{os.getpid()}.jar"
        adb = ["adb", "-s", args.serial]
        try:
            run([*adb, "push", str(out / "probe.jar"), remote], "push.log")
            for name in ("KeyBenchmark", "PlatformMembersProbe"):
                command = f"CLASSPATH={remote} app_process /system/bin os.kei.hyper.home.xposed.{name}"
                run([*adb, "shell", "su -c " + shlex.quote(command)], f"art-{name}.log")
        finally:
            run([*adb, "shell", "rm", "-f", remote], "remove-probe.log")
finally:
    (out / "manifest.json").write_text(json.dumps({
        "commands": commands,
        "sha256": {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest()
                   for p in sources},
        "limits": "Isolated unminified helpers, not whole-app/frame/battery measurements",
    }, indent=2) + "\n")
