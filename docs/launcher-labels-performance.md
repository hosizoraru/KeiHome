# Launcher name performance acceptance - 2026-11-03

This checkpoint covers HyperOS AVD `emulator-5580`, Android API 37, Home 8593,
Rust 3.9.0 / Flutter 3.38.3. Builds are minified Release. No physical device was
inspected, installed or tested. Host-only resolution also uses the saved Home
7722 library; that is not physical-device acceptance.

Native acceptance used versionCode `101000157`. The independent label-status UI
fix used `101000159`, installed with API 102 Java reload and the identical native
payload. The unstripped native SHA256 is
`9bdf5e46ca961be68140c7c57aaf90ad4f3b8d43d213abf70f081876da4ccbe5`.
The final version-only package must retain that payload before ordinary install.

## Native findings and changes

- A cached page's original Marquee keeps animating after the page leaves the
  viewport. A resident TickerMode now wraps the complete CellLayout Stack and
  uses the native reactive grid visibility predicate. Wrapping only the
  background/grid Obx did not include the actual icon labels.
- Two native width budgets differ. Names fitting the larger envelope remain
  static unless the original prefix helper reports an installation/update mark.
  LibChecker and the FCM repair title remain static; HyperPods-X and Payload
  Extract scroll despite their leading dots. No C++ string or width cache is
  retained, and these checks run during label construction.
- Zero-length fading still builds a ShaderMask and listener subtree in the
  resident Marquee. A guarded leaf bridge returns its existing ListView only
  when both fractions are zero and marquee mode is selected. Other fractions
  and modes preserve the original call. The smaller tree is verified; these
  measurements do not establish a significant raster-CPU reduction from this
  change alone.
- An extra RepaintBoundary wrapper was tested and removed. It did not materially
  reduce this AVD's raster load, so the final implementation avoids its extra
  widget and adaptation requirements.
- Label-only acknowledgements dispatch one normal package callback and invoke
  native font/title cache invalidation. This replaces the previously observed
  94 callbacks. Genuine package/resource/palette work keeps its required scans.
  Current logs show ACK-to-dispatch intervals of about 100 ms and callbacks=1.

## Measurements

Accepted traces last 12 seconds, after a two-second settle, with an exact
starting screenshot. The initial weather baseline has the separate context
screenshot `marquee-weather-page.png`, rather than a screenshot taken inside
its capture script. Process start identity and native mode/revision are checked
before and after the accepted samples. Guest /proc tick CPU is approximate: 100% means
one CPU core, not the whole emulator or host. These are bounded single samples;
they are not battery measurements or physical-device frame-rate guarantees.

| Scenario | Approximate CPU of one core | Frames in trace | App Deadline Missed |
| --- | ---: | ---: | ---: |
| Before: marquee enabled, weather visible, long-name page cached | 98.79% | 705 | 19 |
| After: same weather scenario | 3.16% | 12 | 3 |
| Marquee enabled, KeiHome in foreground and launcher behind it | 0.00% | 0 | 0 |
| Native mode, 24-app page idle | 0.08% | 0 | 0 |
| Two-line mode, same 24-app page idle | 0.08% | 0 | 0 |
| Marquee, 24-app page idle with six overflowing names | 123.71% | 604 | 104 |
| Native mode, eight alternating page swipes | 71.28% | 351 | 66 |
| Marquee mode, same eight swipes from the same page | 118.64% | 619 | 81 |

The weather page retains its legitimate clock update at approximately one
frame per second. Returning to the app page resumes marquee. Foregrounding an
app stops launcher rendering; switching away from marquee removes its continued
frames. The two-line screenshot confirms original grid spacing and native line
layout on existing icons; longer-than-two-line names still ellipsize.

The active marquee page remains expensive on this AVD: the 1.raster and Vulkan
fence threads dominate, and frame deadlines are still missed. Native page
swipes also miss deadlines. SurfaceFrame Encode p95 was 21.802 ms for native
swipes and 18.117 ms for marquee swipes. Mixed idle/animation samples produce
different frame counts, so comparing their jank percentages as equal workloads
would be misleading. This checkpoint establishes removal of hidden animation
work, not a claim that the active marquee costs as little as static text or
that all frames meet 60 Hz.

## Status UI and refresh

The five-second label-only query formerly replaced the full runtime report with
one omitting iconTheme. The fallback action row then disappeared until the
next ten-second full query, moving the open dropdown. Separate StateFlows now
hold the two receipt types, their own freshness and failure state. Framework
service changes clear both, and the existing single outstanding I/O limit
remains. Full fallback refresh and label acknowledgement observation continue
only while their UI is visible and STARTED.

Six consecutive UI-tree samples over 15.7 seconds kept the update
entry, system-theme entry, name control and current selection at identical
coordinates. Both affected dropdown choices were then saved through the UI,
with native modes 2 and 1 and revisions 3 and 4 confirmed. The final choice is
marquee, with Simplified Chinese retained.

## Checks and evidence

- 540 linked production ARM64 execution cases pass on both exact ELF fixtures,
  including root relocation, preference races, pressure, page initialization
  ordering and the zero-fade guard. They stub Dart callees and do not run Dart GC.
- 42 focused JVM tests pass: refresh generation, runtime report/dashboard and
  launcher-label lease behavior. Release assembly and lintRelease pass.
- The actual AVD rendering, mode transitions, idle/visible/background lifecycle,
  dropdown geometry and callback scope were inspected. No process replacement
  occurred during mode changes or the Java-only UI update. System-server and
  SystemUI remained PID 8812 and 9696; the accepted launcher stayed PID 19539,
  parent 19382, start tick 13832620 after the authorized native activation.
- Temporary label probe apps, notifier JAR and raw host heap captures were removed.

Local evidence lives under `.artifacts/research/launcher-labels/`:

- `performance/marquee-weather-r1.*` and `performance/accepted-marquee-weather.*`
- `performance/accepted-marquee-background.*`
- `performance/accepted-native-dense.*` and `performance/accepted-two-lines-main.*`
- `performance/zero-fade-marquee-dense-r1.*`
- `performance/accepted-native-swipes.*` and `performance/accepted-marquee-swipes.*`
- `accepted-performance-report.txt`, `accepted-framework-tail.log`
- `independent-status-geometry.json`, `zero-fade-bridge-tests.log`
- `zero-fade-deploy/` and `independent-label-status-deploy/`
- `capture-performance.py` and `trace-performance-report.py` preserve the exact
  capture/report recipes; the capture helper uses this checkout's deployment
  utilities and only `emulator-5580`.

Some exploratory captures have misleading scene names. In particular,
`accepted-two-lines-dense` starts on the 11-app secondary page and is excluded
from the 24-app comparison; `accepted-two-lines-main` has the checked 24-app
screenshot. The quiet RepaintBoundary baseline records visual mode evidence
rather than native-memory validation and is only experimental context.
