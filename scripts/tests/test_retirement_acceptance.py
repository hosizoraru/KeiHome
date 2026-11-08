import importlib.util
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
spec = importlib.util.spec_from_file_location(
    "retirement_acceptance", Path(__file__).resolve().parents[1] / "test-api102-retirement-avd.py")
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)

PASSED = "OK (4 tests)\n\nINSTRUMENTATION_CODE: -1\n"
EVENTS = [{"round": round_, "process": process, "status": "SUCCEEDED"}
          for round_ in (1, 2)
          for process in ("system", "com.android.systemui", "com.android.thememanager")]


class RetirementAcceptanceTest(unittest.TestCase):
    def test_client_success_does_not_hide_startup_crash(self):
        with self.assertRaisesRegex(RuntimeError, "did not complete"):
            runner.validate_result("shortMsg=Process crashed.\nINSTRUMENTATION_CODE: 0\n", EVENTS)

    def test_requires_completed_tests_and_every_host_in_both_rounds(self):
        runner.validate_result(PASSED, EVENTS)
        for events in [EVENTS[:-1], EVENTS[:5] + [EVENTS[0]], [],
                       EVENTS[:5] + [{**EVENTS[-1], "status": "FAILED"}]]:
            with self.assertRaisesRegex(RuntimeError, "two fresh successful rounds"):
                runner.validate_result(PASSED, events)

    def test_skipped_handoff_or_partial_runner_output_is_not_acceptance(self):
        for output in ["OK (3 tests)\nINSTRUMENTATION_CODE: -1\n",
                       "OK (4 tests)\n", "INSTRUMENTATION_CODE: -1\n"]:
            with self.assertRaisesRegex(RuntimeError, "did not complete"):
                runner.validate_result(output, EVENTS)


if __name__ == "__main__":
    unittest.main()
