# Missing splash icon completion

The opt-in **启动图标补全** setting is under **桌面 > 图标与启动**.
It fills eligible opaque, neutral system starting windows using the currently
applied theme archive or the stock HyperOS icon producer. Existing app-authored
splash icons, branding, custom backgrounds and task snapshots remain owned by
the platform.

On HyperOS, a theme can set `windowDisablePreview` and still receive a modern
Shell splash. The completion path may fill an existing type-6 splash from a
normal splash request only when the request describes a new, cold task and
the backing and overlay are opaque, flat and neutral. A missing request field
keeps the preview-disabled exclusion. The platform's solid-color exit-listener
capability is distinct from a request for a solid splash. No starting window
is created, kept longer or enabled by changing the app's preview flags.
Existing snapshot, artwork, branding, floating and translucent guards still apply.

KeiHome `113000655` was verified on the Xiaomi 17 Pro running HyperOS
`OS4.0.8.0.XBLCNXM` with Home `801030308` and WeChat 8.0.80. Two cold launches
from the desktop logged `initial=1`, `final=6`, `previewDisabled=true`,
`startingFlags=8b`, and `reason=fill source=applied-theme`, first rendering and
then reusing the icon. Both recordings show the themed icon in motion and on
the black starting surface before WeChat's own Earth artwork takes over.
See `b0655-cold-{1,2}-wechat.mp4` and the matching logs under
`.artifacts/research/splash-wechat-weibo-20261121/`.

## Work and resource ownership

- Existing authored icons and snapshots are rejected before background resource
  resolution. Disabled mode bypasses artwork loading.
- The existing owned initialization queue prepares the archive and night-mode
  setting in the background. There is no recurring timer, package scan or
  dedicated persistent splash-cache thread.
- A first eligible lookup renders on the Shell worker. Main-thread lookups use
  the original starting window.
- With an active archive, the shared surface renderer resolves applied artwork
  and respects original-icon preservation. On a miss, the activity's original
  resource is rendered at the effective light or dark appearance and passed
  through the shared coverage-gated mask (`IconMaskPolicy.clipIfExceeds`) using
  the active archive's mask (falling back to `OriginalIconShape.mask`). Any
  drawable whose rasterized pixels protrude outside the mask contour—including
  `<layer-list>` (`LayerDrawable`, e.g., MiShare), `BitmapDrawable`, and
  `VectorDrawable`—is alpha-intersected to the active shape without adding a
  background, while already-shaped or transparent artwork passes through
  untouched. Explicitly preserved icons follow the same `OriginalIconShape.mask`
  contract as previews and other runtime surfaces. Fixed-size layers compose in
  the resource's intrinsic viewport before scaling.
- With the stock theme, identified by the absence of the active archive, the
  splash worker queries HyperOS `IconCustomizer.getIcon` for the resolved
  activity (falling back to the activity's APK resource when unavailable) and
  passes the rasterized result through the same `OriginalIconShape.mask`
  coverage gate. Animated drawables are left to the Shell starting-window path.
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

## Stock theme and Phone AVD verification (2026-11-24)

On `emulator-5580` (`OS4.0.31.0.XFRCNXM`, Home `801030308`), the
11:12 logs recorded `source=original-apk` for GetApps and Calculator.
With no active icon archive, that path drew the APK resource directly into
the splash. The framework's `MiuiThemeHelper.getDrawable` routes through
`IconCustomizer.getIcon`, which selects HyperOS's default icon treatment.
Build `115000754` recorded `source=stock-hyperos`. Recordings show the
rounded orange GetApps plate on the light splash and the rounded Calculator
artwork on both light and dark splashes. The AVD was returned to light mode.

Before deployment, the Phone AVD launcher had no KeiHome APK or native library
mapping. The maintained deploy script with `--reload-native` verified the
installed APK and replaced the owning launcher spawner. The new launcher
mapped the candidate and logged `Two-tone native profile ready`. The same Dock
layout showed its glass background again. SystemUI and system_server kept
their process identities during deployment. The root cause of the earlier
missing mapping was not established.

Debug and AndroidTest assembly passed, along with 16 splash JVM tests,
103 desktop-material JVM tests and the Dock geometry sweep. The three
`OriginalAppIconGeometryDeviceTest` cases
passed on both the Phone AVD and the Xiaomi 17 Pro (`OS4.0.8.0.XBLCNXM`,
Home `801030308`). The physical phone retained its applied icon pack and a
Calculator cold launch recorded `source=applied-theme`. It provided an
applied-theme regression check. Stock-theme visuals and native Dock recovery
were verified on the Phone AVD.

The four-launch Settings debug sample reported 54.6 ms of module preparation for
the first render and 0.4–7.4 ms for the three cache hits. This is a timing sample
within one build. The deployment receipts, selected logs and recordings are
under `.artifacts/research/avd-dock-splash-20261124/`.

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

### Interrupting the launch from Home

On the Xiaomi 17 Pro (OS4.0.6.0.XBLCNXM, launcher 801030308, KeiHome build
633), a timed single-finger touch sequence opened Via from the dock and Settings
from its desktop icon. Each trial force-stopped the target before the first
tap and kept the device in dark mode. Video frames and touches were timestamped
against the same device boot clock.
The upward gesture began 40, 80 or 135 ms after the first tap was released.
Home logged `performAppToHome` for both apps. Tapping the same icon 260, 210
or 300 ms after lifting the gesture finger, respectively, reopened the target
in all six trials. Both apps also accepted an earlier second tap about 100 ms
after the gesture ended, while the window was still shrinking. With completion
both on and off, the launcher reversed the shrinking window into the next
opening before it reached the desktop icon. A cancellation without a second
tap left each app's icon in place on Home with no visible splash residue.

In the 40 ms recordings, the window expanded during the swipe and began to
shrink before the finger lifted. Across the turn, the measured width of the
Settings gear stayed at 32.8–33.3% of window width over 24 recorded frames
(90–282 ms after tap release). The orange Via mark stayed at 27.2–27.9% over
15 frames (88–205 ms), before the app began drawing its homepage. The smaller
Via mark and search box also appeared with completion disabled. In Settings
the gear briefly faded over the list as the native starting window exited.
Each enabled trial logged one fill across the whole sequence. The recordings showed no
separate flash or scale change from the completed icon during reversal.

Recordings, per-frame measurements and timestamped input, launcher and window
logs are under `.artifacts/research/splash-cancel-20261120/`.
