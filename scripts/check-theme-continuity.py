#!/usr/bin/env python3
"""Exercise light/dark switching and navigation on the authorized HyperOS AVD."""
import argparse
import importlib.util
import json
from pathlib import Path
import re
import subprocess
import time

SERIAL = "emulator-5580"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence", type=Path, required=True)
    parser.add_argument("--cycles", type=int, default=4)
    args = parser.parse_args()
    if not 1 <= args.cycles <= 20:
        parser.error("cycles must be in 1..20")
    args.evidence.mkdir(parents=True, exist_ok=True)
    spec = importlib.util.spec_from_file_location(
        "deploy", Path(__file__).with_name("deploy-hyperos-avd.py"))
    deploy = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(deploy)
    if "KeiOS_HyperOS4_Phone" not in deploy.adb("emu", "avd", "name").splitlines():
        raise RuntimeError("Not the authorized HyperOS AVD")
    processes = ("com.miui.home", "system_server", "com.android.systemui")
    before = {name: deploy.process(name) for name in processes}
    initial = deploy.adb("shell", "cmd", "uimode", "night").split(": ")[-1]
    if initial not in ("yes", "no", "auto", "custom"):
        raise RuntimeError(f"Unknown night mode {initial!r}")
    started = deploy.adb("shell", "date '+%m-%d %H:%M:%S.000'")
    actions = []

    def command(*command_args):
        start = time.monotonic()
        result = subprocess.run(["adb", "-s", SERIAL, *command_args],
                                capture_output=True, text=True, timeout=15, check=True)
        actions.append({"command": command_args, "seconds": round(time.monotonic() - start, 3),
                        "output": result.stdout.strip()})

    try:
        for cycle in range(args.cycles):
            for mode in ("no", "yes"):
                command("shell", "cmd", "uimode", "night", mode)
                time.sleep(2)
                command("shell", "am", "start", "-n", "os.kei.hyper.home/.MainActivity")
                time.sleep(0.5)
                command("shell", "su -c 'input -d 0 keyevent APP_SWITCH'")
                time.sleep(0.5)
                command("shell", "su -c 'input -d 0 keyevent HOME'")
                time.sleep(1)
            current = {name: deploy.process(name) for name in processes}
            if current != before:
                raise RuntimeError(f"Process changed in cycle {cycle + 1}: {current}")
            print(f"Cycle {cycle + 1}: identities unchanged", flush=True)
    finally:
        command("shell", "cmd", "uimode", "night", initial)
        (args.evidence / "actions.json").write_text(json.dumps(actions, indent=2))
    time.sleep(12)
    after = {name: deploy.process(name) for name in processes}
    events = deploy.adb("logcat", "-b", "events", "-d", "-v", "threadtime", "-T", started)
    (args.evidence / "events.txt").write_text(events)
    failures = [line for line in events.splitlines()
                if re.search(r"\bwatchdog\s*:", line)
                or (re.search(r"\bam_anr\s*:|\bam_crash\s*:", line)
                    and any(name in line for name in (*processes, "os.kei.hyper.home")))]
    report = {"serial": SERIAL, "nightModeRestored": initial, "before": before,
              "after": after, "cycles": args.cycles, "events": failures}
    (args.evidence / "continuity.json").write_text(json.dumps(report, indent=2))
    if before != after or failures:
        raise RuntimeError(f"Continuity check failed: {report}")
    print("No target restart or ANR/crash event in this run", flush=True)


if __name__ == "__main__":
    main()
