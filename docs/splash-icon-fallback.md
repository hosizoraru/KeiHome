# Missing splash icon completion

The opt-in **启动图标补全** setting is under **桌面 > 图标与启动**.
It fills eligible opaque, neutral system starting windows using the currently
applied theme archive or the activity's original APK icon. Existing app-authored
splash icons, branding, custom backgrounds and task snapshots remain owned by
the platform.

This is separate from the earlier build-flag-only prototype documented in
`splash-icon-fallback-prototype.md`.

## Work and resource ownership

- Existing authored icons and snapshots are rejected before background resource
  resolution. Disabled mode bypasses artwork loading.
- The existing owned initialization queue prepares the archive and night-mode
  setting in the background. There is no recurring timer, package scan or
  dedicated persistent splash-cache thread.
- A first eligible lookup renders on the Shell worker. Main-thread lookups use
  the original starting window.
- The shared surface renderer resolves applied artwork first and respects
  original-icon preservation. On a miss, the activity's original resource is
  rendered at the effective light or dark appearance. Fixed-size layers
  compose in the resource's intrinsic viewport before scaling into a square
  snapshot. The splash renderer receives completed artwork in both cases.
- Switching to the icon window and setting its drawable are one operation.
  A missing snapshot leaves the Shell request intact. This avoids Shell's
  legacy white-backed wrapping of raw layer-list resources when the theme
  lacks a particular icon.
- Completed snapshots use a least-recently-used cache with at most 16 entries
  and 2 MiB of bitmap allocations. Missing results expire after 30 seconds and
  count toward the entry limit.
- The archive is reused while its filesystem identity, size and full modification
  timestamp match. A change releases cached snapshots and the old archive.
  Changes detected during rendering prevent publication of that result.
- Snapshot identity includes the component, application UID, installed APK
  path, activity icon, locale, timezone, effective light/dark appearance and packed
  Monet palette state. No per-launch package-manager enumeration is required.
- Known clock/calendar snapshots expire when their wall-clock minute changes.
  Static artwork does not re-render on every minute boundary.
- Configuration callbacks and an observer for the system night-mode setting
  invalidate the cached query and schedule existing background work. HyperOS can
  pin SystemUI's configuration, so a configuration callback alone is insufficient.
  Cache hits do not repeat the synchronous night-mode Binder call.
- A low-memory callback drops retained artwork and closes the archive. Disabling
  or retiring the owner also unregisters its configuration callback. Eviction
  never recycles bitmaps that a native splash drawable may still be using.
- Invalid archives back off briefly and retain the native fallback. Diagnostics
  are bounded; archive failures do not create an unbounded per-launch log stream.

The cache is memory-only. It creates no persistent icon copies.

## Measuring

Use a signed R8 Release APK and record its exact build identity. Hot-reload
through the repository deployment script; do not restart SystemUI, the launcher
or system_server for a cache benchmark.

```sh
python3 scripts/tests/splash-icons/benchmark.py \
  --serial emulator-5580 --runs 10 \
  --output .artifacts/research/splash-performance/run
```

The benchmark force-stops Settings without clearing its data, then waits for the
home transition to settle. A system application can restart background processes
before its activity launches; retain the actual `LaunchState` instead of assuming
every launch is process-cold. Capture the first render
separately from subsequent `cache=hit` samples. `prepareUs` measures module
preparation; `buildUs` measures the platform builder, not the full application
startup. End-to-end launch time includes unrelated application and system work.

For Perfetto, trace SystemUI with app tracing enabled. Useful sections are
`KeiHomeSplash/prepare`, `/lookup`, `/archive`, `/render` and `/night-mode`.
Check scheduling delays before attributing wall time to CPU work.

Reject or label runs affected by ANRs, watchdog dumps, critical process changes,
launch timeouts or a materially different load. Validate light/dark changes,
palette changes, atomic archive replacement, disabling and re-enabling after
the performance run. Restore the original archive, palette state and appearance.
