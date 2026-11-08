import base64
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from hyperos_avd_health import check_health, png_size, recent_failures


PNG = base64.b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+ip1sAAAAASUVORK5CYII="
)


class AvdHealthTest(unittest.TestCase):
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
                "dumpsys -t 3 gfxinfo com.android.systemui":
                    b"*** SERVICE DUMP TIMEOUT (3000ms) EXPIRED ***\n",
            }
            return subprocess.CompletedProcess(args, 0, replies[args[-1]], b"")

        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(RuntimeError, "SystemUI graphics did not answer"):
                check_health(directory, runner=run)


if __name__ == "__main__":
    unittest.main()
