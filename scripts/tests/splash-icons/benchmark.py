#!/usr/bin/env python3
"""Repeat Settings launches after force-stop; preserve data and system processes."""
import argparse
import json
from pathlib import Path
import re
import statistics
import subprocess
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--runs", type=int, default=10)
    args = parser.parse_args()
    if not 1 <= args.runs <= 24:
        parser.error("--runs must be between 1 and 24")
    args.output.mkdir(parents=True, exist_ok=True)
    base = ["adb", "-s", args.serial]

    def adb(*command):
        return subprocess.check_output(base + list(command), text=True, timeout=45)

    def root(*command):
        return adb("shell", "su", "0", *command)

    critical = ("system_server", "com.android.systemui", "com.miui.home")
    # pidof can include a transient debuggerd child retaining the parent's argv.
    def identities():
        result = {}
        for name in critical:
            candidates = root("pidof", name).split()
            if len(candidates) == 1:
                result[name] = int(candidates[0])
                continue
            live = []
            for pid in candidates:
                try:
                    stat = root("cat", f"/proc/{int(pid)}/stat").rsplit(")", 1)[1].split()
                    if stat[0] != "Z":
                        live.append((int(stat[19]), int(pid)))
                except subprocess.CalledProcessError:
                    pass
            result[name] = min(live)[1]
        return result

    before = identities()
    identity = root("dumpsys", "package", "os.kei.hyper.home")
    (args.output / "package.txt").write_text(identity)
    rows = []
    with (args.output / "logcat.txt").open("w") as log:
        process = subprocess.Popen(base + ["logcat", "-b", "main", "-b", "system",
            "-v", "threadtime", "-T", "1"], stdout=log, stderr=subprocess.STDOUT)
        try:
            for index in range(args.runs):
                root("am", "force-stop", "com.android.settings")
                root("am", "start", "-W", "-a", "android.intent.action.MAIN",
                    "-c", "android.intent.category.HOME")
                time.sleep(1.5)
                root("log", "-t", "KeiHomeSplashBench", f"BEGIN-{index}")
                started = time.monotonic()
                text = root("am", "start", "-W", "-a", "android.intent.action.MAIN",
                    "-c", "android.intent.category.LAUNCHER", "-n",
                    "com.android.settings/.MainSettings")
                if "Status: ok" not in text:
                    raise RuntimeError(text)
                (args.output / f"launch-{index}.txt").write_text(text)
                launch_state = re.search(r"^LaunchState: (.+)$", text, re.M)
                rows.append({"run": index,
                    "launchState": launch_state[1] if launch_state else "unreported",
                    "commandMs": (time.monotonic() - started) * 1000,
                    **{key: int(value) for key, value in
                       re.findall(r"^(TotalTime|WaitTime|ThisTime): (\d+)", text, re.M)}})
                print(f"launch {index + 1}/{args.runs}", flush=True)
                time.sleep(0.3)
        finally:
            process.terminate()
            process.wait(timeout=5)
    after = identities()
    if before != after:
        raise RuntimeError(f"Critical process identities changed: {before} -> {after}")
    logs = (args.output / "logcat.txt").read_text()
    samples = [{"prepareUs": int(prepare), "buildUs": int(build)}
        for prepare, build in re.findall(
            r"KeiHomeSplashIcon[^\n]*package=com\.android\.settings .*prepareUs=(\d+) buildUs=(\d+)",
            logs)]
    result = {"serial": args.serial, "criticalPids": before,
        "launches": rows, "hookSamples": samples,
        "summary": {key: {"count": len(values), "median": statistics.median(values),
            "min": min(values), "max": max(values)}
            for key, values in {
                "prepareUs": [row["prepareUs"] for row in samples],
                "buildUs": [row["buildUs"] for row in samples],
                "totalTimeMs": [row["TotalTime"] for row in rows if "TotalTime" in row],
            }.items() if values}}
    (args.output / "results.json").write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result["summary"], indent=2))


if __name__ == "__main__":
    main()
