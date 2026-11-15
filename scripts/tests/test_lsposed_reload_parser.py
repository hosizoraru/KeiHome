"""Hermetic regressions for the 12:47 nine-success/one-UMMS-reclaim transaction."""
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from lsposed_reload_parser import parse_reloads, plan_log_reads

FIXTURES = Path(__file__).with_name("fixtures")
REAL = (FIXTURES / "lsposed-reload-20261115.log").read_text()
KILL = (FIXTURES / "lsposed-reload-20261115-kill.log").read_text()
MODULE = "os.kei.hyper.home"
PROVIDER = "com.android.settings:provider"
OLD = "/data/adb/lspd/log/verbose_old.log"
NEW = "/data/adb/lspd/log/verbose_new.log"


def provider_lines():
    return "\n".join(line for line in REAL.splitlines() if PROVIDER in line)


class ReloadParserTest(unittest.TestCase):
    def parse(self, text=REAL, **kwargs):
        return parse_reloads(text, MODULE, **kwargs)

    def provider(self, report):
        return next(row for row in report["targets"] if row["process"] == PROVIDER)

    def test_real_ten_targets_nine_success_one_failed_never_global_success(self):
        report = self.parse()
        self.assertEqual({"success": 9, "failed": 1, "unknown": 0}, report["counts"])
        self.assertEqual("failed", report["result"])
        self.assertEqual("complete", report["observedCompleteness"])
        self.assertEqual("unknown", report["populationCompleteness"])
        provider = self.provider(report)
        self.assertEqual(15566, provider["pid"])
        self.assertEqual(4, provider["outcomes"][0]["status"])
        self.assertEqual("status=4, message=null", provider["detail"])
        self.assertEqual("unknown", provider["failureReason"])
        self.assertEqual(1, len(provider["starts"]))

    def test_other_modules_and_modules_with_same_prefix_do_not_pollute_results(self):
        noise = REAL.replace(MODULE, "another.module")
        prefix = REAL.replace(MODULE, MODULE + ".test")
        report = self.parse(noise + "\n" + prefix + "\n" + REAL)
        self.assertEqual({"success": 9, "failed": 1, "unknown": 0}, report["counts"])
        self.assertEqual("complete", report["observedCompleteness"])
        self.assertEqual(0, len(self.parse(noise + prefix)["targets"]))

    def test_success_and_failure_for_same_process_different_pids_are_distinct(self):
        successful = provider_lines().replace("15566", "25566")
        successful = successful.replace("failed for", "succeeded for").replace(
            ": status=4, message=null", ""
        )
        report = self.parse(provider_lines() + "\n" + successful)
        self.assertEqual({"success": 1, "failed": 1, "unknown": 0}, report["counts"])
        self.assertEqual({15566, 25566}, {row["pid"] for row in report["targets"]})

    def test_later_success_cannot_erase_an_earlier_failure(self):
        success = next(line for line in provider_lines().splitlines() if "failed for" in line)
        success = success.replace("failed for", "succeeded for").replace(
            ": status=4, message=null", ""
        ).replace("12:47:52.249", "12:47:53.249")
        report = self.parse(provider_lines() + "\n" + success)
        self.assertEqual("failed", self.provider(report)["status"])
        self.assertEqual(2, len(self.provider(report)["outcomes"]))
        self.assertEqual("unknown", report["observedCompleteness"])

    def test_outcome_without_start_is_unknown_even_when_successful(self):
        text = "\n".join(line for line in REAL.splitlines() if "succeeded for" in line)
        report = self.parse(text)
        self.assertEqual(9, report["counts"]["unknown"])
        self.assertEqual(0, report["counts"]["success"])
        self.assertEqual("unknown", report["observedCompleteness"])

    def test_start_without_outcome_is_unknown_not_success(self):
        text = "\n".join(line for line in REAL.splitlines() if "Auto hot reload" not in line)
        report = self.parse(text)
        self.assertEqual(10, report["counts"]["unknown"])
        self.assertEqual("unknown", report["result"])

    def test_missing_loading_context_cannot_attribute_a_generic_start(self):
        text = "\n".join(line for line in REAL.splitlines() if "Loading module" not in line)
        report = self.parse(text)
        self.assertEqual(0, report["counts"]["success"])
        self.assertTrue(any("unattributed start" in issue for issue in report["issues"]))

    def test_empty_or_unreadable_capture_has_unknown_coverage(self):
        for text in ("", "unrecognized log format"):
            report = self.parse(text)
            self.assertEqual("unknown", report["result"])
            self.assertEqual("unknown", report["populationCompleteness"])

    def test_exact_same_pid_and_time_confirms_policy_reclaim_without_claiming_oom(self):
        provider = self.provider(self.parse(system_log=KILL))
        self.assertEqual("system_reclaimed", provider["failureReason"])
        evidence = provider["systemReclaimEvidence"]
        self.assertEqual("umms_selfcheck", evidence["reason"])
        self.assertEqual(15566, evidence["pid"])
        self.assertEqual("2026-11-15 12:47:52.055", evidence["time"])
        self.assertEqual("failed", provider["status"])

    def test_status_four_or_unrelated_or_old_pressure_cannot_confirm_reclaim(self):
        for system_log in (
            "", "low_memory pressure", KILL.replace("15566", "15567"),
            KILL.replace(PROVIDER, "com.android.settings"),
            KILL.replace("12:47:52.055", "12:46:52.055"),
            KILL.replace("2026-11-15", "2026-11-14"),
            KILL.replace("umms_selfcheck", "user_request"),
        ):
            with self.subTest(system_log=system_log):
                row = self.provider(self.parse(system_log=system_log))
                self.assertEqual("unknown", row["failureReason"])
                self.assertNotIn("systemReclaimEvidence", row)

    def test_capture_gap_cannot_be_hidden_by_observed_successes(self):
        text = "\n".join(line for line in REAL.splitlines() if PROVIDER not in line)
        report = self.parse(text, capture_issues=["lost inode"])
        self.assertEqual(9, report["counts"]["success"])
        self.assertEqual("unknown", report["result"])
        self.assertEqual("unknown", report["observedCompleteness"])

    def test_all_observed_success_is_explicitly_scoped(self):
        text = "\n".join(line for line in REAL.splitlines() if PROVIDER not in line)
        report = self.parse(text)
        self.assertEqual("observed_success", report["result"])
        self.assertEqual("unknown", report["populationCompleteness"])

    def test_duplicate_mirror_records_do_not_duplicate_targets_or_attempts(self):
        report = self.parse(REAL + "\n" + REAL)
        self.assertEqual(10, len(report["targets"]))
        self.assertEqual("complete", report["observedCompleteness"])


class LogRangeTest(unittest.TestCase):
    def test_unchanged_stream_does_not_replay_preinstall_records(self):
        self.assertEqual(([], []), plan_log_reads({OLD: (1, 100)}, {OLD: (1, 100)}))

    def test_only_appended_bounded_range_is_read(self):
        reads, issues = plan_log_reads({OLD: (1, 100)}, {OLD: (1, 180)})
        self.assertEqual([dict(path=OLD, inode=1, offset=100, length=80, reset=False)], reads)
        self.assertEqual([], issues)

    def test_new_stream_and_renamed_old_inode_preserve_offsets(self):
        rotated = OLD + ".log"
        reads, issues = plan_log_reads(
            {OLD: (1, 100)}, {rotated: (1, 110), NEW: (2, 80)}
        )
        self.assertEqual({(1, 100, 10), (2, 0, 80)},
                         {(r["inode"], r["offset"], r["length"]) for r in reads})
        self.assertEqual([], issues)

    def test_replacement_with_disappeared_inode_marks_unknown(self):
        reads, issues = plan_log_reads({OLD: (1, 100)}, {OLD: (2, 180)})
        self.assertEqual(0, reads[0]["offset"])
        self.assertIn("disappeared", issues[0])

    def test_truncation_marks_unknown_and_reads_from_zero(self):
        reads, issues = plan_log_reads({OLD: (1, 100)}, {OLD: (1, 50)})
        self.assertEqual(0, reads[0]["offset"])
        self.assertTrue(reads[0]["reset"])
        self.assertIn("truncated", issues[0])


if __name__ == "__main__":
    unittest.main()
