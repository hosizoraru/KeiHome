import contextlib
import csv
import io
import json
from pathlib import Path
import sqlite3
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import analyze_trace as analyzer


EVENT = 205_854_370_767_114
PID = 22222
MS = 1_000_000


class AttributionTest(unittest.TestCase):
    def setUp(self):
        self.database = sqlite3.connect(":memory:")
        self.addCleanup(self.database.close)
        self.database.executescript("""
            CREATE TABLE trace_bounds(start_ts INTEGER, end_ts INTEGER);
            CREATE TABLE process(upid INTEGER, pid INTEGER, name TEXT);
            CREATE TABLE thread(utid INTEGER, tid INTEGER, name TEXT, upid INTEGER);
            CREATE TABLE thread_track(id INTEGER, utid INTEGER);
            CREATE TABLE slice(
                id INTEGER, ts INTEGER, dur INTEGER, name TEXT,
                track_id INTEGER, parent_id INTEGER
            );
            CREATE TABLE actual_frame_timeline_slice(
                id INTEGER, ts INTEGER, dur INTEGER, name TEXT,
                upid INTEGER, track_id INTEGER, jank_type TEXT
            );
            CREATE TABLE stats(
                name TEXT, value INTEGER, idx INTEGER, severity TEXT, source TEXT
            );
        """)
        self.database.execute("INSERT INTO trace_bounds VALUES (?, ?)", (
            EVENT - 1000 * MS, EVENT + 3000 * MS,
        ))
        self.database.execute("INSERT INTO process VALUES (1, ?, 'launcher')", (PID,))
        self.thread(1, PID, "launcher")
        self.thread(2, 22282, "1.ui")
        self.thread(3, 22320, "DartWorker")

    def thread(self, utid, tid, name):
        self.database.execute("INSERT INTO thread VALUES (?, ?, ?, 1)", (utid, tid, name))
        self.database.execute("INSERT INTO thread_track VALUES (?, ?)", (utid, utid))

    def collector(self, identifier, offset_ms, duration_ms, name="CollectOldGeneration",
                  thread=2, parent=None):
        self.database.execute("INSERT INTO slice VALUES (?, ?, ?, ?, ?, ?)", (
            identifier, EVENT + int(offset_ms * MS), int(duration_ms * MS),
            name, thread, parent,
        ))

    def frame(self, identifier, offset_ms, duration_ms, jank="App Deadline Missed"):
        self.database.execute(
            "INSERT INTO actual_frame_timeline_slice VALUES (?, ?, ?, 'frame', 1, 99, ?)",
            (identifier, EVENT + int(offset_ms * MS), int(duration_ms * MS), jank),
        )

    def rows(self, before_ns=0, after_ns=1500 * MS):
        cursor = self.database.execute(analyzer.build_query(PID, EVENT, before_ns, after_ns))
        columns = [c[0] for c in cursor.description]
        self.assertEqual(list(analyzer.COLUMNS), columns)
        return [dict(zip(columns, row)) for row in cursor]

    def report(self, before_ns=0, after_ns=1500 * MS):
        return analyzer.summarize(self.rows(before_ns, after_ns), PID, EVENT, before_ns, after_ns)

    def test_late_large_gc_does_not_overlap_actual_missed_frames(self):
        self.collector(10, 899.139635, 52.054583)
        self.frame(20, 80, 24)
        self.frame(21, 880, 8, "None")
        report = self.report()
        gc = report["ui_gc"]["slices"][0]
        self.assertEqual(899.139635, gc["offset_ms"])
        self.assertEqual(52.054583, gc["duration_ms"])
        self.assertEqual(0, gc["overlapping_missed_frame_count"])
        self.assertEqual(0, report["ui_gc"]["overlapping_missed_frame_count"])
        self.assertEqual(1, report["frames"]["app_deadline_missed_count"])

    def test_real_overlap_not_any_slow_or_earlier_frame_and_half_open_edges(self):
        self.collector(10, 100, 50)
        self.frame(20, 80, 20)  # Ends exactly at GC start.
        self.frame(21, 150, 20)  # Starts exactly at GC end.
        self.frame(22, 110, 5, "App Deadline Missed,Buffer Stuffing")
        self.frame(23, 120, 5, "SurfaceFlinger Deadline Missed")
        report = self.report()
        self.assertEqual([22], report["ui_gc"]["slices"][0]["overlapping_missed_frame_ids"])
        self.assertEqual(1, report["ui_gc"]["overlapping_missed_frame_count"])

    def test_partial_coverage_clips_timings_and_reports_actual_bounds(self):
        self.database.execute("UPDATE trace_bounds SET start_ts=?, end_ts=?", (
            EVENT + 50 * MS, EVENT + 900 * MS,
        ))
        self.collector(10, 0, 100)
        self.collector(11, 850, 100)
        self.frame(20, 0, 40)  # Outside trace bounds, so no observed overlap.
        report = self.report()
        coverage = report["coverage"]
        self.assertFalse(coverage["fully_covered"])
        self.assertFalse(coverage["event_in_trace"])
        self.assertEqual(EVENT + 50 * MS, coverage["trace_bounds"]["start_ns"])
        self.assertEqual(EVENT + 900 * MS, coverage["observed_end_ns"])
        self.assertEqual(850, coverage["observed_duration_ms"])
        self.assertAlmostEqual(850 / 1500, coverage["coverage_fraction"])
        self.assertEqual(100, report["ui_gc"]["observed_union_duration_ms"])
        self.assertEqual(100, report["ui_gc"]["slices"][0]["duration_ms"])
        self.assertEqual(0, report["ui_gc"]["overlapping_missed_frame_count"])

    def test_entire_window_outside_trace_has_zero_observed_coverage(self):
        self.database.execute("UPDATE trace_bounds SET end_ts=?", (EVENT - MS,))
        coverage = self.report()["coverage"]
        self.assertEqual(0, coverage["observed_duration_ms"])
        self.assertEqual(0, coverage["coverage_fraction"])
        self.assertIsNone(coverage["observed_start_ns"])
        self.assertFalse(coverage["fully_covered"])

    def test_nested_collectors_preserve_raw_evidence_and_deduplicate_totals(self):
        self.collector(10, 100, 50, "CollectNewGeneration")
        self.collector(11, 101, 48, "Scavenge", parent=10)
        self.collector(12, 102, 10, "CollectOldGeneration", parent=11)
        self.frame(20, 105, 8)
        gc = self.report()["ui_gc"]
        self.assertEqual(3, gc["raw_slice_count"])
        self.assertEqual(1, gc["deduplicated_pause_count"])
        self.assertEqual(50, gc["observed_union_duration_ms"])
        self.assertEqual([None, 10, 10], [r["nested_under_slice_id"] for r in gc["slices"]])
        self.assertEqual(1, gc["overlapping_missed_frame_count"])

    def test_distinct_ui_threads_are_not_deduplicated_but_wall_time_is_union(self):
        self.collector(10, 100, 50, thread=1)
        self.collector(11, 110, 20, thread=2)
        gc = self.report()["ui_gc"]
        self.assertEqual(2, gc["deduplicated_pause_count"])
        self.assertEqual(50, gc["observed_union_duration_ms"])

    def test_worker_marking_and_worker_collectors_are_excluded(self):
        self.collector(10, 100, 200, "ConcurrentMark", thread=3)
        self.collector(11, 100, 80, "ParallelMark", thread=3)
        self.collector(12, 100, 50, thread=3)
        self.collector(13, 100, 1, "ConcurrentMark", thread=2)
        # Even a misleading phase label cannot promote a DartWorker to UI.
        self.collector(14, 100, 2, "UI.Build", thread=3)
        self.frame(20, 110, 30)
        report = self.report()
        self.assertEqual([], report["ui_gc"]["slices"])
        self.assertEqual(0, report["ui_gc"]["observed_union_duration_ms"])
        self.assertEqual(4, len(report["excluded_gc_or_marking_slices"]))
        self.assertEqual([], report["ui_phases"]["slices"])

    def test_ui_phase_labels_identify_renamed_ui_thread(self):
        self.thread(4, 22400, "renamed Flutter thread")
        self.collector(10, 20, 4, "UI.Build", thread=4)
        self.collector(11, 25, 5, "UI.Layout", thread=4)
        self.collector(12, 30, 1, "UI.Finalize", thread=4)
        self.collector(13, 21, 1, "Scavenge", thread=4)
        report = self.report()
        self.assertEqual(1, report["ui_gc"]["deduplicated_pause_count"])
        self.assertEqual(4, report["ui_phases"]["summary"]["UI.Build"]["max_complete_duration_ms"])
        self.assertEqual(4, report["ui_threads"][0]["utid"])

    def test_crossing_start_and_end_included_and_disjoint_slices_excluded(self):
        self.collector(10, -10, 20)
        self.collector(11, 1490, 20)
        self.collector(12, -20, 20)  # Touches start, no interval inside window.
        self.collector(13, 1500, 20)
        self.frame(20, -5, 7)
        report = self.report()
        self.assertEqual([10, 11], [r["id"] for r in report["ui_gc"]["slices"]])
        self.assertEqual(-10, report["ui_gc"]["slices"][0]["offset_ms"])
        self.assertEqual(20, report["ui_gc"]["observed_union_duration_ms"])
        self.assertEqual(1, report["ui_gc"]["overlapping_missed_frame_count"])
        wider = self.report(before_ns=30 * MS)
        self.assertEqual(3, wider["ui_gc"]["raw_slice_count"])

    def test_unfinished_gc_and_frames_have_unknown_overlap_not_zero_duration(self):
        self.collector(10, 100, -0.000001)
        self.frame(20, 110, -0.000001)
        report = self.report()
        self.assertTrue(report["ui_gc"]["slices"][0]["incomplete"])
        self.assertIsNone(report["ui_gc"]["slices"][0]["duration_ms"])
        self.assertIsNone(report["ui_gc"]["slices"][0]["overlapping_missed_frame_count"])
        self.assertEqual(1, report["frames"]["incomplete_slice_count"])
        self.assertEqual(0, report["frames"]["complete_missed_interval_count"])

    def test_stats_are_relevant_nonzero_bounded_and_explicitly_global(self):
        values = [
            (f"ftrace_cpu_overrun_{n:03}", 1, n, "info", "trace")
            for n in range(analyzer.STAT_LIMIT + 2)
        ] + [
            ("irrelevant_info", 9, None, "info", "trace"),
            ("parse_error_zero", 0, None, "error", "trace"),
            ("unexpected_error", 1, None, "error", "trace"),
        ]
        self.database.executemany("INSERT INTO stats VALUES (?, ?, ?, ?, ?)", values)
        diagnostics = self.report()["diagnostics"]
        self.assertEqual(analyzer.STAT_LIMIT, len(diagnostics["stats"]))
        self.assertEqual(analyzer.STAT_LIMIT + 3, diagnostics["total_matching"])
        self.assertTrue(diagnostics["truncated"])
        self.assertIn("whole-trace", diagnostics["scope"])

    def test_one_processor_invocation_query_file_csv_and_output(self):
        self.collector(10, 100, 50)
        with tempfile.TemporaryDirectory(prefix="launcher test ") as directory:
            trace = Path(directory) / "input trace.pftrace"
            processor = Path(directory) / "old processor"
            output = Path(directory) / "result.json"
            trace.touch()
            processor.touch()
            query_paths = []

            def run(command, **kwargs):
                self.assertEqual(str(processor.resolve()), command[0])
                self.assertEqual("-q", command[1])
                self.assertEqual(str(trace.resolve()), command[3])
                query_file = Path(command[2])
                query_paths.append(query_file)
                # sqlite.execute rejects multiple statements, just as the old
                # processor interface does; exercise the real generated SQL.
                cursor = self.database.execute(query_file.read_text())
                buffer = io.StringIO()
                writer = csv.writer(buffer)
                writer.writerow([c[0] for c in cursor.description])
                writer.writerows([
                    ["[NULL]" if value is None else value for value in row]
                    for row in cursor
                ])
                return subprocess.CompletedProcess(command, 0, buffer.getvalue(), "loaded")

            with patch.object(analyzer.subprocess, "run", side_effect=run) as invoke:
                result = analyzer.main([
                    "--trace", str(trace), "--pid", str(PID), "--event-ns", str(EVENT),
                    "--trace-processor", str(processor), "--output", str(output),
                ])
            self.assertEqual(0, result)
            invoke.assert_called_once()
            self.assertFalse(query_paths[0].exists())
            report = json.loads(output.read_text())
            self.assertEqual(1, report["ui_gc"]["raw_slice_count"])
            self.assertEqual(1500, report["window"]["after_ms"])
            self.assertCountEqual([output, processor, trace], Path(directory).iterdir())

    def test_processor_errors_and_bad_csv_fail_explicitly(self):
        for response in (
            subprocess.CompletedProcess([], 1, "", "no such table: actual_frame_timeline_slice"),
            subprocess.CompletedProcess([], 0, "wrong,columns\n1,2\n", ""),
        ):
            with self.subTest(response=response), patch.object(
                analyzer.subprocess, "run", return_value=response,
            ):
                with self.assertRaises(ValueError):
                    analyzer.query_trace(Path("processor"), Path("trace"), "SELECT 1")

    def test_missing_pid_is_visible_as_no_matching_process(self):
        self.database.execute("DELETE FROM process")
        report = self.report()
        self.assertFalse(report["target_process_found"])
        self.assertEqual([], report["processes"])
        self.assertEqual(0, report["frames"]["slice_count"])

    def test_reused_pid_cannot_merge_two_process_lifetimes(self):
        self.database.execute("INSERT INTO process VALUES (2, ?, 'replacement')", (PID,))
        with self.assertRaisesRegex(ValueError, "PID was reused"):
            self.report()


class InputValidationTest(unittest.TestCase):
    def test_huge_pid_event_and_sql_text_are_rejected_before_subprocess(self):
        base = [
            "--trace", "unused", "--trace-processor", "unused",
            "--pid", str(PID), "--event-ns", str(EVENT),
        ]
        bad = (
            ("--pid", str(analyzer.MAX_PID + 1)), ("--pid", "9" * 10000),
            ("--pid", "1 OR 1=1"), ("--pid", "0"),
            ("--event-ns", str(analyzer.MAX_NS + 1)), ("--event-ns", "9" * 10000),
            ("--event-ns", "-1"), ("--after-ms", "1e999999"),
            ("--after-ms", "NaN"), ("--after-ms", "Infinity"),
            ("--after-ms", "0.0000001"), ("--before-ms", "-1"),
        )
        with patch.object(analyzer.subprocess, "run") as invoke:
            for key, value in bad:
                with self.subTest(key=key, value=value[:40]), contextlib.redirect_stderr(io.StringIO()):
                    with self.assertRaises(SystemExit) as error:
                        analyzer.main(base + [key, value])
                    self.assertEqual(2, error.exception.code)
            invoke.assert_not_called()

    def test_arithmetic_overflow_underflow_empty_window_and_exact_ns(self):
        for values in (
            (PID, analyzer.MAX_NS, 0, 1), (PID, 1, 2, 1),
            (PID, EVENT, 0, 0), (analyzer.MAX_PID + 1, EVENT, 0, MS),
        ):
            with self.subTest(values=values), self.assertRaises(ValueError):
                analyzer.build_query(*values)
        self.assertEqual(1, analyzer.milliseconds("0.000001"))
        self.assertEqual(1_500_000_001, analyzer.milliseconds("1500.000001"))
        self.assertEqual(
            (analyzer.MAX_NS - 1, analyzer.MAX_NS),
            analyzer.window(PID, analyzer.MAX_NS - 1, 0, 1),
        )


if __name__ == "__main__":
    unittest.main()
