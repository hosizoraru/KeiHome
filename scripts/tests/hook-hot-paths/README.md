# Hook key allocation probe

```sh
python3 scripts/tests/hook-hot-paths/run.py --out .artifacts/research/hook-hot-paths/probe
# Optional, only on the explicitly selected AVD:
python3 scripts/tests/hook-hot-paths/run.py --out .artifacts/research/hook-hot-paths/art --serial emulator-5580
```

`KeyBenchmark.java` compares the former per-query composite key with the production
`SurfaceIconKeyIndex`, using the same 64-entry access-ordered working set and cache hits.
Both sides preserve package/activity identity. Compile it against the current production
Kotlin helper and Kotlin standard library; run on a JVM, or dex those same classes and run
with Android `app_process`. Capture the input hashes and exact commands with the results.

The allocation counter is thread-local on a host JVM and process-wide on ART. The ART
probe should therefore run in an isolated shell process, not inside an active UI. Seven
alternating-order samples follow warmup. Elapsed times are observational, never pass/fail
thresholds. Output excludes index construction, reflection, locking, bitmap loading,
rendering and IPC. It cannot establish frame-rate, battery or whole-app improvements.

Run `SurfaceIconKeyIndexTest` for identity, negative lookup and eviction contracts, and
`CrossSurfaceIconCacheWorkloadTest` plus `CrossSurfaceIconCacheShutdownTest` on a device
for bounded admission, invalidation, worker fairness and generation retirement.

`PlatformMembersProbe` verifies that the six boot-class members are reused and invokes
the user/context/package getters against live Android state. It runs only in a separate
shell process. This does not substitute for minified module activation and live Hook
acceptance, or establish correctness during a real concurrent user switch.
