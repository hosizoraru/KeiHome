"""Read-only checks before mutating the shared Phone AVD."""
import json
import re
import struct
import subprocess
import tempfile
import time
import zlib
from pathlib import Path

SERIAL = "emulator-5580"
DISPLAY = "4619827259835644672"
DISPLAY_SIZE = (1120, 2436)
MAX_CAPTURE_BYTES = 32 * 1024 * 1024
CAPTURE_BACKENDS = ("auto", "console")


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
    offset, dimensions, has_pixels = 8, None, False
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
        if offset == 8 and kind != b"IHDR":
            raise ValueError("Missing initial PNG header")
        if kind == b"IHDR":
            if length != 13 or dimensions is not None:
                raise ValueError("Invalid or duplicate PNG header")
            width, height, depth, color, compression, filtering, interlace = struct.unpack(">IIBBBBB", payload)
            depths = {0: (1, 2, 4, 8, 16), 2: (8, 16), 3: (1, 2, 4, 8), 4: (8, 16), 6: (8, 16)}
            if depth not in depths.get(color, ()) or compression != 0 or filtering != 0 or interlace not in (0, 1):
                raise ValueError("Invalid PNG header format")
            dimensions = (width, height)
        if kind == b"IDAT" and length:
            has_pixels = True
        if kind == b"IEND" and length == 0:
            if dimensions and has_pixels and min(dimensions) > 0 and end == len(data):
                return dimensions
            break
        offset = end
    raise ValueError("Incomplete screenshot")


def display_png_size(data):
    if len(data) > MAX_CAPTURE_BYTES:
        raise ValueError("Screenshot exceeds capture size limit")
    dimensions = png_size(data)
    if dimensions != DISPLAY_SIZE:
        raise ValueError(f"Unexpected screenshot size {dimensions}; expected {DISPLAY_SIZE}")
    return dimensions


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


def console_capture(probe):
    # Emulator console paths containing spaces may ACK OK without writing.
    # A fresh directory also prevents accepting an earlier screenshot.
    with tempfile.TemporaryDirectory(prefix="keihome-avd-capture-", dir="/tmp") as capture:
        response = probe("display-console", ["emu", "screenrecord", "screenshot", capture], 5)
        if any(line.startswith(b"KO") for line in response.splitlines()):
            raise RuntimeError("Emulator console screenshot rejected the capture")
        files = []
        for entry in Path(capture).iterdir():
            files.append(entry)
            if len(files) > 1:
                break
        if len(files) != 1 or files[0].is_symlink() or not files[0].is_file() or \
                files[0].suffix.lower() != ".png":
            raise RuntimeError("Emulator console must produce exactly one regular PNG")
        if files[0].stat().st_size > MAX_CAPTURE_BYTES:
            raise ValueError("Console screenshot exceeds capture size limit")
        with files[0].open("rb") as source:
            image = source.read(MAX_CAPTURE_BYTES + 1)
        return image, display_png_size(image)


def check_health(evidence, runner=subprocess.run, capture_backend="auto"):
    evidence = Path(evidence)
    evidence.mkdir(parents=True, exist_ok=True)
    report = {"serial": SERIAL, "healthy": False, "captureBackendRequested": capture_backend, "checks": []}

    def probe(name, args, timeout):
        started = time.monotonic()
        try:
            result = runner(["adb", "-s", SERIAL, *args], capture_output=True, timeout=timeout)
        except (subprocess.TimeoutExpired, OSError) as error:
            failed = {
                "name": name, "seconds": round(time.monotonic() - started, 3),
                "returnCode": None,
            }
            if isinstance(error, subprocess.TimeoutExpired):
                failed["timedOut"] = True
            else:
                failed["error"] = f"{type(error).__name__}: {error}"
            report["checks"].append(failed)
            raise
        report["checks"].append({
            "name": name, "seconds": round(time.monotonic() - started, 3),
            "returnCode": result.returncode,
        })
        if result.returncode:
            raise RuntimeError(f"{name} failed: {result.stderr.decode(errors='replace')[:400]}")
        return result.stdout

    try:
        if capture_backend not in CAPTURE_BACKENDS:
            raise ValueError(f"Unknown capture backend: {capture_backend!r}")
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
        clock = probe("clock", ["shell", "date +%s; cat /proc/uptime"], 3).split()
        now, uptime = float(clock[0]), float(clock[1])
        events = probe("recent-events", [
            "shell", "logcat -b system -b events -d -t 1200 -v epoch",
        ], 5).decode(errors="replace")
        report["recentFailures"] = recent_failures(events, now, uptime)
        if report["recentFailures"]:
            raise RuntimeError("Recent Provider/SystemUI/Watchdog failure; preserve the scene first")
        # Capture only after every independent readiness gate has passed. A guest
        # screencap failure can be transport/encoding trouble rather than GUI failure.
        if capture_backend == "console":
            image, dimensions = console_capture(probe)
            backend = "emulator-console"
        else:
            try:
                image = probe("display", ["exec-out", "screencap", "-d", DISPLAY, "-p"], 15)
                dimensions = display_png_size(image)
                backend = "guest-screencap"
            except (subprocess.TimeoutExpired, RuntimeError, ValueError, OSError) as error:
                report["captureFallbackReason"] = f"{type(error).__name__}: {error}"
                if report["checks"][-1]["name"] == "display":
                    report["checks"][-1]["error"] = report["captureFallbackReason"]
                image, dimensions = console_capture(probe)
                backend = "emulator-console"
        report["displaySize"] = dimensions
        report["captureBackend"] = backend
        (evidence / "preflight.png").write_bytes(image)
        report["healthy"] = True
    except (subprocess.TimeoutExpired, RuntimeError, ValueError, IndexError, OSError) as error:
        report["error"] = str(error)
    finally:
        (evidence / "preflight.json").write_text(json.dumps(report, indent=2) + "\n")
    if not report["healthy"]:
        raise RuntimeError(f"AVD preflight rejected deployment: {report['error']}")
    return report


def main(argv=None):
    import argparse
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence", required=True, type=Path)
    parser.add_argument("--capture-backend", choices=CAPTURE_BACKENDS, default="console")
    args = parser.parse_args(argv)
    print(json.dumps(check_health(args.evidence, capture_backend=args.capture_backend), indent=2))


if __name__ == "__main__":
    main()
