#!/usr/bin/env python3
"""Exercise light/dark switching and navigation on the authorized HyperOS AVD."""
import argparse
import importlib.util
import json
from pathlib import Path
import re
import subprocess
import time
import uuid

SERIAL = "emulator-5580"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence", type=Path, required=True)
    parser.add_argument("--cycles", type=int, default=4)
    parser.add_argument("--wallpaper-fixture", action="store_true",
                        help="Alternate green/original wallpapers using prepare-wallpaper-fixture.py")
    parser.add_argument("--phase", choices=("combined", "night", "wallpaper", "navigation"),
                        default="combined")
    args = parser.parse_args()
    if not 1 <= args.cycles <= 20:
        parser.error("cycles must be in 1..20")
    if args.phase == "wallpaper" and not args.wallpaper_fixture:
        parser.error("wallpaper phase requires --wallpaper-fixture")
    args.evidence.mkdir(parents=True, exist_ok=True)
    spec = importlib.util.spec_from_file_location(
        "deploy", Path(__file__).with_name("deploy-hyperos-avd.py"))
    deploy = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(deploy)
    if "KeiOS_HyperOS4_Phone" not in deploy.adb("emu", "avd", "name").splitlines():
        raise RuntimeError("Not the authorized HyperOS AVD")
    deploy.root("input -d 0 keyevent WAKEUP")
    deploy.root("wm dismiss-keyguard")
    deadline = time.monotonic() + 5
    while True:
        keyguard = deploy.adb("shell", "dumpsys", "window", "policy")
        delegate = keyguard.split("KeyguardServiceDelegate", 1)[-1]
        if re.search(r"\bshowing=false\b", delegate) or time.monotonic() >= deadline:
            break
        time.sleep(0.2)
    (args.evidence / "window-policy-before.txt").write_text(keyguard)
    if not re.search(r"\bshowing=false\b", delegate):
        raise RuntimeError("Unlock the AVD before testing foreground/background transitions")
    processes = ("com.miui.home", "system_server", "com.android.systemui")
    before = {name: deploy.process(name) for name in processes}
    initial = deploy.adb("shell", "cmd", "uimode", "night").split(": ")[-1]
    if initial not in ("yes", "no", "auto", "custom"):
        raise RuntimeError(f"Unknown night mode {initial!r}")
    started = deploy.adb("shell", "date '+%m-%d %H:%M:%S.000'")
    actions = []
    fixture_log = None
    fixture_output = None
    if args.wallpaper_fixture:
        deploy.adb("shell", "pm", "path", "os.kei.home.wallpaper.fixture")
        fixture_output = (args.evidence / "wallpaper-fixture.log").open("w")
        fixture_log = subprocess.Popen(
            ["adb", "-s", SERIAL, "logcat", "-v", "threadtime", "-T", "1",
             "-s", "KeiWallpaperFixture:I"], stdout=fixture_output, stderr=subprocess.STDOUT)

    def command(*command_args):
        start = time.monotonic()
        action = {"command": command_args}
        try:
            result = subprocess.run(["adb", "-s", SERIAL, *command_args],
                                    capture_output=True, text=True, timeout=15, check=True)
            action["output"] = result.stdout.strip()
        except (subprocess.TimeoutExpired, subprocess.CalledProcessError) as error:
            action["error"] = str(error)
            raise
        finally:
            action["seconds"] = round(time.monotonic() - start, 3)
            actions.append(action)

    def wallpaper(mode):
        log_path = args.evidence / "wallpaper-fixture.log"
        request = uuid.uuid4().hex
        command("shell", "am", "start", "-W", "-n", "os.kei.home.wallpaper.fixture/.Main",
                "--es", "mode", mode, "--es", "request", request)
        deadline = time.monotonic() + 20
        while not any("Applied wallpaper id=" in line and f"request={request} mode={mode}" in line
                      for line in log_path.read_text().splitlines()):
            if time.monotonic() >= deadline:
                raise RuntimeError("Wallpaper fixture did not acknowledge the change")
            time.sleep(0.1)

    def foreground(package):
        start = time.monotonic()
        resumed = None
        while time.monotonic() - start < 5:
            result = subprocess.run(
                ["adb", "-s", SERIAL, "shell", "dumpsys", "activity", "activities"],
                capture_output=True, text=True, timeout=5, check=True)
            display = result.stdout.split("Display #0", 1)[-1].split("Display #", 1)[0]
            match = re.search(r"topResumedActivity=ActivityRecord\{\S+ u\d+ ([^/\s]+)/", display)
            resumed = match[1] if match else None
            if resumed == package:
                actions.append({"foreground": package, "seconds": round(time.monotonic() - start, 3)})
                return
            time.sleep(0.2)
        (args.evidence / "foreground-timeout.txt").write_text(result.stdout)
        raise RuntimeError(f"Display 0 did not resume {package}; observed {resumed}")

    problem = None
    try:
        for cycle in range(args.cycles):
            for mode in ("no", "yes"):
                if args.wallpaper_fixture and args.phase in ("combined", "wallpaper"):
                    wallpaper("green" if mode == "no" else "restore")
                if args.phase in ("combined", "night"):
                    command("shell", "cmd", "uimode", "night", mode)
                time.sleep(0.7 if args.wallpaper_fixture else 2)
                if args.phase in ("combined", "navigation"):
                    command("shell", "am", "start", "-n", "os.kei.hyper.home/.MainActivity")
                    foreground("os.kei.hyper.home")
                    time.sleep(0.4)
                    command("shell", "su -c 'input -d 0 keyevent APP_SWITCH'")
                    time.sleep(0.3)
                    command("shell", "su -c 'input -d 0 keyevent HOME'")
                    foreground("com.miui.home")
                time.sleep(0.5)
            current = {name: deploy.process(name) for name in processes}
            if current != before:
                raise RuntimeError(f"Process changed in cycle {cycle + 1}: {current}")
            print(f"Cycle {cycle + 1}: identities unchanged", flush=True)
    except Exception as error:
        problem = str(error)
    finally:
        try:
            if args.wallpaper_fixture:
                wallpaper("restore")
        except Exception as error:
            problem = f"{problem or ''} Restoration failed: {error}"
        finally:
            try:
                command("shell", "cmd", "uimode", "night", initial)
            except Exception as error:
                problem = f"{problem or ''} Night mode restoration failed: {error}"
            (args.evidence / "actions.json").write_text(json.dumps(actions, indent=2))
            if fixture_log:
                fixture_log.terminate()
                fixture_log.wait(timeout=5)
                fixture_output.close()
    time.sleep(12)
    after = {}
    for name in processes:
        try:
            after[name] = deploy.process(name)
        except Exception as error:
            after[name] = {"inspectionError": str(error)}
    events = deploy.adb("logcat", "-b", "events", "-d", "-v", "threadtime", "-T", started)
    (args.evidence / "events.txt").write_text(events)
    failures = [line for line in events.splitlines()
                if re.search(r"\bwatchdog\s*:", line)
                or (re.search(r"\bam_anr\s*:|\bam_crash\s*:", line)
                    and any(name in line for name in (*processes, "os.kei.hyper.home")))]
    scout = deploy.adb("logcat", "-d", "-v", "threadtime", "-T", started,
                       "-s", "ScoutManager:W", "MIUIScout App:W", "MiuiVolumeDialogMotion:W")
    (args.evidence / "scout.txt").write_text(scout)
    failures.extend(line for line in scout.splitlines() if "APP_SCOUT_HANG" in line and
                    any(name in line for name in (*processes, "os.kei.hyper.home")))
    if problem:
        failures.append(problem)
    actual_mode = deploy.adb("shell", "cmd", "uimode", "night").split(": ")[-1]
    if actual_mode != initial:
        failures.append(f"Night mode {actual_mode} differs from original {initial}")
    report = {"serial": SERIAL, "initialNightMode": initial, "nightModeAfter": actual_mode, "before": before,
              "after": after, "cycles": args.cycles, "phase": args.phase, "events": failures}
    if args.wallpaper_fixture:
        fixture_text = (args.evidence / "wallpaper-fixture.log").read_text()
        report["wallpaperApplications"] = fixture_text.count("Applied wallpaper id=")
        if "Failed" in fixture_text or (args.phase in ("combined", "wallpaper") and
                                         report["wallpaperApplications"] < args.cycles * 2):
            failures.append("Wallpaper fixture did not acknowledge every requested change")
    (args.evidence / "continuity.json").write_text(json.dumps(report, indent=2))
    if before != after or failures:
        raise RuntimeError(f"Continuity check failed: {report}")
    print("No target restart or ANR/crash event in this run", flush=True)


if __name__ == "__main__":
    main()
