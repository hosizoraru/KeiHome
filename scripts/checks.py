#!/usr/bin/env python3
"""Preview or run a deliberately selected check group; never build/install device APKs."""

import argparse
from pathlib import Path
import re
import shlex
import subprocess
import sys


ROOT = Path(__file__).resolve().parents[1]
UI = "os.kei.hyper.home.ui.main."
GROUPS = {
    "ui-actions": ("device", "FABs, disclosures, links and metadata pills", (
        UI + "UiActionPolishDeviceTest",)),
    "ui-navigation": ("device", "Both navbar styles, tabs, themes and large text", (
        UI + "Os4ChromeDeviceTest",)),
    "ui-status": ("device", "Status cards, disclosures and detail navigation", (
        UI + "MonetStatusContentTest",)),
    "ui-theme-effects": ("device", "Theme-effect controls and scope availability", (
        UI + "ThemeEffectsCardTest",)),
    "ui-color-editor": ("device", "Color input, confirmation and draft recreation", (
        UI + "MonetColorEditorTest",)),
    "ui-settings": ("jvm", "Appearance preferences and recovery", (
        "ThemeEffectsPreferencesTest", "ThemeEffectsRecoveryTest", "ThemeEffectsScopeTest")),
    "pack-time": ("jvm", "Compact dates, locales, time zones and year boundaries", (
        "PackEventTimeTest",)),
    "perfect-sync": ("jvm", "Sync history, health, cached source and cancellation", (
        "PerfectSyncHistoryTest", "PerfectIconSyncHealthTest", "PerfectLocalHealthProbeTest",
        "PerfectIconRepositoryTest", "PerfectIconTransportCancellationTest")),
    "scope": ("jvm", "Scope authorization requests and missing targets", (
        "StaticScopeRequestsTest",)),
    "hook-lifecycle": ("jvm", "Generation retirement bookkeeping (no device action)", (
        "ModuleRetirementMaintenanceTest",)),
    "release-jvm": ("jvm", "Full JVM suite; opt in for broad changes or releases", ()),
}


def command_for(group, serial, method):
    kind, _, classes = GROUPS[group]
    if method and len(classes) != 1:
        raise ValueError("--method requires a group containing exactly one test class")
    if kind == "device":
        target = ",".join(classes) + (f"#{method}" if method else "")
        return ["adb", "-s", serial or "DEVICE_SERIAL", "shell", "am", "instrument", "-w", "-r",
                "-e", "class", target,
                "os.kei.hyper.home.test/androidx.test.runner.AndroidJUnitRunner"]
    command = ["./gradlew", ":app:testDebugUnitTest"]
    for name in classes:
        command += ["--tests", f"*{name}" + (f".{method}" if method else "")]
    return command + ["--console=plain"]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("group", nargs="?", choices=GROUPS)
    parser.add_argument("--list", action="store_true", help="list the available groups")
    parser.add_argument("--run", action="store_true", help="execute; default is preview only")
    parser.add_argument("--serial", help="explicit adb target, required for device execution")
    parser.add_argument("--method", help="one test method in a single-class group")
    args = parser.parse_args()
    if args.list or args.group is None:
        for name, (kind, description, _) in GROUPS.items():
            print(f"{name:18} {kind:6} {description}")
        return 0
    try:
        command = command_for(args.group, args.serial, args.method)
    except ValueError as error:
        parser.error(str(error))
    print(shlex.join(command), flush=True)
    if not args.run:
        return 0
    if GROUPS[args.group][0] == "device" and not args.serial:
        parser.error("device execution requires --serial; no target is selected automatically")
    if GROUPS[args.group][0] == "jvm":
        return subprocess.run(command, cwd=ROOT).returncode
    result = subprocess.run(command, cwd=ROOT, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, text=True)
    print(result.stdout, end="", flush=True)
    # adb itself can exit zero even when the instrumentation runner reports failure.
    passed = re.search(r"^OK \(([1-9][0-9]*) tests?\)\s*$", result.stdout, re.MULTILINE)
    return 0 if result.returncode == 0 and passed and "FAILURES!!!" not in result.stdout else 1


if __name__ == "__main__":
    sys.exit(main())
