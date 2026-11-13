"""Read-only checks before mutating the shared Phone AVD."""
import json
import re
import struct
import subprocess
import time
import zlib
from pathlib import Path

SERIAL = "emulator-5580"
DISPLAY = "4619827259835644672"


def unlocked_user_state(text):
    state = text.strip()
    if state != "RUNNING_UNLOCKED":
        raise ValueError(f"Current user is not RUNNING_UNLOCKED: {state[:160]!r}")
    return state


def keyguard_policy(text):
    """Only trust unambiguous direct fields of the one live delegate dump."""
    if re.search(r"dump\s+timeout|timed\s+out|permission denial|can't find service",
                 text, re.IGNORECASE):
        raise ValueError("Window policy is unavailable or timed out")
    lines = [line.expandtabs(8) for line in text.splitlines()]
    headers = [index for index, line in enumerate(lines)
               if re.match(r"^\s*KeyguardServiceDelegate\b", line)]
    if len(headers) != 1 or not re.fullmatch(r"\s*KeyguardServiceDelegate:?\s*", lines[headers[0]]):
        raise ValueError("Window policy must contain exactly one KeyguardServiceDelegate")
    header = headers[0]
    indent = len(lines[header]) - len(lines[header].lstrip())
    section = []
    for line in lines[header + 1:]:
        if not line.strip():
            continue
        depth = len(line) - len(line.lstrip())
        if depth <= indent:
            break
        section.append((depth, line.strip()))
    direct = min((depth for depth, _ in section), default=indent)
    fields = {}
    for depth, line in section:
        if depth != direct:
            continue
        if not re.match(r"^(showing|inputRestricted)\b", line):
            continue
        field = re.fullmatch(r"(showing|inputRestricted)\s*=\s*(true|false)", line)
        if not field or field[1] in fields:
            raise ValueError("Window policy has an invalid or ambiguous keyguard field")
        fields[field[1]] = field[2] == "true"
    if set(fields) != {"showing", "inputRestricted"}:
        raise ValueError("Window policy has incomplete keyguard state")
    return fields


def png_size(data):
    """Validate complete chunks, not just screencap's occasionally empty header."""
    if not data.startswith(b"\x89PNG\r\n\x1a\n"):
        raise ValueError("Missing PNG signature")
    offset, dimensions = 8, None
    while offset + 12 <= len(data):
        length = struct.unpack_from(">I", data, offset)[0]
        end = offset + 12 + length
        if end > len(data):
            break
        kind = data[offset + 4:offset + 8]
        payload = data[offset + 8:end - 4]
        crc = struct.unpack_from(">I", data, end - 4)[0]
        if zlib.crc32(kind + payload) != crc:
            raise ValueError("Invalid PNG chunk checksum")
        if kind == b"IHDR" and length == 13:
            dimensions = struct.unpack_from(">II", payload)
        if kind == b"IEND" and length == 0:
            if dimensions and min(dimensions) > 0 and end == len(data):
                return dimensions
            break
        offset = end
    raise ValueError("Incomplete screenshot")


def recent_failures(log, now, uptime):
    # logcat survives a reboot on some development images. Ignore the previous boot
    # and old incidents; an unknown time is not permission to proceed.
    lower = max(now - 120, now - uptime)
    failures = []
    for line in log.splitlines():
        stamp = re.match(r"^\s*(\d{10}\.\d+)\s", line)
        if not stamp or not lower <= float(stamp[1]) <= now + 2:
            continue
        if re.search(r"timeout publishing content providers|pre_watchdog|"
                     r"pre-Watchdog|ANR in com\.android\.systemui|"
                     r"Watchdog.*(Blocked in|WAITED_HALF|WAITED_UNTIL_PRE_WATCHDOG)", line,
                     re.IGNORECASE):
            failures.append(line[:1200])
    return failures[-12:]


def check_health(evidence, runner=subprocess.run):
    evidence = Path(evidence)
    evidence.mkdir(parents=True, exist_ok=True)
    report = {"serial": SERIAL, "healthy": False, "checks": []}

    def probe(name, args, timeout):
        started = time.monotonic()
        try:
            result = runner(["adb", "-s", SERIAL, *args], capture_output=True, timeout=timeout)
        except subprocess.TimeoutExpired:
            report["checks"].append({
                "name": name, "seconds": round(time.monotonic() - started, 3),
                "returnCode": None, "timedOut": True,
            })
            raise
        report["checks"].append({
            "name": name, "seconds": round(time.monotonic() - started, 3),
            "returnCode": result.returncode,
        })
        if result.returncode:
            raise RuntimeError(f"{name} failed: {result.stderr.decode(errors='replace')[:400]}")
        return result.stdout

    try:
        avd = probe("authorized-AVD", ["emu", "avd", "name"], 3).decode(errors="replace")
        if "KeiOS_HyperOS4_Phone" not in avd.splitlines():
            raise RuntimeError("The selected serial is not the authorized Phone AVD")
        boot = probe("boot", ["shell", "getprop sys.boot_completed"], 3).strip()
        if boot != b"1":
            raise RuntimeError("Phone AVD has not finished booting")
        user = probe("AMS", ["shell", "am get-current-user"], 5).strip()
        if not user.isdigit():
            raise RuntimeError("ActivityManager did not return a user ID")
        report["currentUser"] = int(user)
        state = probe("user-unlocked", [
            "shell", f"am get-started-user-state {int(user)}",
        ], 5).decode(errors="replace")
        report["startedUserState"] = state.strip()[:160]
        unlocked_user_state(state)
        policy = probe("keyguard-policy", [
            "shell", "dumpsys -t 3 window policy",
        ], 5).decode(errors="replace")
        report["keyguard"] = keyguard_policy(policy)
        if any(report["keyguard"].values()):
            raise RuntimeError("Keyguard is showing or inputRestricted; unlock before deployment")
        graphics = probe("SystemUI-renderer", [
            "shell", "dumpsys -t 3 gfxinfo com.android.systemui",
        ], 5).decode(errors="replace")
        process = re.search(r"Graphics info for pid (\d+) \[com\.android\.systemui\]", graphics)
        if "DUMP TIMEOUT" in graphics or not process:
            raise RuntimeError("SystemUI graphics did not answer; a screenshot alone can be stale")
        states = []
        for index in range(2):
            if index:
                time.sleep(0.2)
            stat = probe("SystemUI-main-state", [
                "shell", f"su -c 'cat /proc/{int(process[1])}/stat'",
            ], 3).decode(errors="replace")
            state = re.match(rf"^{int(process[1])} \(.*\) ([RSDI]) ", stat.strip())
            if not state:
                raise RuntimeError("Cannot confirm a live, unstopped SystemUI main thread")
            states.append(state[1])
        report["systemUiMainStates"] = states
        if states == ["D", "D"]:
            raise RuntimeError("SystemUI main thread remains in uninterruptible kernel wait")
        # Full-resolution PNG encoding exceeded 8s on a responsive AVD under host load.
        # Keep the independent renderer/main-thread/failure checks strict.
        image = probe("display", ["exec-out", "screencap", "-d", DISPLAY, "-p"], 15)
        report["displaySize"] = png_size(image)
        (evidence / "preflight.png").write_bytes(image)
        clock = probe("clock", ["shell", "date +%s; cat /proc/uptime"], 3).split()
        now, uptime = float(clock[0]), float(clock[1])
        events = probe("recent-events", [
            "shell", "logcat -b system -b events -d -t 1200 -v epoch",
        ], 5).decode(errors="replace")
        report["recentFailures"] = recent_failures(events, now, uptime)
        if report["recentFailures"]:
            raise RuntimeError("Recent Provider/SystemUI/Watchdog failure; preserve the scene first")
        report["healthy"] = True
    except (subprocess.TimeoutExpired, RuntimeError, ValueError, IndexError, OSError) as error:
        report["error"] = str(error)
    finally:
        (evidence / "preflight.json").write_text(json.dumps(report, indent=2) + "\n")
    if not report["healthy"]:
        raise RuntimeError(f"AVD preflight rejected deployment: {report['error']}")
    return report


if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence", required=True, type=Path)
    print(json.dumps(check_health(parser.parse_args().evidence), indent=2))
