import base64
import io
import json
import struct
import subprocess
import sys
import tempfile
import unittest
import zlib
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from hyperos_avd_health import (
    MAX_CAPTURE_BYTES, check_health, keyguard_policy, main as health_main, png_size,
    recent_failures, unlocked_user_state,
)


SMALL_PNG = base64.b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+ip1sAAAAASUVORK5CYII="
)


def png_chunk(kind, payload):
    return struct.pack(">I", len(payload)) + kind + payload + struct.pack(">I", zlib.crc32(kind + payload))


def screenshot_png(width=1120, height=2436):
    return b"\x89PNG\r\n\x1a\n" + \
        png_chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 0, 0, 0, 0)) + \
        png_chunk(b"IDAT", zlib.compress((b"\x00" * (width + 1)) * height)) + \
        png_chunk(b"IEND", b"")


PNG = screenshot_png()

POLICY = b"""WINDOW MANAGER POLICY STATE (dumpsys window policy)
  mTopFullscreenOpaqueWindowState=Window{123 u0 os.example/.ForegroundActivity}
  KeyguardServiceDelegate
    showing=false
    inputRestricted=false
    occluded=false
    secure=true
    currentUser=0
  mOtherPolicyState:
    showing=true
    inputRestricted=true
"""


def fake_runner(overrides=None, calls=None):
    replies = {
        "name": b"KeiOS_HyperOS4_Phone\nOK\n",
        "getprop sys.boot_completed": b"1\n",
        "am get-current-user": b"0\n",
        "am get-started-user-state 0": b"RUNNING_UNLOCKED\n",
        "dumpsys -t 3 window policy": POLICY,
        "dumpsys -t 3 gfxinfo com.android.systemui":
            b"** Graphics info for pid 99 [com.android.systemui] **\n",
        "su -c 'cat /proc/99/stat'": b"99 (com.android.systemui) S 1 2\n",
        "-p": PNG,
        "date +%s; cat /proc/uptime": b"1800000300\n600.0 100.0\n",
        "logcat -b system -b events -d -t 1200 -v epoch": b"",
    }
    replies.update(overrides or {})

    def run(args, **kwargs):
        if calls is not None:
            calls.append(args)
        reply = replies[args[-1]]
        if isinstance(reply, Exception):
            raise reply
        if isinstance(reply, tuple):
            return subprocess.CompletedProcess(args, *reply)
        return subprocess.CompletedProcess(args, 0, reply, b"")

    return run


def fallback_runner(primary, outputs, calls, captures, console_reply=b"OK\n", overrides=None):
    guest = fake_runner({**(overrides or {}), "-p": primary})

    def run(args, **kwargs):
        calls.append(args)
        if args[3:6] != ["emu", "screenrecord", "screenshot"]:
            return guest(args, **kwargs)
        capture = Path(args[-1])
        captures.append(capture)
        if kwargs["timeout"] != 5 or capture.parent != Path("/tmp") or " " in str(capture) or \
                not capture.name.startswith("keihome-avd-capture-") or not capture.is_dir():
            raise AssertionError("Console capture must use its bounded, fresh, space-free /tmp directory")
        if callable(outputs):
            outputs(capture)
        else:
            for name, contents in outputs.items():
                (capture / name).write_bytes(contents)
        if isinstance(console_reply, Exception):
            raise console_reply
        if isinstance(console_reply, tuple):
            return subprocess.CompletedProcess(args, *console_reply)
        return subprocess.CompletedProcess(args, 0, console_reply, b"")

    return run


class AvdHealthTest(unittest.TestCase):
    def test_console_mode_captures_directly_without_invoking_guest_or_inventing_a_failure(self):
        calls, captures = [], []
        with tempfile.TemporaryDirectory(prefix="console evidence with spaces ") as directory:
            report = check_health(directory, capture_backend="console", runner=fallback_runner(
                AssertionError("Console mode must not call guest screencap"),
                {"capture.png": PNG}, calls, captures))
            self.assertTrue(report["healthy"])
            self.assertEqual("console", report["captureBackendRequested"])
            self.assertEqual("emulator-console", report["captureBackend"])
            self.assertNotIn("captureFallbackReason", report)
            self.assertEqual((1120, 2436), report["displaySize"])
            self.assertEqual(PNG, (Path(directory) / "preflight.png").read_bytes())
            self.assertEqual(["recent-events", "display-console"],
                             [check["name"] for check in report["checks"][-2:]])
            self.assertNotIn("display", [check["name"] for check in report["checks"]])
            receipt = json.loads((Path(directory) / "preflight.json").read_text())
            self.assertEqual("console", receipt["captureBackendRequested"])
            self.assertNotIn("captureFallbackReason", receipt)
        self.assertFalse(any("screencap" in call for call in calls))
        self.assertEqual(1, len(captures))
        self.assertFalse(captures[0].exists())

    def test_console_mode_requires_real_unique_valid_capture_and_bounded_completion(self):
        for outputs, response in [
            ({}, b"OK\n"),
            ({"one.png": PNG, "two.png": PNG}, b"OK\n"),
            ({"capture.png": PNG[:-1]}, b"OK\n"),
            ({"capture.png": SMALL_PNG}, b"OK\n"),
            ({"capture.png": PNG}, subprocess.TimeoutExpired("console screenshot", 5)),
        ]:
            calls, captures = [], []
            with self.subTest(outputs=list(outputs), response=response), tempfile.TemporaryDirectory() as directory:
                with self.assertRaisesRegex(RuntimeError, "preflight rejected"):
                    check_health(directory, capture_backend="console", runner=fallback_runner(
                        AssertionError("No guest retry is allowed"), outputs, calls, captures,
                        console_reply=response))
                report = json.loads((Path(directory) / "preflight.json").read_text())
                self.assertFalse(report["healthy"])
                self.assertEqual("console", report["captureBackendRequested"])
                self.assertNotIn("captureBackend", report)
                self.assertNotIn("captureFallbackReason", report)
                self.assertFalse((Path(directory) / "preflight.png").exists())
            self.assertFalse(any("screencap" in call for call in calls))
            self.assertEqual(1, len(captures))
            self.assertFalse(captures[0].exists())

    def test_console_mode_still_requires_every_independent_readiness_gate(self):
        for failed_stage, overrides in [
            ("AMS", {"am get-current-user": subprocess.TimeoutExpired("AMS", 5)}),
            ("user-unlocked", {"am get-started-user-state 0": b"RUNNING_LOCKED\n"}),
            ("keyguard-policy", {"dumpsys -t 3 window policy": subprocess.TimeoutExpired("policy", 5)}),
            ("keyguard-policy", {"dumpsys -t 3 window policy": POLICY.replace(b"showing=false", b"showing=true")}),
            ("SystemUI-renderer", {"dumpsys -t 3 gfxinfo com.android.systemui": b"*** DUMP TIMEOUT ***"}),
            ("SystemUI-main-state", {"su -c 'cat /proc/99/stat'": b"99 (com.android.systemui) D 1 2\n"}),
            ("recent-events", {"logcat -b system -b events -d -t 1200 -v epoch":
                               b"1800000299.000 1 1 W Watchdog: pre_watchdog\n"}),
        ]:
            calls, captures = [], []
            with self.subTest(stage=failed_stage, overrides=overrides), tempfile.TemporaryDirectory() as directory:
                with self.assertRaisesRegex(RuntimeError, "preflight rejected"):
                    check_health(directory, capture_backend="console", runner=fallback_runner(
                        AssertionError("No guest screenshot expected"), {"capture.png": PNG}, calls, captures,
                        overrides=overrides))
                report = json.loads((Path(directory) / "preflight.json").read_text())
                self.assertFalse(report["healthy"])
                self.assertEqual(failed_stage, report["checks"][-1]["name"])
                self.assertNotIn("captureBackend", report)
                self.assertFalse((Path(directory) / "preflight.png").exists())
            self.assertEqual([], captures)
            self.assertFalse(any("screencap" in call for call in calls))

    def test_unknown_capture_backend_is_rejected_before_any_adb_command(self):
        for backend in ["guest", "AUTO", "", None]:
            calls = []
            with self.subTest(backend=backend), tempfile.TemporaryDirectory() as directory:
                with self.assertRaisesRegex(RuntimeError, "Unknown capture backend"):
                    check_health(directory, capture_backend=backend, runner=fake_runner(calls=calls))
                report = json.loads((Path(directory) / "preflight.json").read_text())
                self.assertFalse(report["healthy"])
                self.assertEqual(backend, report["captureBackendRequested"])
                self.assertEqual([], report["checks"])
            self.assertEqual([], calls)

    def test_cli_defaults_to_console_and_accepts_explicit_auto_or_console(self):
        for extra, expected in [([], "console"), (["--capture-backend", "auto"], "auto"),
                                (["--capture-backend", "console"], "console")]:
            with self.subTest(extra=extra), patch("hyperos_avd_health.check_health",
                    return_value={"healthy": True}) as health, patch("sys.stdout", new_callable=io.StringIO) as stdout:
                health_main(["--evidence", "/tmp/health cli evidence", *extra])
                health.assert_called_once_with(Path("/tmp/health cli evidence"),
                                               capture_backend=expected, serial="emulator-5580")
                self.assertTrue(json.loads(stdout.getvalue())["healthy"])

    def test_cli_unknown_capture_backend_never_reaches_health_check(self):
        with patch("hyperos_avd_health.check_health") as health, patch("sys.stderr", new_callable=io.StringIO):
            with self.assertRaises(SystemExit) as error:
                health_main(["--evidence", "/tmp/unused-health-evidence", "--capture-backend", "guest"])
            self.assertEqual(2, error.exception.code)
            health.assert_not_called()

    def test_guest_timeout_uses_console_without_claiming_guest_recovery(self):
        calls, captures = [], []
        with tempfile.TemporaryDirectory(prefix="avd evidence with spaces ") as directory:
            report = check_health(directory, runner=fallback_runner(
                subprocess.TimeoutExpired("screencap", 15), {"capture.png": PNG}, calls, captures))
            self.assertTrue(report["healthy"])
            self.assertEqual("auto", report["captureBackendRequested"])
            self.assertEqual("emulator-console", report["captureBackend"])
            self.assertIn("TimeoutExpired", report["captureFallbackReason"])
            self.assertEqual((1120, 2436), report["displaySize"])
            self.assertEqual(PNG, (Path(directory) / "preflight.png").read_bytes())
            guest, console = report["checks"][-2:]
            self.assertEqual("display", guest["name"])
            self.assertTrue(guest["timedOut"])
            self.assertIsNone(guest["returnCode"])
            self.assertIn("seconds", guest)
            self.assertEqual(report["captureFallbackReason"], guest["error"])
            self.assertEqual("display-console", console["name"])
            self.assertEqual(0, console["returnCode"])
            self.assertIn("seconds", console)
            receipt = json.loads((Path(directory) / "preflight.json").read_text())
            self.assertEqual("emulator-console", receipt["captureBackend"])
        self.assertEqual(1, len(captures))
        self.assertFalse(captures[0].exists())
        self.assertEqual(1, sum(call[3:6] == ["emu", "screenrecord", "screenshot"] for call in calls))

    def test_failed_invalid_truncated_and_wrong_size_guest_capture_can_fall_back(self):
        for primary in [(1, b"", b"guest encoder failed"), b"not a PNG", PNG[:-1],
                        PNG[:40] + b"\x01" + PNG[41:], SMALL_PNG]:
            calls, captures = [], []
            with self.subTest(primary=str(primary)[:80]), tempfile.TemporaryDirectory() as directory:
                report = check_health(directory, runner=fallback_runner(
                    primary, {"capture.png": PNG}, calls, captures))
                self.assertTrue(report["healthy"])
                self.assertEqual("emulator-console", report["captureBackend"])
                self.assertTrue(report["captureFallbackReason"])
                self.assertIn("error", report["checks"][-2])
                self.assertEqual(PNG, (Path(directory) / "preflight.png").read_bytes())
            self.assertFalse(captures[0].exists())

    def test_console_ack_without_one_regular_png_is_not_capture_success(self):
        outputs = [
            {}, {"one.png": PNG, "two.png": PNG}, {"one.png": PNG, "extra.txt": b"metadata"},
            {"capture.txt": PNG},
            lambda folder: (folder / "capture.png").mkdir(),
            lambda folder: (folder / "capture.png").symlink_to("/tmp/no-keihome-capture-target"),
        ]
        for files in outputs:
            calls, captures = [], []
            with self.subTest(files=files), tempfile.TemporaryDirectory() as directory:
                with self.assertRaisesRegex(RuntimeError, "exactly one regular PNG"):
                    check_health(directory, runner=fallback_runner(b"", files, calls, captures))
                report = json.loads((Path(directory) / "preflight.json").read_text())
                self.assertFalse(report["healthy"])
                self.assertIn("captureFallbackReason", report)
                self.assertNotIn("captureBackend", report)
                self.assertFalse((Path(directory) / "preflight.png").exists())
            self.assertFalse(captures[0].exists())

    def test_console_png_must_be_complete_checksummed_and_expected_size(self):
        header_only = PNG[:33] + png_chunk(b"IEND", b"")
        for image in [b"not a PNG", PNG[:-1], PNG[:40] + b"\x01" + PNG[41:],
                      header_only, SMALL_PNG, screenshot_png(2436, 1119)]:
            calls, captures = [], []
            with self.subTest(image=str(image)[:80]), tempfile.TemporaryDirectory() as directory:
                with self.assertRaisesRegex(RuntimeError, "preflight rejected"):
                    check_health(directory, runner=fallback_runner(
                        b"", {"capture.png": image}, calls, captures))
                report = json.loads((Path(directory) / "preflight.json").read_text())
                self.assertFalse(report["healthy"])
                self.assertNotIn("captureBackend", report)
                self.assertFalse((Path(directory) / "preflight.png").exists())
            self.assertFalse(captures[0].exists())

    def test_console_timeout_failure_and_ko_ack_reject_even_if_output_exists(self):
        for reply in [subprocess.TimeoutExpired("console screenshot", 5),
                      (1, b"", b"console unavailable"), b"KO: screenshot failed\n"]:
            calls, captures = [], []
            with self.subTest(reply=reply), tempfile.TemporaryDirectory() as directory:
                with self.assertRaisesRegex(RuntimeError, "preflight rejected"):
                    check_health(directory, runner=fallback_runner(
                        b"", {"capture.png": PNG}, calls, captures, console_reply=reply))
                report = json.loads((Path(directory) / "preflight.json").read_text())
                self.assertFalse(report["healthy"])
                self.assertIn("captureFallbackReason", report)
                self.assertEqual("display-console", report["checks"][-1]["name"])
                self.assertFalse((Path(directory) / "preflight.png").exists())
            self.assertFalse(captures[0].exists())
            self.assertEqual(1, sum(call[3:6] == ["emu", "screenrecord", "screenshot"] for call in calls))

    def test_console_output_size_is_bounded_before_reading(self):
        def oversized(folder):
            with (folder / "capture.png").open("wb") as image:
                image.truncate(MAX_CAPTURE_BYTES + 1)

        calls, captures = [], []
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(RuntimeError, "exceeds capture size limit"):
                check_health(directory, runner=fallback_runner(b"", oversized, calls, captures))
            self.assertFalse((Path(directory) / "preflight.png").exists())
        self.assertFalse(captures[0].exists())

    def test_recent_failure_gate_blocks_capture_and_console_fallback(self):
        calls, captures = [], []
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(RuntimeError, "Recent Provider/SystemUI/Watchdog failure"):
                check_health(directory, runner=fallback_runner(b"", {"capture.png": PNG}, calls, captures,
                    overrides={"logcat -b system -b events -d -t 1200 -v epoch":
                               b"1800000299.000 1 1 W Watchdog: pre_watchdog\n"}))
            self.assertFalse((Path(directory) / "preflight.png").exists())
        self.assertEqual([], captures)
        self.assertFalse(any("screencap" in call for call in calls))

    def test_user_state_requires_only_the_exact_unlocked_state(self):
        self.assertEqual("RUNNING_UNLOCKED", unlocked_user_state("\r\nRUNNING_UNLOCKED\n"))
        for state in ["", "UNKNOWN", "-1", "RUNNING_LOCKED", "RUNNING_UNLOCKING",
                      "BOOTING", "STOPPING", "SHUTDOWN", "User 0 is not started",
                      "RUNNING_UNLOCKED\nRUNNING_LOCKED", "RUNNING_UNLOCKED extra"]:
            with self.subTest(state=state), self.assertRaises(ValueError):
                unlocked_user_state(state)

    def test_policy_reads_only_the_live_delegate_not_other_showing_fields(self):
        self.assertEqual({"showing": False, "inputRestricted": False},
                         keyguard_policy(POLICY.decode()))

    def test_policy_rejects_unavailable_incomplete_nested_and_ambiguous_fields(self):
        for policy in [
            "", "Permission Denial: can't dump WindowManager", "Can't find service: window",
            "showing=false\ninputRestricted=false\n",
            "KeyguardServiceDelegate\n  showing=false\n",
            "KeyguardServiceDelegate\n  inputRestricted=false\n",
            "KeyguardServiceDelegate\n  cached:\n    showing=false\n    inputRestricted=false\n",
            "KeyguardServiceDelegate\n  showing=unknown\n  inputRestricted=false\n",
            "KeyguardServiceDelegate\n  showing=false\n  showing=true\n  inputRestricted=false\n",
            "KeyguardServiceDelegate\n  showing=false\n  inputRestricted=false\n"
            "  inputRestricted=false\n",
            POLICY.decode() + POLICY.decode(),
            POLICY.decode() + "*** SERVICE DUMP TIMEOUT (3000ms) EXPIRED ***",
        ]:
            with self.subTest(policy=policy), self.assertRaises(ValueError):
                keyguard_policy(policy)

    def test_locked_or_unknown_user_rejects_before_keyguard_and_renderer(self):
        for state in [b"RUNNING_LOCKED\n", b"RUNNING_UNLOCKING\n", b"UNKNOWN\n", b""]:
            calls = []
            with self.subTest(state=state), tempfile.TemporaryDirectory() as directory:
                with self.assertRaisesRegex(RuntimeError, "not RUNNING_UNLOCKED"):
                    check_health(directory, runner=fake_runner({"am get-started-user-state 0": state}, calls))
                report = json.loads((Path(directory) / "preflight.json").read_text())
                self.assertFalse(report["healthy"])
                self.assertEqual(0, report["currentUser"])
            self.assertFalse(any("window policy" in call[-1] or "gfxinfo" in call[-1] for call in calls))

    def test_unlocked_user_still_rejects_showing_or_input_restricted_keyguard(self):
        for field in [b"showing", b"inputRestricted"]:
            calls = []
            policy = POLICY.replace(field + b"=false", field + b"=true")
            with self.subTest(field=field), tempfile.TemporaryDirectory() as directory:
                with self.assertRaisesRegex(RuntimeError, "Keyguard is showing or inputRestricted"):
                    check_health(directory, runner=fake_runner({"dumpsys -t 3 window policy": policy}, calls))
                report = json.loads((Path(directory) / "preflight.json").read_text())
                self.assertEqual("RUNNING_UNLOCKED", report["startedUserState"])
                self.assertTrue(report["keyguard"][field.decode()])
                self.assertFalse(report["healthy"])
            self.assertFalse(any("gfxinfo" in call[-1] or "screencap" in call for call in calls))

    def test_unavailable_and_incomplete_policy_reject_before_renderer(self):
        for reply in [b"", b"KeyguardServiceDelegate\n  showing=false\n",
                      b"Unknown command: window policy\n", (1, b"", b"window service unavailable"),
                      POLICY + b"*** SERVICE DUMP TIMEOUT (3000ms) EXPIRED ***"]:
            calls = []
            with self.subTest(reply=reply), tempfile.TemporaryDirectory() as directory:
                with self.assertRaisesRegex(RuntimeError, "preflight rejected"):
                    check_health(directory, runner=fake_runner({"dumpsys -t 3 window policy": reply}, calls))
                report = json.loads((Path(directory) / "preflight.json").read_text())
                self.assertFalse(report["healthy"])
                self.assertFalse((Path(directory) / "preflight.png").exists())
            self.assertFalse(any("gfxinfo" in call[-1] for call in calls))

    def test_policy_timeout_preserves_failed_stage_receipt_and_stops(self):
        calls = []
        timeout = subprocess.TimeoutExpired("dumpsys -t 3 window policy", 5)
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(RuntimeError, "preflight rejected"):
                check_health(directory, runner=fake_runner({"dumpsys -t 3 window policy": timeout}, calls))
            report = json.loads((Path(directory) / "preflight.json").read_text())
            self.assertFalse(report["healthy"])
            self.assertEqual("keyguard-policy", report["checks"][-1]["name"])
            self.assertTrue(report["checks"][-1]["timedOut"])
        self.assertEqual("dumpsys -t 3 window policy", calls[-1][-1])

    def test_healthy_unlocked_foreground_app_continues_all_checks_without_requiring_home(self):
        calls = []
        with tempfile.TemporaryDirectory() as directory:
            report = check_health(directory, runner=fake_runner(calls=calls))
            self.assertTrue(report["healthy"])
            self.assertEqual("auto", report["captureBackendRequested"])
            self.assertEqual({"showing": False, "inputRestricted": False}, report["keyguard"])
            self.assertEqual((1120, 2436), report["displaySize"])
            self.assertEqual("guest-screencap", report["captureBackend"])
            self.assertNotIn("captureFallbackReason", report)
            self.assertTrue((Path(directory) / "preflight.png").exists())
            self.assertEqual("recent-events", report["checks"][-2]["name"])
            self.assertEqual("display", report["checks"][-1]["name"])
        self.assertTrue(all(call[:3] == ["adb", "-s", "emulator-5580"] for call in calls))
        self.assertTrue(any(call[-1] == "am get-started-user-state 0" for call in calls))
        self.assertTrue(any(call[-1] == "dumpsys -t 3 window policy" for call in calls))
        self.assertFalse(any("HOME" in " ".join(call) or "am start" in call[-1] for call in calls))
        self.assertFalse(any(call[3:6] == ["emu", "screenrecord", "screenshot"] for call in calls))

    def test_started_state_is_requested_for_the_actual_current_user(self):
        calls = []
        with tempfile.TemporaryDirectory() as directory:
            report = check_health(directory, runner=fake_runner({
                "am get-current-user": b"7\n",
                "am get-started-user-state 7": b"RUNNING_UNLOCKED\n",
                "dumpsys -t 3 window policy": POLICY.replace(b"currentUser=0", b"currentUser=7"),
            }, calls))
            self.assertTrue(report["healthy"])
            self.assertEqual(7, report["currentUser"])
        self.assertTrue(any(call[-1] == "am get-started-user-state 7" for call in calls))
        self.assertFalse(any(call[-1] == "am get-started-user-state 0" for call in calls))

    def test_pad_requires_its_avd_identity_and_accepts_landscape_capture(self):
        calls = []
        with tempfile.TemporaryDirectory() as directory:
            report = check_health(directory, serial="emulator-5582", runner=fake_runner({
                "name": b"KeiOS_HyperOS4_Pad\nOK\n",
                "-p": screenshot_png(3408, 2272),
            }, calls))
            self.assertEqual((3408, 2272), report["displaySize"])
            self.assertTrue(report["healthy"])
        self.assertTrue(all(call[:3] == ["adb", "-s", "emulator-5582"] for call in calls))
        calls.clear()
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(RuntimeError, "not the authorized Pad AVD"):
                check_health(directory, serial="emulator-5582", runner=fake_runner(calls=calls))
        self.assertEqual(1, len(calls))

    def test_same_port_with_another_avd_is_rejected_before_android_probes(self):
        calls = []

        def run(args, **kwargs):
            calls.append(args)
            return subprocess.CompletedProcess(args, 0, b"ColorOS17_API37\nOK\n", b"")

        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(RuntimeError, "not the authorized Phone AVD"):
                check_health(directory, runner=run)
        self.assertEqual(1, len(calls))

    def test_screenshot_must_be_complete_with_valid_checksums(self):
        self.assertEqual((1, 1), png_size(SMALL_PNG))
        self.assertEqual((1120, 2436), png_size(PNG))
        for bad in [b"", PNG[:-1], PNG[:40] + b"\x01" + PNG[41:],
                    PNG[:33] + png_chunk(b"IEND", b"")]:
            with self.assertRaises(ValueError):
                png_size(bad)

    def test_recent_failure_rejects_but_previous_boot_and_old_incidents_do_not(self):
        now = 1800000300
        events = "\n".join([
            f"{now - 130}.000 1 1 W Watchdog: pre_watchdog",
            f"{now - 20}.000 1 1 W ActivityManager: timeout publishing content providers",
            f"{now - 10}.000 1 1 I ActivityManager: normal startup",
        ])
        self.assertEqual(1, len(recent_failures(events, now, 600)))
        self.assertEqual([], recent_failures(events, now, 5))

    def test_ams_timeout_preserves_receipt_and_never_reaches_graphics_or_install(self):
        calls = []

        def run(args, **kwargs):
            calls.append(args)
            if args[-3:] == ["emu", "avd", "name"]:
                return subprocess.CompletedProcess(args, 0, b"KeiOS_HyperOS4_Phone\nOK\n", b"")
            if "getprop sys.boot_completed" in args:
                return subprocess.CompletedProcess(args, 0, b"1\n", b"")
            raise subprocess.TimeoutExpired(args, kwargs["timeout"])

        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(RuntimeError, "preflight rejected"):
                check_health(directory, runner=run)
            report = json.loads((Path(directory) / "preflight.json").read_text())
            self.assertFalse(report["healthy"])
        self.assertEqual(3, len(calls))
        self.assertTrue(all(call[:3] == ["adb", "-s", "emulator-5580"] for call in calls))
        self.assertFalse(any("install" in call or "screencap" in call for call in calls))

    def test_uninterruptible_systemui_is_rejected_despite_answering_gfxinfo(self):
        def run(args, **kwargs):
            command = args[-1]
            replies = {
                "name": b"KeiOS_HyperOS4_Phone\nOK\n",
                "getprop sys.boot_completed": b"1\n",
                "am get-current-user": b"0\n",
                "am get-started-user-state 0": b"RUNNING_UNLOCKED\n",
                "dumpsys -t 3 window policy": POLICY,
                "dumpsys -t 3 gfxinfo com.android.systemui":
                    b"** Graphics info for pid 99 [com.android.systemui] **\n",
                "su -c 'cat /proc/99/stat'": b"99 (com.android.systemui) D 1 2\n",
            }
            return subprocess.CompletedProcess(args, 0, replies[command], b"")

        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(RuntimeError, "uninterruptible kernel wait"):
                check_health(directory, runner=run)
            report = json.loads((Path(directory) / "preflight.json").read_text())
            self.assertEqual(["D", "D"], report["systemUiMainStates"])
            self.assertFalse((Path(directory) / "preflight.png").exists())

    def test_dumpsys_timeout_cannot_pass_just_because_its_exit_code_is_zero(self):
        def run(args, **kwargs):
            replies = {
                "name": b"KeiOS_HyperOS4_Phone\nOK\n",
                "getprop sys.boot_completed": b"1\n",
                "am get-current-user": b"0\n",
                "am get-started-user-state 0": b"RUNNING_UNLOCKED\n",
                "dumpsys -t 3 window policy": POLICY,
                "dumpsys -t 3 gfxinfo com.android.systemui":
                    b"*** SERVICE DUMP TIMEOUT (3000ms) EXPIRED ***\n",
            }
            return subprocess.CompletedProcess(args, 0, replies[args[-1]], b"")

        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(RuntimeError, "SystemUI graphics did not answer"):
                check_health(directory, runner=run)


if __name__ == "__main__":
    unittest.main()
