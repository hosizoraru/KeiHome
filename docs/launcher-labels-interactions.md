# Launcher names: page and editing interactions - 2026-11-04

This extends the [previous performance checkpoint](launcher-labels-performance.md).
Actual acceptance uses only HyperOS AVD `emulator-5580`, Home 8593, API 37,
Rust 3.9.0 / Flutter 3.38.3 and minified Release native build 102000154.
Physical devices and ColorOS AVD were not inspected or modified. Final packaging
and identity are recorded in the local `acceptance.json` receipt listed below.

## Change and native ownership

Previously, whole-page native TickerMode stopped cached offscreen marquees, short
names used Text, zero-length fading bypassed ShaderMask, and label-only ACKs
scheduled one ordinary package callback. Editing still kept visible marquees
animating, including while selecting apps and arranging widgets.

The new native bridge reads the reactive screen-layer editing getter in the
ordinary-icon parent. Cached immutable widgets otherwise hide that change from
existing GetBuilder titles. Both cached hits and both cold factory returns now
produce fresh immutable native draggable/ShortcutIconWidget copies with their
original classes, keys, controller, callbacks, flags and model. A fully initialized
unpublished 32-byte shell retains the original and clones across the native query;
the total is 160 young bytes per eligible ordinary-icon build, not per frame.
Pressure, missing controller or other child classes preserve the original path.

Only the completed native Marquee title publishes TickerMode. A broader icon
boundary was rejected because it froze checkbox transitions. The final boundary
keeps selection marks, icon effects, drag feedback, positioning, folders and
widget providers under their native owners. No additional timer, retained C++
Dart pointer, polling, per-frame measurement or new VM frame is introduced.
Original mapped return PCs and existing stack-map roots cover the extra calls.
Normal mode resumes the resident title animation; two-line/default modes retain
their existing native paths. All three settings were changed through the UI and
confirmed in live native state; mode 1 was restored at revision/notified 4.

## Actual interaction checks

- Eight alternating fast swipes, slow swipes and partial drags, returning to the
  checked 24-app page; repeated page returns resume marquee.
- Editing-page fast and slow swipes; existing long titles stay paused.
- Two independent probe apps selected together; both checkmarks complete their
  transitions. Both move together across a page boundary.
- Single probe app moved across a page boundary; another pair merged by dragging
  one onto the other, opened as a folder, then one dragged out. The native folder
  dissolved normally when only one member remained.
- A temporary native Calendar photo widget added through the real widget picker,
  moved to another page and removed. This fixed-size 2x2 widget does not establish
  acceptance of resizable Android widget handles or every provider's animations.
  Existing native weather/clock surfaces remained present and clock time advanced.
- Native default, two-line and marquee labels rendered on already installed apps.
  Test packages, instrumentation APK, probe widget/folder and drag-helper JAR
  were removed. Via is restored to the fourth dock slot and visually checked.

Independent probe Activities replaced an earlier ambiguous shared-framework
Activity fixture. Reliable taps use a 120 ms stationary touch: zero-duration
`input tap` frequently failed on this AVD. Final page gestures use blank y=1900,
away from dock hit targets. Starting and ending screenshots are part of each
accepted trace. Early y=2110 captures that landed on weather are excluded from
normal app-page comparisons.

## Bounded Release measurements

100% means one guest CPU core, not all cores or physical power. CPU comes from
/proc tick deltas; frames and App Deadline Missed come from Perfetto for the exact
launcher PID. Non Animating is not counted as a missed app deadline. Samples
settle for two seconds and verify native mode/revision and process identity.
These are single bounded captures, not a statistical frame-rate guarantee.

| Scene | Seconds | One-core CPU | App frames | App Deadline Missed |
| --- | ---: | ---: | ---: | ---: |
| Before: 24-app page, editing idle | 12.35 | 122.19% | 519 | 133 |
| After: same 24-app page, editing idle | 12.27 | 0.16% | 0 | 0 |
| After: selected probe icons, editing idle | 12.30 | 0.24% | 0 | 0 |
| Normal: eight 120 ms swipes | 12.38 | 123.18% | 575 | 104 |
| Normal: eight 1000 ms swipes | 18.47 | 119.38% | 871 | 155 |
| Normal: eight partial 1000 ms drags | 18.70 | 121.82% | 797 | 167 |
| Editing: eight 120 ms swipes | 12.37 | 48.75% | 194 | 47 |
| Editing: eight 1000 ms swipes | 18.43 | 76.51% | 447 | 107 |
| Weather page after visiting long names | 12.35 | 0.00% | 0 | 0 |
| Launcher behind KeiHome, marquee selected | 12.36 | 0.00% | 0 | 0 |

Editing idle falls from continuous title rendering to zero frames. The selected
probe idle sample also has zero frames and PSS changes by only 5 KiB in that
window. Accepted gesture captures do not show a sustained PSS rise, but are not
proof against all long-running leaks. Normal active-page CPU remains near the
previous approximately 120% one-core range. This checkpoint establishes editing
idle savings and native interaction preservation; it does not establish a
universal swipe speed-up. Raster/Vulkan work and app deadline misses remain on
this AVD, including in native page animation. Frame percentages across differing
render counts should not be compared as equal workloads.

The initial 32 MiB long-capture buffers overwrote data. Those traces were excluded
and the three long captures repeated with 64 MiB buffers. Accepted reports check
`*overwrit*`, loss and error statistics; no such positive counters remain. The
12-second weather sample records zero frames; this quiet interval is compatible
with the minute clock, whose later screenshots confirm updates.

## Dynamic contracts and reload stability

All five exact saved 7722/8593/8602/9209/9226 libraries pass structural resolution
and rejection checks, plus 2,550 linked production ARM64 execution cases. These
cover title pause, both cold paths and cached hits, native key/field preservation,
pressure, absent controllers, preference races, original PCs and simulated moving
roots. They stub Dart callees and do not execute the real VM or GC. There is no
production launcher version, hash or address table. Only Home 8593 received this
interaction/performance checkpoint on the AVD.

During an earlier iterative activation, SystemUI 9696 aborted in DexKit libc++
after `pthread_key_create` failed. The native stack/disassembly and repeated
class-loader history distinguish this from a launcher-title bridge crash.
SystemUI restarted to 16652. Resolved SystemUI profile descriptors are now cached
in a bounded, atomic private file, tied to canonical APK path and a SHA256 of
sorted DEX/manifest ZIP size/CRC identities. Current loader handles are restored
and method/field contracts rechecked. Changed APK code, malformed/oversized
cache or member drift resolves again; schema changes invalidate resolver proofs.
No OEM version gate or live old-loader object is persisted.

Three device instrumentation tests pass for nested optional-role roundtrip,
same-size/same-mtime changed DEX invalidation, and malformed/oversized/missing-member
fallback. Release reload logs confirm descriptors restored and material hooks
installed. After the final title-only native activation, launcher 23195,
system-server 8812 and SystemUI 16652 remain stable throughout the accepted
interactions and normal package/setting updates. Earlier authorized native
payload activations replaced the launcher and are recorded separately.

## Evidence and packaging

Local evidence is under `.artifacts/research/launcher-interactions-20261104/`:

- `title-only-tests.log`, `systemui-cache-device-tests.log`, `final-check-build.log`
- `title-only-deploy/`, `final-deploy/`, `acceptance.json`
- `performance/title-accepted-*`, `performance/title-final-weather.*`
- `performance/title-only-selection-idle.*`, `performance/baseline-edit-idle.*`
- `title-accepted-reports.txt`, `capture-interactions.py`, `report-interactions.py`
- `title-only-cross-page-multi.png`, `title-only-single-cross-page-success.png`
- `title-only-folder-{drag,open,extract}.png`
- `title-only-widget-{added,moved}.png`, `via-restored.png`
- `native-after-interactions.png`, `two-lines-after-interactions.png`
- `cache-activation-logcat.txt`, and the accepted native payload copies.

lintRelease and Release assembly pass. Final packaging keeps version series
1.2.0 and the existing release-tag anchors. No physical acceptance or push is
part of this checkpoint. Current user state is Chinese, dark, Classic ART+,
Monet off and marquee mode 1.
