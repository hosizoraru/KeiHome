"""No-device tests for bounded capture and strict observed-target gates."""
import contextlib
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
spec = importlib.util.spec_from_file_location(
    "keihome_reload_capture", Path(__file__).resolve().parents[1] / "deploy-hyperos-avd.py"
)
deploy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(deploy)
FIXTURES = Path(__file__).with_name("fixtures")
REAL = (FIXTURES / "lsposed-reload-20261115.log").read_bytes()
KILL = (FIXTURES / "lsposed-reload-20261115-kill.log").read_text()
PATH = "/data/adb/lspd/log/verbose_old.log"


class CaptureTest(unittest.TestCase):
    def test_snapshot_filters_module_logs_and_records_inode_and_size(self):
        rows = (f"{PATH} 100 500\n/data/adb/lspd/log/modules_old.log 101 600\n"
                "/data/adb/lspd/log/service.log 102 700")
        with patch.object(deploy, "root", return_value=rows):
            snapshot = deploy.service_log_snapshot()
        self.assertEqual({PATH: (100, 500), "/data/adb/lspd/log/service.log": (102, 700)}, snapshot)

    def test_reads_only_postinstall_bytes_with_open_inode_verification(self):
        with patch.object(deploy, "service_log_snapshot", side_effect=[
            {PATH: (100, 500)}, {PATH: (100, 500 + len(REAL))}
        ]), patch.object(
            deploy.subprocess, "check_output",
            return_value=f"100 {500 + len(REAL)}\n".encode() + REAL,
        ) as read:
            capture = deploy.ServiceReloadCapture()
            capture.poll()
        command = read.call_args.args[0][-1]
        self.assertIn("tail -c +501", command)
        self.assertIn(f"head -c {len(REAL)}", command)
        self.assertIn("/proc/$$/fd/3 || exit 1", command)
        self.assertEqual(1, capture.report()["counts"]["failed"])
        self.assertEqual(9, capture.report()["counts"]["success"])

    def test_renamed_inode_preserves_a_record_split_across_reads(self):
        cut = REAL.index(b"Start hot reload") + 5
        renamed = "/data/adb/lspd/log/verbose_rotated.log"
        with patch.object(deploy, "service_log_snapshot", side_effect=[
            {PATH: (100, 0)}, {PATH: (100, cut)}, {renamed: (100, len(REAL))}
        ]), patch.object(deploy.subprocess, "check_output", side_effect=[
            f"100 {cut}\n".encode() + REAL[:cut],
            f"100 {len(REAL)}\n".encode() + REAL[cut:],
        ]):
            capture = deploy.ServiceReloadCapture()
            capture.poll()
            self.assertEqual("unknown", capture.report()["observedCompleteness"])
            capture.poll()
        self.assertEqual(1, len(capture.segments))
        self.assertEqual(REAL, capture.segments[0]["data"])
        self.assertEqual("complete", capture.report()["observedCompleteness"])

    def test_inode_mismatch_or_short_read_is_unknown_and_does_not_advance(self):
        for raw in (f"101 {len(REAL)}\n".encode() + REAL,
                    f"100 {len(REAL)}\n".encode() + REAL[:-1]):
            with self.subTest(raw_size=len(raw)), patch.object(
                deploy, "service_log_snapshot",
                side_effect=[{PATH: (100, 0)}, {PATH: (100, len(REAL))}],
            ), patch.object(deploy.subprocess, "check_output", return_value=raw):
                capture = deploy.ServiceReloadCapture()
                capture.poll()
            self.assertEqual((100, 0), capture.cursor[PATH])
            self.assertEqual([], capture.report()["targets"])
            self.assertEqual("unknown", capture.report()["result"])

    def test_failed_baseline_cannot_replay_old_records_on_recovery(self):
        with patch.object(deploy, "service_log_snapshot", side_effect=[
            subprocess.CalledProcessError(1, "stat"), {PATH: (100, len(REAL))}
        ]), patch.object(deploy.subprocess, "check_output") as read:
            capture = deploy.ServiceReloadCapture()
            capture.poll()
        read.assert_not_called()
        self.assertEqual("unknown", capture.report()["result"])
        self.assertEqual([], capture.segments)

    def test_save_keeps_raw_failure_and_policy_reclaim_evidence(self):
        with patch.object(deploy, "service_log_snapshot", return_value={}):
            capture = deploy.ServiceReloadCapture()
        capture.issues.clear()
        capture.segments = [{"path": PATH, "inode": 100, "offset": 0, "data": REAL}]
        with tempfile.TemporaryDirectory() as directory:
            capture.save(Path(directory), KILL)
            report = json.loads((Path(directory) / "all-target-reloads.json").read_text())
            self.assertIn("status=4, message=null", (Path(directory) / "service-reload.log").read_text())
        row = next(t for t in report["targets"] if t["pid"] == 15566)
        self.assertEqual("failed", row["status"])
        self.assertEqual("system_reclaimed", row["failureReason"])
        self.assertEqual("umms_selfcheck", row["systemReclaimEvidence"]["reason"])

    def test_strict_accepts_observed_success_with_unknown_population_and_clear_stdout(self):
        text = "\n".join(line for line in REAL.decode().splitlines()
                         if "com.android.settings:provider" not in line)
        report = deploy.parse_reloads(text, deploy.PACKAGE)
        self.assertEqual("unknown", report["populationCompleteness"])
        deploy.require_observed_reloads(report)
        stream = io.StringIO()
        with contextlib.redirect_stdout(stream):
            deploy.print_reload_results(report)
        self.assertIn("target population completeness=unknown", stream.getvalue())
        self.assertIn("observed success=9", stream.getvalue())

    def test_strict_rejects_failure_unknown_and_capture_gap(self):
        success = "\n".join(line for line in REAL.decode().splitlines()
                            if "com.android.settings:provider" not in line)
        reports = [
            deploy.parse_reloads(REAL.decode(), deploy.PACKAGE),
            deploy.parse_reloads("", deploy.PACKAGE),
            deploy.parse_reloads(success, deploy.PACKAGE, capture_issues=["lost log inode"]),
        ]
        for report in reports:
            with self.subTest(result=report["result"]), self.assertRaises(RuntimeError):
                deploy.require_observed_reloads(report)


class FakeClock:
    def __init__(self):
        self.now = 0

    def monotonic(self):
        return self.now

    def sleep(self, seconds):
        self.now += seconds


class AsyncWaitTest(unittest.TestCase):
    def test_late_failed_outcome_is_not_hidden_by_earlier_successes(self):
        clock = FakeClock()
        pending = "\n".join(line for line in REAL.decode().splitlines()
                            if "Auto hot reload failed" not in line).encode() + b"\n"
        with patch.object(deploy, "service_log_snapshot", return_value={}):
            capture = deploy.ServiceReloadCapture()
        capture.issues.clear()

        def poll(timeout):
            clock.now += 0.1
            data = pending if clock.now < 1 else REAL
            capture.segments = [{"path": PATH, "inode": 100, "offset": 0, "data": data}]

        with tempfile.TemporaryDirectory() as directory, \
                patch.object(capture, "poll", side_effect=poll), \
                patch.object(deploy.time, "monotonic", side_effect=clock.monotonic), \
                patch.object(deploy.time, "sleep", side_effect=clock.sleep), \
                patch.object(deploy, "adb", return_value=KILL):
            report = deploy.settle_service_reloads(capture, Path(directory), timeout=6)
            self.assertTrue((Path(directory) / "reload-events.log").is_file())
        self.assertEqual(1, report["counts"]["failed"])
        self.assertEqual(9, report["counts"]["success"])
        self.assertLessEqual(clock.now, 6)

    def test_never_completed_outcomes_wait_only_to_bound_and_stay_unknown(self):
        clock = FakeClock()
        pending = "\n".join(line for line in REAL.decode().splitlines()
                            if "Auto hot reload" not in line).encode() + b"\n"
        with patch.object(deploy, "service_log_snapshot", return_value={}):
            capture = deploy.ServiceReloadCapture()
        capture.issues.clear()
        capture.segments = [{"path": PATH, "inode": 100, "offset": 0, "data": pending}]
        with tempfile.TemporaryDirectory() as directory, \
                patch.object(capture, "poll"), \
                patch.object(deploy.time, "monotonic", side_effect=clock.monotonic), \
                patch.object(deploy.time, "sleep", side_effect=clock.sleep), \
                patch.object(deploy, "adb") as adb:
            report = deploy.settle_service_reloads(capture, Path(directory), timeout=2)
        adb.assert_not_called()
        self.assertGreaterEqual(clock.now, 1.5)
        self.assertLessEqual(clock.now, 2)
        self.assertEqual(10, report["counts"]["unknown"])
        self.assertEqual("unknown", report["result"])


if __name__ == "__main__":
    unittest.main()
