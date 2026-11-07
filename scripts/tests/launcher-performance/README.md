# Offline launcher interval attribution

Python standard library only. No installation, devices, or builds.

```bash
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts/tests/launcher-performance -v
python3 scripts/tests/launcher-performance/analyze_trace.py \
  --trace .artifacts/research/launcher-open-20261107/physical-nine-after/baseline.pftrace \
  --pid 22222 --event-ns 205854370767114 \
  --trace-processor /Users/voyager/.local/share/perfetto/prebuilts/trace_processor_shell-98a41b80e9f60da0 \
  --output .artifacts/research/launcher-followup-20261107/old-drop-attribution.json
```

`--before-ms` defaults to 0; `--after-ms` defaults to 1500. Fractional
milliseconds accept up to six decimal places. Event timestamps must use the
trace's nanosecond clock domain. Without `--output`, JSON goes to stdout.

The processor loads the trace once, using a temporary `-q` file containing one
SELECT with CTEs and tagged UNION branches. It requires `trace_bounds`,
`actual_frame_timeline_slice`, `process`, `thread`, `thread_track`, `slice`,
and `stats`, and fails explicitly if the processor/schema is incompatible.

Reports include actual trace bounds, requested/observed coverage, every selected
frame/collector/UI-phase slice, offsets and full durations, and per-collector
overlap with complete `App Deadline Missed` FrameTimeline intervals. Selection
includes slices crossing window boundaries. Overlaps use half-open intervals
clipped to the observed window; touching endpoints do not overlap.

Only `CollectOldGeneration`, `Scavenge`, and `CollectNewGeneration` on identified
UI threads count as UI collector pauses. Identity uses main TID, `ui`/`N.ui`
names, or observed `UI.Build`/`UI.Layout`/`UI.Finalize` labels. `DartWorker`
threads are explicitly excluded. ConcurrentMark/ParallelMark slices remain
visible separately and are never added to UI GC totals, including UI marking.
This is trace-label attribution, not proof of the VM's complete stop-the-world
protocol.

Nested collector intervals on the same thread track remain in the raw list
but count once in pause totals. Timing totals use interval unions; phase
totals are independent and must not be added to GC totals. Unfinished slices
are listed with unknown duration and excluded from timing/overlap totals.
Frame counts refer to slice IDs, not unique presentation tokens.

Bounds coverage is only a trace envelope and cannot establish continuous
instrumentation. Missing frame/GC slices do not prove absence of work. Up to
128 nonzero error/data-loss/lost/drop/overrun/overwrite/parser diagnostics are
reported with total count and truncation status; these are whole-trace stats.
PID reuse can return multiple process identities, which are listed. Temporal
overlap establishes association only, never frame-drop causality.
