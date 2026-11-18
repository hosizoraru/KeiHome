#!/usr/bin/env python3
"""Developer deployment: whole APK, increasing versions, explicit AVD only.

The default verifies critical Java receipts without restarting any process,
and separately reports observed target outcomes and coverage uncertainty.
Evidence includes service-reload.log and all-target-reloads.json. Strict mode
requires complete successful observed pairs; it cannot prove unseen targets.
--reload-native is an explicit development operation that replaces the exact
root HYOS spawner after Java hot reload succeeds. It is not module runtime code.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shlex
import subprocess
import time
from hyperos_avd_health import check_health
from lsposed_reload_parser import parse_reloads, plan_log_reads

ROOT = Path(__file__).resolve().parents[1]
SERIAL = "emulator-5580"
PACKAGE = "os.kei.hyper.home"


def adb(*args, timeout=45):
    return subprocess.check_output(
        ["adb", "-s", SERIAL, *args], text=True, errors="backslashreplace", timeout=timeout,
    ).strip()


def root(command, timeout=45):
    return adb("shell", "su", "-c", shlex.quote(command), timeout=timeout)


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


def service_log_snapshot(timeout=3):
    rows = root("stat -c '%n %i %s' /data/adb/lspd/log/*.log", timeout=timeout)
    result = {}
    for row in rows.splitlines():
        path, inode, size = row.rsplit(" ", 2)
        if re.fullmatch(r"/data/adb/lspd/log/(?:verbose|service)(?:_[^/]*)?\.log", path):
            result[path] = (int(inode), int(size))
    return result


class ServiceReloadCapture:
    """Append only checked inode byte ranges; retain rotation/read gaps."""

    def __init__(self):
        self.issues = []
        self.segments = []
        try:
            self.cursor = service_log_snapshot()
            if not self.cursor:
                self.issues.append("no service/verbose log at pre-install snapshot")
        except (subprocess.SubprocessError, ValueError) as error:
            self.cursor = None
            self.issues.append(f"pre-install service snapshot failed: {error}")

    def poll(self, timeout=3):
        deadline = time.monotonic() + timeout
        try:
            current = service_log_snapshot(timeout=max(0.01, deadline - time.monotonic()))
            if self.cursor is None:
                # A failed baseline must never replay pre-install history.
                self.cursor = current
                return
            reads, issues = plan_log_reads(self.cursor, current)
            self.issues.extend(issues)
            next_cursor = dict(current)
            for read in reads:
                path, inode = read["path"], read["inode"]
                offset, length = read["offset"], read["length"]
                next_cursor[path] = (inode, offset)
                try:
                    remaining = deadline - time.monotonic()
                    if remaining <= 0:
                        raise ValueError("service byte-read deadline expired")
                    # Open first, so a pathname replacement cannot mix inodes.
                    command = (
                        f"exec 3< {shlex.quote(path)} || exit 1; "
                        "stat -Lc '%i %s' /proc/$$/fd/3 || exit 1; "
                        f"tail -c +{offset + 1} <&3 | head -c {length}"
                    )
                    raw = subprocess.check_output(
                        ["adb", "-s", SERIAL, "shell", "su", "-c", shlex.quote(command)],
                        timeout=remaining,
                    )
                    metadata, data = raw.split(b"\n", 1)
                    actual_inode, actual_size = map(int, metadata.split())
                    if (actual_inode != inode or actual_size < offset + length
                            or len(data) != length):
                        raise ValueError("service inode/size changed during read")
                    segment = next(
                        (item for item in reversed(self.segments) if item["inode"] == inode),
                        None,
                    )
                    if segment is None or read["reset"]:
                        segment = {"path": path, "inode": inode, "offset": offset, "data": b""}
                        self.segments.append(segment)
                    segment["data"] += data
                    next_cursor[path] = (inode, offset + length)
                except (subprocess.SubprocessError, ValueError) as error:
                    self.issues.append(f"service byte read failed for {path}: {error}")
            self.cursor = next_cursor
        except (subprocess.SubprocessError, ValueError) as error:
            self.issues.append(f"service snapshot failed: {error}")
        self.issues = list(dict.fromkeys(self.issues))

    def text(self, *, complete_lines=False):
        chunks = []
        for segment in self.segments:
            data = segment["data"]
            if complete_lines:
                data = data.rpartition(b"\n")[0]
            chunks.append(
                f"# {segment['path']} inode={segment['inode']} offset={segment['offset']}\n"
                + data.decode("utf-8", errors="replace")
            )
        return "\n".join(chunks)

    def report(self, system_log=""):
        issues = list(self.issues)
        if any(segment["data"] and not segment["data"].endswith(b"\n")
               for segment in self.segments):
            issues.append("incomplete trailing service log record")
        return parse_reloads(
            self.text(complete_lines=True), PACKAGE,
            capture_issues=issues, system_log=system_log,
        )

    def save(self, out, system_log=""):
        report = self.report(system_log)
        (out / "service-reload.log").write_text(self.text())
        (out / "all-target-reloads.json").write_text(json.dumps(report, indent=2) + "\n")
        return report


def settle_service_reloads(capture, out, timeout=6):
    """Wait briefly for late asynchronous results, without changing native gates."""
    deadline = time.monotonic() + timeout
    last_signature = None
    stable_since = time.monotonic()
    # Do not start another adb round trip with only a few milliseconds left.
    while deadline - time.monotonic() >= 0.5:
        capture.poll(timeout=min(3, deadline - time.monotonic()))
        report = capture.save(out)
        signature = json.dumps(report["targets"], sort_keys=True)
        if signature != last_signature:
            last_signature = signature
            stable_since = time.monotonic()
        if (report["observedCompleteness"] == "complete"
                and time.monotonic() - stable_since >= 1):
            break
        remaining = deadline - time.monotonic()
        if remaining > 0:
            time.sleep(min(0.25, remaining))
    system_log = ""
    if capture.report()["counts"]["failed"]:
        try:
            system_log = adb(
                "logcat", "-b", "all", "-d", "-v", "threadtime", "-v", "year",
                "-t", "12000", timeout=3,
            )
        except subprocess.SubprocessError as error:
            capture.issues.append(f"system kill evidence unavailable: {error}")
        # Graphics spam can evict the relevant kill from the mixed-buffer tail.
        try:
            events = adb(
                "logcat", "-b", "events", "-d", "-v", "threadtime", "-v", "year",
                "-t", "2000", timeout=3,
            )
            (out / "reload-events.log").write_text(events)
            system_log += "\n" + events
        except subprocess.SubprocessError as error:
            capture.issues.append(f"process exit events unavailable: {error}")
        (out / "reload-system.log").write_text(system_log)
    return capture.save(out, system_log)


def print_reload_results(report):
    counts = report["counts"]
    print(f"All-target logs: observed success={counts['success']}, "
          f"failed={counts['failed']}, unknown={counts['unknown']}; "
          f"observed completeness={report['observedCompleteness']}; "
          "target population completeness=unknown.", flush=True)
    for target in report["targets"]:
        if target["status"] != "success":
            print(f"  {target['process']}/{target['pid']}: {target['status']}; "
                  f"detail={target['detail']!r}; reason={target['failureReason']}", flush=True)
    for issue in report["issues"]:
        print(f"  Evidence unknown: {issue}", flush=True)


def require_observed_reloads(report):
    if report["result"] != "observed_success" or report["observedCompleteness"] != "complete":
        raise RuntimeError(
            "Strict observed-target reload check failed or evidence unknown; "
            "inspect all-target-reloads.json. Unobserved targets are never certified."
        )


def java_reconciliation_complete(delta):
    own = [line for line in delta.splitlines() if f"[{PACKAGE}," in line]
    deferred = [line for line in own if "Hot reload deferred in " in line]
    if deferred:
        raise RuntimeError("API 102 handoff refused: " + "\n".join(deferred))
    for host in ("system", "com.android.systemui"):
        retirement = [line for line in own
                      if f"({host})[{PACKAGE},KeiHomeRetirement," in line]
        if not any(" retired pending=" in line for line in retirement):
            return False
        if not any(" adopted pending=" in line for line in retirement):
            return False
    return any("System-server icon refresh hooks installed" in line for line in own)


class ProcessUnavailable(RuntimeError):
    pass


def process(name, attempts=12):
    for attempt in range(attempts):
        # pidof scans unrelated cmdlines, whose reads can wait on their mmap locks.
        # Discover candidates through task comm without touching those mappings.
        suffix = shlex.quote(name[-15:])
        raw = root(
            'for f in /proc/[0-9]*/comm; do '
            'IFS= read -r n < "$f" 2>/dev/null || continue; '
            f'if [ "$n" = {suffix} ]; then '
            'p=${f#/proc/}; printf "%s\\n" "${p%/comm}"; fi; done',
            timeout=5,
        )
        candidates = []
        for value in raw.split():
            if not value.isdigit():
                continue
            pid = int(value)
            try:
                # comm is truncated; confirm full names for candidates only.
                if root(f"cat /proc/{pid}/cmdline", timeout=5).split("\0", 1)[0] != name:
                    continue
                stat = root(f"cat /proc/{pid}/stat", timeout=3)
                fields = stat[stat.rfind(")") + 2:].split()
                if len(fields) <= 19 or fields[0] == "Z":
                    continue
                candidates.append({
                    "pid": pid,
                    "parent": int(fields[1]),
                    "start": int(fields[19]),
                })
            except (subprocess.CalledProcessError, ValueError, IndexError):
                # A debugger/crash_dump clone can disappear between these reads.
                continue
        if candidates:
            # The live application has the oldest start tick; transient debugger
            # clones are newer. Do not select by directory enumeration order.
            return min(candidates, key=lambda item: item["start"])
        if attempt + 1 < attempts:
            time.sleep(0.25)
    raise ProcessUnavailable(f"Expected a live {name} process, got {raw!r}")


def wait_for_launcher_replacement(previous, timeout=15):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            current = process("com.miui.home", attempts=1)
            if current["pid"] != previous["pid"] and current["parent"] != previous["parent"]:
                return current
        except (ProcessUnavailable, subprocess.CalledProcessError):
            # Stopping the native spawner can leave no launcher during replacement.
            pass
        time.sleep(0.25)
    raise RuntimeError("No replacement launcher; preserve evidence before recovery")


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
    parser.add_argument(
        "--require-all-reloads", action="store_true",
        help="Require complete successful observed start/outcome pairs, not proof of unseen targets.",
    )
    parser.add_argument(
        "--capture-backend", choices=("auto", "console"), default="console",
        help="Use console capture for readiness; auto tries guest screencap first.",
    )
    parser.add_argument("--allow-downgrade", action="store_true",
                        help="Allow a lower code on the core-patched HyperOS test AVD")
    parser.add_argument("--evidence", required=True, type=Path)
    args = parser.parse_args()
    args.evidence.mkdir(parents=True, exist_ok=True)
    if "KeiOS_HyperOS4_Phone" not in adb("emu", "avd", "name").splitlines():
        raise RuntimeError("The selected serial is not the authorized HyperOS AVD")
    check_health(args.evidence, capture_backend=args.capture_backend)
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
    service_capture = ServiceReloadCapture()
    install_args = ["install", "-r"]
    if new_version < old_version:
        install_args.append("-d")
    result = adb(*install_args, "--no-streaming", str(args.apk.resolve()), timeout=120)
    if "Success" not in result:
        raise RuntimeError(result)
    print(f"Installed {old_version} -> {new_version}", flush=True)
    reconciliation_deadline = time.monotonic() + 60
    while time.monotonic() < reconciliation_deadline:
        delta = module_log_delta(logs)
        (args.evidence / "java-reload.log").write_text(delta)
        service_capture.poll()
        service_capture.save(args.evidence)
        if java_reconciliation_complete(delta):
            break
        time.sleep(0.25)
    else:
        raise RuntimeError("Incomplete system-server/SystemUI API 102 handoff; native deployment stopped")
    all_target_reloads = settle_service_reloads(service_capture, args.evidence)
    print_reload_results(all_target_reloads)
    for name, identity in before.items():
        if process(name) != identity:
            raise RuntimeError(f"{name} changed during APK installation/hot reload")
    print("Critical API 102 receipts verified; critical process identities unchanged. "
          "This does not certify every reload target.", flush=True)
    if args.require_all_reloads:
        require_observed_reloads(all_target_reloads)
    if args.reload_native:
        # Make the installed APK durable before the deliberate native process replacement.
        root("sync")
        package_paths = adb("shell", "pm", "path", PACKAGE).splitlines()
        base_paths = [line.removeprefix("package:").strip() for line in package_paths
                      if line.startswith("package:") and line.endswith("/base.apk")]
        if len(base_paths) != 1:
            raise RuntimeError("Cannot verify installed base APK; native restart stopped")
        installed_hash = root(f"sha256sum {shlex.quote(base_paths[0])}").split()[0]
        if installed_hash != hashlib.sha256(args.apk.read_bytes()).hexdigest():
            raise RuntimeError("Installed APK hash differs; native restart stopped")
        (args.evidence / "native-pre-restart-integrity.json").write_text(json.dumps({
            "flushed": True, "apkPath": base_paths[0], "apkSha256": installed_hash,
        }, indent=2) + "\n")
        launcher = before["com.miui.home"]
        spawner(launcher["parent"])
        root(f"kill -TERM {launcher['parent']}")
        current = wait_for_launcher_replacement(launcher)
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
        "javaHotReloadVerificationScope": "critical_hosts_only",
        "allTargetReloads": all_target_reloads,
    }, indent=2))
    print("Critical deployment identity checks passed; "
          "see all-target-reloads.json for target outcomes and unknown coverage.", flush=True)


if __name__ == "__main__":
    main()
