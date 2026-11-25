#!/usr/bin/env python3
"""Collect evidence for a HyperOS Runtime launcher after a framework upgrade."""

import argparse
import json
from pathlib import Path
import re
import subprocess
import sys
import time


NATIVE_LOAD = "Loaded HyperOS Runtime native module os.kei.hyper.home (libkeihome_hyos_lsp.so)"
HOME_FOCUS = re.compile(r"mCurrentFocus=Window\{[^}]*\bu0 com\.miui\.home/")
LAUNCHER_CRASH = re.compile(
    r"(?:\bFatal signal \d+.*\(com\.miui\.home\)(?:,|\s|$)"
    r"|\bCmdline: com\.miui\.home\s*$|\bProcess: com\.miui\.home,\s+PID:)"
)
VERBOSE_PID = re.compile(r"^\[[^]]+:\s*(\d+):\s*\d+\s+\w/LSPosed\s*\]")
LOGCAT_PID = re.compile(r"^\d\d-\d\d\s+\S+\s+(\d+)\s+\d+\s+\w\s+LSPosed\s*:")


def native_loaded(logs, pid):
    for line in logs.splitlines():
        if NATIVE_LOAD in line:
            header = VERBOSE_PID.match(line) or LOGCAT_PID.match(line)
            if header and header[1] == pid:
                return True
    return False


def assess(before, after, crash_log, native_log, expected):
    failures = []
    for name, value in expected.items():
        if before[name] != value:
            failures.append(f"Expected {name} {value}, got {before[name]}")
    if before != after:
        failures.append("Boot, package, framework or launcher state changed during capture")
    if before["completed"] != "1" or before["user"] != "RUNNING_UNLOCKED":
        failures.append("The current boot has not reached an unlocked user 0")
    if not before["foreground"]:
        failures.append("HOME is not the focused window on the primary display")
    if before["launcherExecutable"] != "/system_ext/bin/hyos_spawner":
        failures.append("No live launcher running in the HyperOS native Runtime")
    crash_lines = [line for line in crash_log.splitlines() if LAUNCHER_CRASH.search(line)]
    if crash_lines:
        failures.append("The captured crash buffer contains a main launcher crash")
    if not native_loaded(native_log, before["launcherPid"]):
        failures.append("No KeiHome native load evidence for the current launcher PID")
    return {"passed": not failures, "failures": failures, "launcherCrashLines": crash_lines}


def collect(args):
    def adb(*command):
        return subprocess.run(
            ["adb", "-s", args.serial, *command], check=True, capture_output=True, timeout=25,
        ).stdout.decode("utf-8", errors="replace")

    def save(name, text):
        (args.evidence / name).write_text(text)

    def snapshot():
        identity = adb(
            "shell",
            "cat /proc/sys/kernel/random/boot_id; getprop sys.boot_completed; "
            "cmd activity get-started-user-state 0; getprop ro.build.version.incremental; "
            "pid=$(pidof -s com.miui.home); echo \"${pid:-none}\"; "
            "su -c \"readlink /proc/${pid:-0}/exe\" || echo none; "
            "su -c 'grep -E \"^version(Code)?=\" /data/adb/modules/zygisk_lsposed/module.prop'; "
            "dumpsys package com.miui.home | grep -m 1 'versionCode='; "
            "dumpsys package os.kei.hyper.home | grep -m 1 'versionCode='",
        )
        windows = adb("shell", "dumpsys", "window", "displays")
        primary = re.search(r"Display: mDisplayId=0\b(.*?)(?=Display: mDisplayId=|\Z)", windows, re.S)
        boot, completed, user, hyperos, pid, executable, *metadata = identity.splitlines()
        versions = re.findall(r"(?m)^\s*versionCode=(\d+)\b", "\n".join(metadata))
        framework_name = re.search(r"(?m)^version=(.+)$", "\n".join(metadata))
        if len(versions) != 3 or not framework_name:
            raise ValueError("Cannot identify framework, launcher and KeiHome package versions")
        framework_code, launcher_code, keihome_code = versions
        return {
            "bootId": boot,
            "completed": completed,
            "user": user,
            "hyperos": hyperos,
            "launcherPid": pid,
            "launcherExecutable": executable,
            "foreground": bool(primary and HOME_FOCUS.search(primary[1])),
            "frameworkCode": framework_code,
            "frameworkName": framework_name[1],
            "launcherCode": launcher_code,
            "keihomeCode": keihome_code,
        }

    args.evidence.mkdir(parents=True)
    before = snapshot()
    save("before.json", json.dumps(before, indent=2) + "\n")
    time.sleep(args.observe_seconds)
    after = snapshot()
    save("after.json", json.dumps(after, indent=2) + "\n")
    crash = adb("logcat", "-b", "crash", "-d", "-v", "threadtime")
    save("crash.txt", crash)
    native = ""
    if re.fullmatch(r"\d+", after["launcherPid"]):
        native = adb(
            "logcat", "-d", "-v", "threadtime", "--pid", after["launcherPid"], "-s", "LSPosed:I",
        )
    if not native_loaded(native, after["launcherPid"]):
        native += adb(
            "exec-out", "su", "-c",
            "grep -hF 'Loaded HyperOS Runtime native module os.kei.hyper.home "
            "(libkeihome_hyos_lsp.so)' /data/adb/lspd/log/verbose*.log 2>/dev/null || true",
        )
    save("native-load.txt", native)
    if snapshot() != after:
        raise ValueError("Device state changed while collecting crash and native load evidence")
    return assess(before, after, crash, native, {
        "frameworkName": args.framework_version,
        "keihomeCode": args.keihome_code,
    })


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--framework-version", required=True, help="Exact module.prop version")
    parser.add_argument("--keihome-code", required=True)
    parser.add_argument("--evidence", type=Path, required=True, help="New directory")
    parser.add_argument("--observe-seconds", type=int, choices=range(1, 61), default=20, metavar="1-60")
    args = parser.parse_args()
    try:
        report = collect(args)
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        print(f"Evidence collection failed: {error}", file=sys.stderr)
        return 2
    output = json.dumps(report, indent=2)
    (args.evidence / "report.json").write_text(output + "\n")
    print(output)
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
