import importlib.util
from pathlib import Path
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "check-launcher-cold-boot.py"
SPEC = importlib.util.spec_from_file_location("check_launcher_cold_boot", SCRIPT)
gate = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(gate)


class LauncherColdBootTest(unittest.TestCase):
    def setUp(self):
        self.state = {
            "completed": "1",
            "user": "RUNNING_UNLOCKED",
            "launcherPid": "6316",
            "launcherExecutable": "/system_ext/bin/hyos_spawner",
            "foreground": True,
            "frameworkName": "v2.2.1-log (7948)",
            "keihomeCode": "115000770",
        }
        self.verbose = f"[ 2026-11-25T15:57:01.914 10149: 6316: 6316 I/LSPosed ] {gate.NATIVE_LOAD}"
        self.logcat = f"11-25 15:57:01.914  6316  6316 I LSPosed : {gate.NATIVE_LOAD}"
        self.expected = {"frameworkName": "v2.2.1-log (7948)", "keihomeCode": "115000770"}

    def test_same_pid_and_specific_framework_pass_with_either_log_format(self):
        for logs in (self.verbose, self.logcat):
            self.assertTrue(gate.assess(self.state, self.state, "", logs, self.expected)["passed"])

    def test_recovered_launcher_cannot_hide_an_earlier_launcher_crash(self):
        crash = "11-25 15:43:57.005  6340  6340 F libc : Fatal signal 11 in tid 6340 (com.miui.home)"
        report = gate.assess(self.state, self.state, crash, self.verbose, self.expected)
        self.assertFalse(report["passed"])
        self.assertEqual(report["launcherCrashLines"], [crash])

    def test_crash_in_auxiliary_process_is_not_the_main_launcher_crash(self):
        crash = "11-25 15:43:57.005  6340  6340 F DEBUG : Cmdline: com.miui.home:safe_mode"
        self.assertTrue(gate.assess(self.state, self.state, crash, self.verbose, self.expected)["passed"])

    def test_other_pid_and_other_framework_variant_fail(self):
        expected = self.expected | {"frameworkName": "v2.2.1 (7948)"}
        other_pid = self.verbose.replace("6316:", "1234:")
        report = gate.assess(self.state, self.state, "", other_pid, expected)
        self.assertFalse(report["passed"])
        self.assertEqual(len(report["failures"]), 2)

    def test_replacement_launcher_during_observation_fails(self):
        after = self.state | {"launcherPid": "1234"}
        self.assertFalse(gate.assess(self.state, after, "", self.logcat, self.expected)["passed"])


if __name__ == "__main__":
    unittest.main()
