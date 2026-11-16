import importlib.util
from pathlib import Path
import unittest

MODULE = Path(__file__).resolve().parents[2] / "release_iconpack_gate.py"
spec = importlib.util.spec_from_file_location("release_gate", MODULE)
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


def report():
    return {"finishedAt": 123, "inventoryComplete": True, "userPreferencesUnchanged": True,
            "candidateCount": 1, "inventoryIssues": [], "inventories": [],
            "packs": [{"sourceId": "source", "localId": "local", "status": "passed",
                       "convertedReady": True}]}


def monet_report():
    return {"passed": True, "restored": True, "tests": 22, "sourceFingerprint": "source",
            "matrixSha256": "matrix", "runtime": {key: "a" * 64 for key in
            ("apkSha256", "testApkSha256", "nativeSha256")}}


class ReleaseGateTest(unittest.TestCase):
    def test_approved_complete_inventory_passes(self):
        self.assertEqual(1, len(gate.validate_matrix(report())))

    def test_incomplete_discovery_cannot_pass(self):
        for key, value in [("finishedAt", None), ("inventoryComplete", False),
                           ("userPreferencesUnchanged", False), ("candidateCount", 2),
                           ("inventoryIssues", [{"error": "unreadable archive"}]),
                           ("inventories", [{"status": "failed"}]), ("packs", [])]:
            with self.subTest(key=key):
                value_report = report()
                value_report[key] = value
                with self.assertRaises(RuntimeError):
                    gate.validate_matrix(value_report)

    def test_unapproved_import_cannot_pass(self):
        for key, value in [("status", "failed"), ("status", "queued"),
                           ("localId", ""), ("convertedReady", False)]:
            with self.subTest(key=key, value=value):
                value_report = report()
                value_report["packs"][0][key] = value
                with self.assertRaises(RuntimeError):
                    gate.validate_matrix(value_report)

    def test_native_mtz_does_not_require_converted_authorization(self):
        value_report = report()
        value_report["packs"][0].update(format="MIUI_MTZ", convertedReady=False)
        gate.validate_matrix(value_report)

    def test_duplicate_sources_and_missing_expected_fixtures_fail(self):
        with self.assertRaises(RuntimeError):
            gate.validate_matrix(report(), minimum=21)
        value_report = report()
        value_report["packs"] *= 2
        value_report["candidateCount"] = 2
        with self.assertRaises(RuntimeError):
            gate.validate_matrix(value_report)

    def test_runner_exit_without_real_tests_is_not_success(self):
        for text in ["", "OK (0 tests)", "INSTRUMENTATION_CODE: -1",
                     "FAILURES!!!", "INSTRUMENTATION_FAILED: Process crashed"]:
            with self.subTest(text=text), self.assertRaises(RuntimeError):
                gate.validate_instrumentation(text)

    def test_assumption_skips_and_ignored_tests_cannot_pass(self):
        for code in [-2, -3, -4]:
            with self.subTest(code=code), self.assertRaises(RuntimeError):
                gate.validate_instrumentation(f"INSTRUMENTATION_STATUS_CODE: {code}\nOK (19 tests)")

    def test_android_runner_success_code_is_not_a_test_failure(self):
        self.assertEqual(1, gate.validate_instrumentation("OK (1 test)\nINSTRUMENTATION_CODE: -1"))
        self.assertEqual(19, gate.validate_instrumentation("OK (19 tests)"))

    def test_monet_requires_current_source_matrix_and_restoration(self):
        gate.validate_monet(monet_report(), "source", "matrix")
        for key, value in [("passed", False), ("restored", False), ("tests", 0),
                           ("sourceFingerprint", "old"), ("matrixSha256", "old"),
                           ("runtime", {}), ("runtime", {"apkSha256": "invalid"})]:
            with self.subTest(key=key):
                evidence = monet_report()
                evidence[key] = value
                with self.assertRaises(RuntimeError):
                    gate.validate_monet(evidence, "source", "matrix")


if __name__ == "__main__":
    unittest.main()
