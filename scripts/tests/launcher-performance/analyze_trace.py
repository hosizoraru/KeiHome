#!/usr/bin/env python3
"""Offline interval attribution for saved launcher Perfetto traces (stdlib only)."""

import argparse
import csv
from decimal import Decimal
import io
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile


MAX_NS = (1 << 63) - 1
MAX_PID = (1 << 31) - 1
STAT_LIMIT = 128
COLLECTORS = ("CollectOldGeneration", "Scavenge", "CollectNewGeneration")
PHASES = ("UI.Build", "UI.Layout", "UI.Finalize")
MARKING = ("ConcurrentMark", "ParallelMark")
COLUMNS = (
    "kind", "id", "ts", "dur", "name", "utid", "tid", "thread_name",
    "track_id", "parent_id", "jank_type", "value", "idx", "severity", "source",
)


def bounded_integer(text, maximum, minimum=0):
    if not re.fullmatch(r"[0-9]{1,19}", text):
        raise argparse.ArgumentTypeError("expected a bounded decimal integer")
    value = int(text)
    if not minimum <= value <= maximum:
        raise argparse.ArgumentTypeError(f"expected {minimum}..{maximum}")
    return value


def milliseconds(text):
    if len(text) > 32 or not re.fullmatch(r"[0-9]+(?:\.[0-9]{1,6})?", text):
        raise argparse.ArgumentTypeError("expected nonnegative milliseconds, at most 6 decimals")
    value = Decimal(text) * 1_000_000
    if value > MAX_NS:
        raise argparse.ArgumentTypeError("duration exceeds signed 64-bit nanoseconds")
    return int(value)


def window(pid, event_ns, before_ns, after_ns):
    for value, low, high in (
        (pid, 1, MAX_PID), (event_ns, 0, MAX_NS),
        (before_ns, 0, MAX_NS), (after_ns, 0, MAX_NS),
    ):
        if type(value) is not int or not low <= value <= high:
            raise ValueError("PID/timestamps must be bounded integers")
    start, end = event_ns - before_ns, event_ns + after_ns
    if start < 0 or end > MAX_NS or start == end:
        raise ValueError("analysis window must be nonempty and within signed 64-bit timestamps")
    return start, end


def build_query(pid, event_ns, before_ns, after_ns):
    start, end = window(pid, event_ns, before_ns, after_ns)

    def row(kind, table="", **values):
        fields = {"kind": f"'{kind}'", **values}
        return "SELECT " + ", ".join(
            f"{fields.get(column, 'NULL')} AS {column}" for column in COLUMNS
        ) + (" FROM " + table if table else "")

    # Compare duration with a difference instead of adding ts+dur in SQLite,
    # whose signed integer overflow silently promotes values to floating point.
    def intersects(alias):
        return (
            f"{alias}.ts < {end} AND ({alias}.dur = -1 OR "
            f"{alias}.dur > {start} - {alias}.ts OR "
            f"({alias}.dur = 0 AND {alias}.ts >= {start}))"
        )

    names = ", ".join(f"'{name}'" for name in COLLECTORS + PHASES + MARKING)
    ctes = f"""WITH target_process AS (
        SELECT upid, pid, name FROM process WHERE pid = {pid}
    ), selected_slices AS (
        SELECT s.id, s.ts, s.dur, s.name, t.utid, t.tid, t.name AS thread_name,
               s.track_id, s.parent_id
        FROM slice s JOIN thread_track tt ON tt.id = s.track_id
        JOIN thread t ON t.utid = tt.utid
        JOIN target_process p ON p.upid = t.upid
        WHERE s.name IN ({names}) AND ({intersects('s')})
    ), relevant_stats AS (
        SELECT name, value, idx, severity, source FROM stats
        WHERE value != 0 AND (
            severity IN ('error', 'data_loss') OR
            name LIKE '%lost%' OR name LIKE '%drop%' OR
            name LIKE '%overrun%' OR name LIKE '%overwrite%' OR
            name LIKE '%parse_error%' OR name LIKE '%tokenizer_error%'
        )
    ), limited_stats AS (
        SELECT * FROM relevant_stats ORDER BY name, idx LIMIT {STAT_LIMIT}
    )
    """
    branches = [
        row("bounds", "trace_bounds", ts="start_ts", dur="end_ts"),
        row("process", "target_process", id="upid", tid="pid", name="name"),
        row("slice", "selected_slices", **{
            key: key for key in (
                "id", "ts", "dur", "name", "utid", "tid", "thread_name",
                "track_id", "parent_id",
            )
        }),
        row(
            "frame",
            "actual_frame_timeline_slice f JOIN target_process p USING(upid)"
            f" WHERE ({intersects('f')})",
            id="f.id", ts="f.ts", dur="f.dur", name="f.name",
            track_id="f.track_id", jank_type="f.jank_type",
        ),
        row("stat", "limited_stats", **{
            key: key for key in ("name", "value", "idx", "severity", "source")
        }),
        row("stat_count", "relevant_stats", value="COUNT(*)"),
    ]
    return ctes + "\nUNION ALL\n".join(branches)


def query_trace(trace_processor, trace, sql):
    # Older trace_processor_shell accepts one SELECT via a query file. Keep
    # that file alive through subprocess completion and load the trace once.
    with tempfile.TemporaryDirectory(prefix="launcher-attribution-") as directory:
        query = Path(directory) / "query.sql"
        query.write_text(sql, encoding="utf-8")
        result = subprocess.run(
            [str(trace_processor), "-q", str(query), str(trace)],
            capture_output=True, text=True, check=False,
        )
    if result.returncode:
        raise ValueError(f"trace processor failed ({result.returncode}): {result.stderr.strip()}")
    reader = csv.DictReader(io.StringIO(result.stdout))
    if reader.fieldnames != list(COLUMNS):
        raise ValueError(f"unexpected trace processor CSV schema: {reader.fieldnames}")
    rows = []
    for record in reader:
        if None in record or any(value is None for value in record.values()):
            raise ValueError("malformed trace processor CSV row")
        rows.append({
            key: None if value == "[NULL]" else value
            for key, value in record.items()
        })
    return rows


def overlap(a, b):
    return max(a[0], b[0]) < min(a[1], b[1])


def union_duration(intervals):
    total = 0
    edge = None
    for start, end in sorted(intervals):
        if start >= end:
            continue
        if edge is None or start >= edge:
            total += end - start
        elif end > edge:
            total += end - edge
        edge = max(end, edge if edge is not None else end)
    return total


def summarize(rows, pid, event_ns, before_ns=0, after_ns=1_500_000_000):
    start, end = window(pid, event_ns, before_ns, after_ns)
    processes = [r for r in rows if r["kind"] == "process"]
    if len(processes) > 1:
        raise ValueError("PID was reused; capture a single process lifetime")
    bounds = [r for r in rows if r["kind"] == "bounds"]
    if len(bounds) != 1:
        raise ValueError("expected exactly one trace_bounds row")
    trace_start, trace_end = int(bounds[0]["ts"]), int(bounds[0]["dur"])
    if not 0 <= trace_start <= trace_end <= MAX_NS:
        raise ValueError("invalid trace_bounds")
    covered_start, covered_end = max(start, trace_start), min(end, trace_end)
    observed_ns = max(0, covered_end - covered_start)

    def interval(record):
        if record["duration_ns"] < 0:
            return None
        a = max(record["start_ns"], covered_start)
        b = min(record["end_ns"], covered_end)
        return (a, b) if a < b else None

    def timed(row):
        ts, dur = int(row["ts"]), int(row["dur"])
        return {
            "id": int(row["id"]), "name": row["name"],
            "start_ns": ts, "end_ns": ts + dur if dur >= 0 else None,
            "offset_ms": (ts - event_ns) / 1_000_000,
            "duration_ns": dur, "duration_ms": dur / 1_000_000 if dur >= 0 else None,
            "incomplete": dur < 0,
            "observed_duration_ns": max(
                0, min(ts + dur, covered_end) - max(ts, covered_start)
            ) if dur >= 0 else None,
            "track_id": int(row["track_id"]),
        }

    frames = []
    slices = []
    for row in rows:
        if row["kind"] == "frame":
            record = timed(row)
            record["jank_type"] = row["jank_type"] or ""
            record["app_deadline_missed"] = (
                "App Deadline Missed" in (
                    label.strip() for label in record["jank_type"].split(",")
                )
            )
            frames.append(record)
        elif row["kind"] == "slice":
            record = timed(row)
            record.update(
                utid=int(row["utid"]), tid=int(row["tid"]),
                thread_name=row["thread_name"],
                parent_id=int(row["parent_id"]) if row["parent_id"] is not None else None,
            )
            slices.append(record)
    frames.sort(key=lambda r: (r["start_ns"], r["id"]))
    slices.sort(key=lambda r: (r["start_ns"], r["id"]))
    phase_threads = {r["utid"] for r in slices if r["name"] in PHASES}

    def is_ui(record):
        name = record["thread_name"] or ""
        if name.lower().startswith("dartworker"):
            return False
        return (
            record["tid"] == pid or
            re.fullmatch(r"(?:[0-9]+\.)?ui", name, re.IGNORECASE) is not None or
            record["utid"] in phase_threads
        )

    missed = [
        (frame, interval(frame)) for frame in frames
        if frame["app_deadline_missed"] and interval(frame) is not None
    ]
    gc = [r for r in slices if r["name"] in COLLECTORS and is_ui(r)]
    roots = []
    for record in sorted(gc, key=lambda r: (
        r["start_ns"], -(r["duration_ns"]), r["id"],
    )):
        # Containment on the same synchronous thread track identifies nested
        # collector instrumentation, without merging different UI threads.
        parent = next((
            root for root in reversed(roots)
            if not record["incomplete"] and not root["incomplete"] and
            root["utid"] == record["utid"] and root["track_id"] == record["track_id"] and
            root["start_ns"] <= record["start_ns"] and root["end_ns"] >= record["end_ns"]
        ), None)
        record["nested_under_slice_id"] = parent["id"] if parent else None
        observed = interval(record)
        record["overlapping_missed_frame_ids"] = None if record["incomplete"] else [
            frame["id"] for frame, frame_interval in missed
            if observed is not None and overlap(observed, frame_interval)
        ]
        record["overlapping_missed_frame_count"] = (
            None if record["incomplete"] else len(record["overlapping_missed_frame_ids"])
        )
        if parent is None:
            roots.append(record)
    gc.sort(key=lambda r: (r["start_ns"], r["id"]))
    phases = [r for r in slices if r["name"] in PHASES and is_ui(r)]
    excluded = [
        dict(r, exclusion_reason=(
            "marking instrumentation is not a named UI collector pause"
            if r["name"] in MARKING else "collector is not on an identified UI thread"
        ))
        for r in slices if r["name"] in MARKING or (
            r["name"] in COLLECTORS and not is_ui(r)
        )
    ]
    stats = [{
        "name": r["name"], "value": int(r["value"]),
        "idx": int(r["idx"]) if r["idx"] is not None else None,
        "severity": r["severity"], "source": r["source"],
    } for r in rows if r["kind"] == "stat"]
    stat_count = next(int(r["value"]) for r in rows if r["kind"] == "stat_count")
    ui_threads = {r["utid"]: {
        "utid": r["utid"], "tid": r["tid"], "name": r["thread_name"],
    } for r in slices if is_ui(r)}
    phase_summary = {}
    for name in PHASES:
        matches = [r for r in phases if r["name"] == name]
        phase_summary[name] = {
            "slice_count": len(matches),
            "incomplete_slice_count": sum(r["incomplete"] for r in matches),
            "observed_union_duration_ms": union_duration([
                interval(r) for r in matches if interval(r) is not None
            ]) / 1_000_000,
            "max_complete_duration_ms": max(
                (r["duration_ms"] for r in matches if not r["incomplete"]), default=None,
            ),
        }
    return {
        "schema_version": 1, "pid": pid, "event_ns": event_ns,
        "window": {
            "start_ns": start, "end_ns": end,
            "before_ms": before_ns / 1_000_000, "after_ms": after_ns / 1_000_000,
            "interval_semantics": "half-open [start, end)",
        },
        "coverage": {
            "trace_bounds": {"start_ns": trace_start, "end_ns": trace_end},
            "fully_covered": trace_start <= start and end <= trace_end,
            "event_in_trace": trace_start <= event_ns < trace_end,
            "observed_start_ns": covered_start if observed_ns else None,
            "observed_end_ns": covered_end if observed_ns else None,
            "observed_duration_ms": observed_ns / 1_000_000,
            "coverage_fraction": observed_ns / (end - start),
        },
        "target_process_found": bool(processes),
        "processes": [{
            "upid": int(r["id"]), "name": r["name"],
        } for r in processes],
        "ui_threads": sorted(ui_threads.values(), key=lambda r: r["utid"]),
        "frames": {
            "slice_count": len(frames),
            "incomplete_slice_count": sum(r["incomplete"] for r in frames),
            "app_deadline_missed_count": sum(r["app_deadline_missed"] for r in frames),
            "complete_missed_interval_count": len(missed), "slices": frames,
        },
        "ui_gc": {
            "raw_slice_count": len(gc), "deduplicated_pause_count": len(roots),
            "incomplete_slice_count": sum(r["incomplete"] for r in gc),
            "observed_union_duration_ms": union_duration([
                interval(r) for r in roots if interval(r) is not None
            ]) / 1_000_000,
            "overlapping_missed_frame_count": len({
                frame_id for r in roots for frame_id in (r["overlapping_missed_frame_ids"] or [])
            }),
            "slices": gc,
        },
        "ui_phases": {"summary": phase_summary, "slices": phases},
        "excluded_gc_or_marking_slices": excluded,
        "diagnostics": {
            "scope": "nonzero relevant whole-trace ingestion stats, not event-window counters",
            "limit": STAT_LIMIT, "total_matching": stat_count,
            "truncated": stat_count > len(stats), "stats": stats,
        },
        "limits": [
            "Interval overlap is association only; it does not establish frame-drop causality.",
            "trace_bounds coverage is an envelope, not proof of uninterrupted instrumentation.",
            "Only the three named UI collectors are counted; marking and other threads are excluded.",
            "UI identity uses main TID, ui/N.ui names, or observed UI phase labels; DartWorker is excluded.",
            "Nested collectors on one thread track are retained but deduplicated in pause totals.",
            "Unfinished slices have unknown duration and are excluded from interval overlap/timing totals.",
            "Frame counts are FrameTimeline slice IDs, not deduplicated presentation tokens.",
        ],
    }


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--trace", type=Path, required=True)
    parser.add_argument("--pid", type=lambda s: bounded_integer(s, MAX_PID, 1), required=True)
    parser.add_argument("--event-ns", type=lambda s: bounded_integer(s, MAX_NS), required=True)
    parser.add_argument("--before-ms", type=milliseconds, default="0")
    parser.add_argument("--after-ms", type=milliseconds, default="1500")
    parser.add_argument("--trace-processor", type=Path, required=True)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args(argv)
    try:
        sql = build_query(args.pid, args.event_ns, args.before_ms, args.after_ms)
        trace, processor = args.trace.resolve(), args.trace_processor.resolve()
        if not trace.is_file() or not processor.is_file():
            raise ValueError("trace and trace processor must be existing files")
        if args.output and args.output.resolve() in (trace, processor):
            raise ValueError("output must not overwrite the trace or trace processor")
        rows = query_trace(processor, trace, sql)
        report = summarize(rows, args.pid, args.event_ns, args.before_ms, args.after_ms)
        report["trace"] = str(trace)
        report["trace_processor"] = str(processor)
        text = json.dumps(report, indent=2, allow_nan=False) + "\n"
        if args.output:
            destination = args.output.resolve()
            with tempfile.NamedTemporaryFile(
                mode="w", encoding="utf-8", dir=destination.parent, delete=False,
                prefix=destination.name + ".", suffix=".tmp",
            ) as temporary:
                temporary_path = Path(temporary.name)
                try:
                    temporary.write(text)
                    temporary.close()
                    os.replace(temporary_path, destination)
                finally:
                    temporary_path.unlink(missing_ok=True)
        else:
            print(text, end="")
    except (OSError, ValueError, csv.Error) as exc:
        parser.exit(2, f"error: {exc}\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
