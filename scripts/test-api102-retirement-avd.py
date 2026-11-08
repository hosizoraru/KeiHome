#!/usr/bin/env python3
"""Run the opt-in Java retirement checks on the Phone AVD, never auto-retrying."""
import argparse
import json
import re
import subprocess
from pathlib import Path

from hyperos_avd_health import SERIAL, check_health

RESULT = "/data/user/0/os.kei.hyper.home/cache/module-retirement-reloads.json"
CLASSES = ("os.kei.hyper.home.DeferredProfileInstallerTest,"
           "os.kei.hyper.home.xposed.ModuleRetirementDeviceTest")


def validate_completion(output):
    # am instrument can exit 0 after the target has died before any method ran.
    passed = re.search(r"\bOK \((\d+) tests?\)", output)
    if (not passed or int(passed[1]) < 4 or "INSTRUMENTATION_CODE: -1" not in output or
            "Process crashed" in output or "FAILURES!!!" in output):
        raise RuntimeError("Instrumentation did not complete the requested tests")


def validate_result(output, events):
    validate_completion(output)
    expected = {(round_, process) for round_ in (1, 2)
                for process in ("system", "com.android.systemui", "com.android.thememanager")}
    if (not isinstance(events, list) or len(events) != len(expected) or
            any(not isinstance(item, dict) for item in events) or
            {(item.get("round"), item.get("process")) for item in events} != expected or
            any(item.get("status") != "SUCCEEDED" for item in events)):
        raise RuntimeError("Missing two fresh successful rounds for all three Java hosts")


def main(evidence):
    evidence.mkdir(parents=True, exist_ok=True)
    check_health(evidence / "before")

    def adb(command, timeout):
        try:
            result = subprocess.run(["adb", "-s", SERIAL, "shell", command],
                                    capture_output=True, text=True, errors="replace", timeout=timeout)
        except subprocess.TimeoutExpired as error:
            (evidence / "interrupted-output.txt").write_bytes(error.stdout or b"")
            (evidence / "timeout.json").write_text(
                json.dumps({"command": command, "timeoutSeconds": timeout}, indent=2) + "\n")
            raise RuntimeError("Probe timed out; partial evidence saved, no automatic retry") from error
        if result.returncode:
            raise RuntimeError(result.stderr[:500] or result.stdout[:500])
        return result.stdout

    preparation = []
    for package in ("os.kei.hyper.home", "os.kei.hyper.home.test"):
        # The unminified instrumented target has two APK class loaders. Prepare ART
        # explicitly; this is not a change to system Provider/Watchdog deadlines.
        output = adb(f"cmd package compile -m speed {package}", 90)
        preparation.append({"package": package, "output": output})
        if "Success" not in output:
            raise RuntimeError("ART preparation failed; did not start instrumentation")
    (evidence / "art-preparation.json").write_text(json.dumps(preparation, indent=2) + "\n")
    adb(f"su -c 'rm -f {RESULT}'", 5)
    output = adb(
        f"am instrument -w -r -e retirementReload true -e class {CLASSES} "
        "os.kei.hyper.home.test/androidx.test.runner.AndroidJUnitRunner", 140)
    (evidence / "instrumentation.log").write_text(output)
    # Validate completion before reading private test output or issuing more probes.
    validate_completion(output)
    raw = adb(f"su -c 'cat {RESULT}'", 5)
    (evidence / "reload-results.json").write_text(raw)
    validate_result(output, json.loads(raw))
    check_health(evidence / "after")
    print("Four device tests and six Java handoffs passed; no native reload was requested")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence", required=True, type=Path)
    main(parser.parse_args().evidence)
