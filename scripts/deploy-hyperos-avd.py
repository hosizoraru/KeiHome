#!/usr/bin/env python3
"""Developer deployment: whole APK, increasing versions, explicit AVD only.

The default verifies Java hot reload without restarting any process.
--reload-native is an explicit development operation that replaces the exact
root HYOS spawner after Java hot reload succeeds. It is not module runtime code.
"""
import argparse
import json
from pathlib import Path
import re
import shlex
import subprocess
import time

ROOT = Path(__file__).resolve().parents[1]
SERIAL = "emulator-5580"
PACKAGE = "os.kei.hyper.home"


def adb(*args, timeout=45):
    return subprocess.check_output(["adb", "-s", SERIAL, *args], text=True, timeout=timeout).strip()


def root(command):
    return adb("shell", "su", "-c", shlex.quote(command))


def module_log_positions():
    positions = {}
    for line in root("stat -c '%n %i %s' /data/adb/lspd/log/modules_*.log").splitlines():
        path, inode, size = line.split()
        if not re.fullmatch(r"/data/adb/lspd/log/modules_[^/]+\.log", path):
            raise RuntimeError("Unexpected framework module log path")
        positions[path] = (int(inode), int(size))
    if not positions:
        raise RuntimeError("Missing framework module log")
    return positions


def module_log_delta(before):
    chunks = []
    for path, (inode, size) in module_log_positions().items():
        old_inode, old_size = before.get(path, (None, 0))
        # A dated new log, replaced inode, or truncated file starts a fresh stream.
        offset = old_size if inode == old_inode and size >= old_size else 0
        if size > offset:
            chunks.append(root(f"tail -c +{offset + 1} {shlex.quote(path)}"))
    return "\n".join(chunks)


def process(name):
    for attempt in range(12):
        raw = adb("shell", "pidof", name)
        candidates = []
        for value in raw.split():
            if not value.isdigit():
                continue
            pid = int(value)
            try:
                stat = root(f"cat /proc/{pid}/stat")
                fields = stat[stat.rfind(")") + 2:].split()
                if len(fields) <= 19 or fields[0] == "Z":
                    continue
                candidates.append({
                    "pid": pid,
                    "parent": int(fields[1]),
                    "start": int(fields[19]),
                })
            except (subprocess.CalledProcessError, ValueError, IndexError):
                # A debugger/crash_dump clone can disappear between pidof and /proc.
                continue
        if candidates:
            # The live application has the oldest start tick; transient debugger
            # clones are newer. This is evidence-based selection, not pidof order.
            return min(candidates, key=lambda item: item["start"])
        if attempt < 11:
            time.sleep(0.25)
    raise RuntimeError(f"Expected a live {name} process, got {raw!r}")


def spawner(parent):
    status = root(f"cat /proc/{parent}/status")
    if root(f"readlink /proc/{parent}/exe") != "/system_ext/bin/hyos_spawner" or not (
        re.search(r"^Uid:\s*0\s", status, re.M) and re.search(r"^PPid:\s*1$", status, re.M)
    ):
        raise RuntimeError("Launcher parent is not the exact owning root HYOS spawner")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--reload-native", action="store_true")
    parser.add_argument("--allow-downgrade", action="store_true",
                        help="Allow a lower code on the core-patched HyperOS test AVD")
    parser.add_argument("--evidence", required=True, type=Path)
    args = parser.parse_args()
    args.evidence.mkdir(parents=True, exist_ok=True)
    if "KeiOS_HyperOS4_Phone" not in adb("emu", "avd", "name").splitlines():
        raise RuntimeError("The selected serial is not the authorized HyperOS AVD")
    sdk = next(line.split("=", 1)[1] for line in (ROOT / "local.properties").read_text().splitlines()
               if line.startswith("sdk.dir="))
    aapt = Path(sdk) / "build-tools/37.0.0/aapt2"
    badging = subprocess.check_output([str(aapt), "dump", "badging", str(args.apk)], text=True)
    metadata = re.search(r"package: name='([^']+)' versionCode='(\d+)'", badging)
    if not metadata or metadata[1] != PACKAGE:
        raise RuntimeError("APK is not KeiHome")
    new_version = int(metadata[2])
    # `dumpsys package` can wait behind a framework dump on a busy development
    # AVD. PackageManager's compact listing provides the same version gate
    # without taking the global package-dump path.
    listed = adb("shell", "pm", "list", "packages", "--show-versioncode", PACKAGE)
    version_match = re.search(rf"package:{re.escape(PACKAGE)}\s+versionCode:(\d+)", listed)
    if not version_match:
        installed = adb("shell", "dumpsys", "package", PACKAGE, timeout=120)
        version_match = re.search(r"versionCode=(\d+)", installed)
    if not version_match:
        raise RuntimeError(f"Cannot read installed version for {PACKAGE}: {listed!r}")
    old_version = int(version_match[1])
    if new_version == old_version or (new_version < old_version and not args.allow_downgrade):
        raise RuntimeError(f"versionCode must increase: installed={old_version}, APK={new_version}")
    before = {name: process(name) for name in ("com.miui.home", "system_server", "com.android.systemui")}
    logs = module_log_positions()
    install_args = ["install", "-r"]
    if new_version < old_version:
        install_args.append("-d")
    result = adb(*install_args, "--no-streaming", str(args.apk.resolve()), timeout=120)
    if "Success" not in result:
        raise RuntimeError(result)
    print(f"Installed {old_version} -> {new_version}", flush=True)
    for _ in range(80):
        delta = module_log_delta(logs)
        if any(PACKAGE in line and "System-server icon refresh hooks installed" in line
               for line in delta.splitlines()):
            break
        time.sleep(0.25)
    else:
        raise RuntimeError("No fresh API 102 Java reconciliation; native deployment stopped")
    (args.evidence / "java-reload.log").write_text(delta)
    for name, identity in before.items():
        if process(name) != identity:
            raise RuntimeError(f"{name} changed during APK installation/hot reload")
    print("API 102 Java reload verified; process identities unchanged", flush=True)
    if args.reload_native:
        launcher = before["com.miui.home"]
        spawner(launcher["parent"])
        root(f"kill -TERM {launcher['parent']}")
        for _ in range(60):
            time.sleep(0.25)
            try:
                current = process("com.miui.home")
                if current["pid"] != launcher["pid"] and current["parent"] != launcher["parent"]:
                    break
            except subprocess.CalledProcessError:
                pass
        else:
            raise RuntimeError("No replacement launcher; preserve evidence before recovery")
        adb("shell", "am", "start", "-a", "android.intent.action.MAIN", "-c",
            "android.intent.category.HOME", "-p", "com.miui.home")
        spawner(current["parent"])
        active_apk = adb("shell", "pm", "path", PACKAGE).removeprefix("package:")
        inode = int(root(f"stat -c %i {shlex.quote(active_apk)}"))
        maps = root(f"cat /proc/{current['pid']}/maps")
        entries = [line for line in maps.splitlines() if active_apk in line]
        if not entries or any("(deleted)" in line or int(line.split()[4]) != inode for line in entries):
            raise RuntimeError("New launcher does not map the currently installed module APK")
        native_deadline = time.monotonic() + 120
        while True:
            native_log = adb("logcat", "-d", "-v", "brief", "--pid", str(current["pid"]),
                             "-s", "KeiHomeMonet")
            if "Two-tone native profile ready" in native_log or "Two-tone hooks inactive" in native_log:
                break
            if time.monotonic() >= native_deadline or process("com.miui.home") != current:
                break
            time.sleep(0.5)
        (args.evidence / "native-load.log").write_text(native_log)
        if "Two-tone native profile ready" not in native_log:
            raise RuntimeError("Current native profile did not become ready; inspect native-load.log")
        # A successfully mapped APK alone does not rule out a startup crash loop.
        time.sleep(3)
        if process("com.miui.home") != current:
            raise RuntimeError("Launcher changed again after native deployment")
        print("Native deployment process:", current, flush=True)
    after = {name: process(name) for name in before}
    for name in ("system_server", "com.android.systemui"):
        if before[name] != after[name]:
            raise RuntimeError(f"Unexpected {name} replacement")
    (args.evidence / "deployment.json").write_text(json.dumps({
        "serial": SERIAL, "previousVersionCode": old_version, "versionCode": new_version, "before": before, "after": after,
        "nativeReloadRequested": args.reload_native,
    }, indent=2))
    print("Deployment identity checks passed", flush=True)


if __name__ == "__main__":
    main()
