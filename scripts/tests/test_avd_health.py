import base64
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from hyperos_avd_health import check_health, keyguard_policy, png_size, recent_failures, unlocked_user_state


PNG = base64.b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+ip1sAAAAASUVORK5CYII="
)

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


class AvdHealthTest(unittest.TestCase):
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
            self.assertEqual({"showing": False, "inputRestricted": False}, report["keyguard"])
            self.assertEqual((1, 1), report["displaySize"])
            self.assertTrue((Path(directory) / "preflight.png").exists())
            self.assertEqual("recent-events", report["checks"][-1]["name"])
        self.assertTrue(all(call[:3] == ["adb", "-s", "emulator-5580"] for call in calls))
        self.assertTrue(any(call[-1] == "am get-started-user-state 0" for call in calls))
        self.assertTrue(any(call[-1] == "dumpsys -t 3 window policy" for call in calls))
        self.assertFalse(any("HOME" in " ".join(call) or "am start" in call[-1] for call in calls))

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
        self.assertEqual((1, 1), png_size(PNG))
        for bad in [b"", PNG[:-1], PNG[:40] + b"\x01" + PNG[41:]]:
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
