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

## Completed artwork during the launch transition

On pandora OS4.0.6.0.XBLCNXM, the Shell splash builder increases the icon
viewport for the no-background foreground path and wraps the result in an
`ImmobileIconDrawable` that prepares another bitmap. Applying this path to a
finished launcher icon enlarged the entire plate during the window expansion.

Build 633 supplies the prepared bitmap directly to Shell's center `ImageView`
when it encounters the exact drawable supplied by the current fill request.
It uses the icon size that the builder selected before foreground expansion.
The bitmap already contains the plate, any icon-pack mask, and transparent
padding. Filtered scaling preserves that geometry and corner artwork. The
existing window animation drives the expansion and exit. There is no extra
animation, minimum display time, or bitmap cache. An incompatible builder
continues through the existing adaptive-wrapper route. The module's own
palette route retains its previous rendering contract.

Physical recordings at 976 × 2124 captured Via from the Home dock on a cold
launch and on reentry, and Settings via its MAIN launcher activity. In dark
Settings, the peak width of the contrasting gear on the center scanline fell
from 350 pixels (build 631) to 290 (633). Light Settings and Via also reached
their first app frames. The native fade briefly overlaps Settings content.
Health and KeiHome's authored starts took the native path. These recordings
measure appearance. They do not establish a change in cold-start latency.

On the Phone AVD, the plain fixture used its original APK icon, and the
custom icon, branding and layered background fixtures retained Shell handling.
All 28 icon geometry and rendering device tests passed, including a completed
icon with opposite colored corners and transparent interior at 3× scale.
The local recordings, scanline measurements, Shell disassembly and deployment
receipts are under `.artifacts/research/splash-launch-motion-20261120/`.

On build 633, returning Home after Via had acquired focus and launching again
succeeded. Attempts to interrupt earlier with a timed Home key or an injected
upward swipe did not consistently transfer focus to Home. Those recordings
leave gesture cancellation during the earliest launch frames unverified.
