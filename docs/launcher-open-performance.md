# Launcher opening and bulk-edit performance

## Evidence boundary

The supplied November 6 diagnosis records a disabled module, a native-heap
startup peak of 1,602,245 KiB, memory-pressure warnings, repeated MAML lifecycle
events, and no tap-aligned frame trace. It does not establish which individual
icon or hook caused an opening stall. Disabling a module also does not restore
an already applied icon archive; `keihome_maml_enabled=0` disables generated
palette selection, not the native loading of ordinary fancy day/night icons.

The November 7 physical snapshot has 6,036 ZIP entries and 937 fancy manifests,
665 containing palette selectors. The earlier AVD snapshot has 288 entries
and 38 manifests. They are different workloads. AVD Vulkan/gfxstream CPU
costs must not be described as physical-device power consumption.

## Bounded release queue

The existing final-owned ZIP metadata release optimization keeps native view,
texture, Dart and animation ownership on their original threads. Its old
producer used `pthread_mutex_trylock`; even a momentary consumer lock could
send a large cleanup back to the UI thread.

The replacement initially used one worker and 64 pending slots plus one active release.
Each slot has a publication sequence. A producer attempts one reservation CAS,
publishes an immutable cleanup item, and posts a semaphore. It does not wait
for a queue lock, allocate a queue node, or spin on a reservation. The consumer
accounts for notification credits so a later producer cannot make it process
an unpublished FIFO head. It sleeps until that head is published.

Capacity, reservation contention, unavailable-worker and reentrant fallbacks
are separately counted. Full or unavailable queues still preserve synchronous
native cleanup; this optimization does not promise to eliminate every native
GC, layout or raster stall. Capacity and retained-memory bounds are unchanged.

ASan/UBSan and TSan each pass 9,095 exactly-once releases, initialization
failures, 64 pending plus one active, FIFO publication gaps, reservation races,
ticket overflow, repeated slot reuse and eight concurrent producers.

## Generated selectors

Byte-identical day/night pairs keep their descriptor and ordinary fallback,
but share one image resource and one base Image node. Palette masks, genuine
day/night differences and original bytes remain intact. Canonical legacy
archives are still accepted. In the physical snapshot this applies to 38
descriptors, so it is a modest reduction rather than a complete memory fix.
Existing applied archives adopt it when regenerated.

## ColorOS reference

Read-only inspection authenticated the running ColorOS launcher/framework
against retained inputs. Its database path selects the active day/night blob
before decode; monochrome/alpha layers can share bitmap storage while paint
filters apply the current palette. Component/user caches are invalidated on
configuration changes. Launch snapshots may still rasterize dynamic icons
or copy hardware bitmaps, so they are not universally free.

These findings support selecting active resources before decode and separating
source pixels from palette state. Android `ConstantState`, ART GC and Java
cache ownership cannot be transplanted into Rust MAML without proving that
runtime's lifetime and invalidation contracts. No unverified shared bitmap,
texture or mutable ZIP cache was added.

Research and per-device captures are retained in
`.artifacts/research/launcher-open-20261107/`, including archive audits and
independent trace reports. The reproducible ColorOS findings are documented in
[coloros-icon-cache-reference.md](coloros-icon-cache-reference.md).

## November 7 device observations

On the physical Xiaomi 17 Pro, the new queue completed 100 metadata releases
during one nine-application move. Peak pending work was nine and every fallback
category remained zero. The drop window still contains a 52.06 ms old-generation
GC and two app-deadline misses across 95 frames. The earlier capture used another
process and cache state, so these captures do not establish a percentage speedup.

Three guarded launches of the actual Settings icon, followed by Home, produced
264 launcher frames in the three one-second opening windows with no app-deadline
misses. The first window's longest frame was 21.52 ms; the two warm windows were
11.44 and 13.86 ms. Settings was verified as the resumed activity on every tap.
These bounded observations do not establish that every icon or application is
free of startup jank. Concurrent marking on Dart worker threads is not itself
proof that the UI was blocked.

The prior physical process had a historical RSS high-water mark of 1,711,588 KiB.
It is neither a single-click peak nor a before/after optimization measurement.
A fresh process after development activation cannot support a memory-saving claim.
On both targets the single cleanup worker accumulated no extra CPU ticks in a
three-second idle observation.

The AVD's longer bulk-drag trace includes expensive native raster snapshots.
Its release window has insufficient rendered-frame evidence for a useful
smoothness comparison. A separate opening attempt failed its resumed-activity
guard and is excluded. Virtual-GPU measurements remain separate from physical
acceptance.

## Editing animation boundary

Offscreen page tickers are suppressed only outside native editing. During
editing, a drag can retain an animation owned by its source page after crossing
to another page; disabling that ancestor would also mute unrelated native
completion and selection animations. The existing authenticated editing getter
is queried only for an otherwise invisible page, using the original mapped
return PC and rooted child list. An unpublished Smi distinguishes the second
return; no additional frame, retained Dart pointer, timer or per-frame work is
introduced. Unknown controller state conservatively keeps native tickers active.
The title-specific editing pause remains in place.

The five retained native fixtures pass 3,430 linked ARM64 execution cases,
including editing-state transitions, missing controllers and relocation during
each native query. These are ABI and ownership checks; actual device interaction
and rendering remain separate acceptance gates.

## Release 222 acceptance checkpoint

Release `1.4.0+gebac22d.b0222` / `104000222` uses the KeiOS Release identity.
Its native payload is byte-identical to the accepted AVD candidate 220.
The AVD completed an eight-probe cross-page drag with completed checkmarks and
no residual drag feedback. Temporary probes were subsequently removed.

A single Classic ART+ regeneration/application succeeded on AVD: the protected
applied archive has 723 fancy descriptors, 35 compact single-image descriptors
and no byte-identical expanded day/night pairs. Physical-device artwork remains
in its previous valid layout; it adopts compaction on its next pack update.

The initial AVD mode 1 -> 2 -> 1 and physical mode 2 -> 1 -> 2 cycles each advanced
revision/notified exactly twice while preserving Launcher PID and start time.
An additional warm AVD cycle also preserved identity and restored mode 1;
the final AVD revision/notified is five and physical is three.
Server and SystemUI identities also remained stable. Explicit development native
activations are recorded separately; the final AVD APK update used ordinary
API 102 Java reload and preserved all three process identities.

The receipt is `.artifacts/research/launcher-open-20261107/acceptance.json`.
Residual Dart GC and native raster/snapshot costs remain investigation targets;
these changes do not establish universal jank removal or a memory-saving ratio.
An early post-update AVD capture showed an empty folder thumbnail and a slow
Home transition. Later settled warm captures in both modes rendered the folder
correctly. These observations do not establish a cold-cache fix or attribute
that transient state to a particular hook.

## Physical follow-up: interval attribution and queue pressure

The November 7 follow-up uses the physical Xiaomi 17 Pro as the primary
performance target. Nine owned test activities move between the original
partly occupied page and a new page. Real applications stay in place and no
social application is launched. These probes do not reproduce every animated
or themed icon; the page thumbnails still exercise native MAML resources.

All following samples share Launcher PID 32572 / start tick 20953382, the same
installed Release 222, icon pack, name mode and display configuration. Each
reported window is the first second after the injected UP event and is fully
covered by its Perfetto trace:

| Sample | Metadata cleanup | Direction | Maximum actual frame | App deadline misses |
| --- | --- | --- | --- | --- |
| A1 | Synchronous diagnostic baseline | Forward, first pass | 146.25 ms | 2 |
| B3 | Bounded worker, nice 10 | Forward | 28.72 ms | 2 |
| A2 | Synchronous diagnostic baseline | Forward, warm | 116.63 ms | 3 |
| B5 | Bounded worker, nice 10 | Forward | 27.92 ms | 2 |

These are small, ordered scene samples, not a randomized benchmark or a
universal improvement ratio. In particular, the first pass has different cache
history. A backward capture that crossed too many pages and failed to place
the probes is excluded rather than compared with successful drops.

The worker completed 142 releases without fallback in B3, with lifetime queue
peak 60. B5 exposed the remaining saturation case: 130 queued releases plus 12
capacity fallbacks, reaching the fixed limit of 64. Reservation, unavailable
and reentrant fallback counts remained zero. Thus a single successful earlier
gesture does not establish that synchronous fallback can never recur.

The older 52.055 ms `CollectOldGeneration` begins 899.140 ms after UP. Its
interval does not overlap either measured missed-frame interval at about
+15.5 ms and +523.1 ms. This does not exonerate GC in other interactions or
measure input latency during an otherwise idle collection. Concurrent
`DartWorker` marking is also distinct from a UI-isolate collection.

`scripts/tests/launcher-performance/analyze_trace.py` now records interval
overlaps, collector nesting, trace coverage and bounded loss diagnostics.
Seventeen tests cover PID reuse, partial captures, worker exclusion, nested collectors,
boundary intersections, malformed input and processor failures. The script
does not infer causality from an overlap.

UI finalization remains approximately 30--36 ms in these forward samples.
Settled process PSS rises from about 934 MiB after A1 to about 1,056 MiB after
B5; this is not a demonstrated memory reduction or a leak diagnosis. A live
native-heap profiling attempt returned no allocation samples and is not used
to assign that memory to a particular cache.

Source inspection of both retained Rust MAML renderers confirms that omitted
`retainWhenInvisible` already means false. Hidden images skip update work and
release their image/provider references. Adding that XML attribute would not
establish a bitmap saving, and those observations do not prove that every
ResourceManager or Flutter cache has been evicted.

The follow-up receipt and raw scene evidence live in
`.artifacts/research/launcher-followup-20261107/`. The adaptive-priority worker
is validated separately from the baseline table above.

### Adaptive cleanup scheduling

At this checkpoint the queue had one worker, 64 pending slots and one active
release. The worker normally uses nice 10.
At 32 pending items it requests ordinary nice 0, then restores nice 10 when
the backlog falls to eight. Hysteresis keeps priority syscalls out of the
per-item steady state; there is no timer, polling thread, extra graphics
allocation or queue expansion.

The worker verifies the actual OS priority. A failed transition disables
further transitions instead of retrying for every item. Queue startup accepts
only a known nonnegative actual priority; unsafe startup preserves the original
synchronous release path. Nine deterministic host scenarios exercise
hysteresis, the diagnostic gate, denied boosts/restores, failed readback and
safe/unsafe startup, in addition to the existing 9,095-release regression under
ASan/UBSan and TSan.

Candidate Release 223 was activated once for development on each target.
The physical follow-up then keeps PID 8201 / start 21458321 throughout three
warm-up moves, six controlled moves and normal preference changes. Kernel
scheduling records nice 10 throughout the fixed-priority control and nice
10/0 during adaptive work. This confirms an actual OS transition rather than
only a configuration acknowledgement.

| Candidate scene | Scheduling | Maximum actual frame after UP | App deadline misses |
| --- | --- | --- | --- |
| Forward control | Fixed nice 10 | 29.82 ms | 2 |
| Backward control | Fixed nice 10 | 27.59 ms | 2 |
| Forward A | Adaptive | 26.44 ms | 3 |
| Backward A | Adaptive | 26.11 ms | 2 |
| Forward B | Adaptive | 29.24 ms | 2 |
| Backward B | Adaptive | 29.96 ms | 2 |

Across warm-up and controlled moves, 1,178 releases completed, with zero
synchronous fallbacks or priority failures. Six boosts returned to nice 10.
A subsequent three-second idle observation found no additional worker CPU
ticks. Both fixed-priority control moves also avoided saturation, so these
samples do not establish that adaptive scheduling eliminates capacity fallback
or produces a significant frame-time gain. The older 12-fallback sample belongs
to another process and is not marketed as a paired 12-to-zero improvement.

Clean-release acceptance additionally checks byte identity with the tested
native payload and a normal Java/API 102 update without another Launcher
restart. Final artifacts, identities, preference restoration and remaining
measurement limits are recorded in the follow-up `acceptance.json`.

## Horizontal Home swipes on 2026-11-24

The report concerns 1.14.0 beta on a slower physical device. Local sampling
compared KeiHome builds `115000758` and `115000759` on HyperOS Phone AVD
`OS4.0.31.0.XFRCNXM` and Xiaomi 17 Pro `OS4.0.8.0.XBLCNXM`. Both devices
ran Home `801030308`. The AVD used the stock icon pack and the phone used
its existing Classic pack. The reporting device, layout, icon pack and
beta frame trace were unavailable.

Perfetto samples show Home's Flutter layout, paint, compositing and raster
work, including `MamlDragSnapshot` slices. The candidate AVD trace reached
65.8 ms in one raster slice. The corresponding phone trace reached 9.1 ms.
These are separate environments. The samples leave the relative
contribution of individual icons, materials and native launcher work open.

App installation and update can run icon refresh code while Home is
visible. A single-package refresh previously fetched the profile's full
activity and shortcut lists, filtered them locally, and looked up
application metadata even for owners already found in those lists.
It now scopes both queries to the package and uses the application lookup
only to cover a package with no activity or shortcut. Full and targeted
refreshes also skip the archive coverage calculation, since every possible
match is already in their activity owner set. Resource-selective refreshes
still combine resource probes, theme coverage and shortcuts. This reduces
event-driven work in `system_server` without adding a persistent cache or
changing gesture timing, refresh pacing or callback scope.

Each build captured four left and four right swipes on the same two pages
on each device. Each gesture lasted 340 ms. The query uses marker windows
and de-duplicates Home's surface frame tokens. P95 is the median of the
eight per-swipe P95s from the Perfetto Actual Timeline.

| Device | Home frame P95 | App deadline misses | Longest Home frame |
| --- | ---: | ---: | ---: |
| Phone AVD | 52.61 → 47.65 ms | 89/311 → 87/328 | 115.15 → 101.26 ms |
| Xiaomi 17 Pro | 13.83 → 13.35 ms | 9/659 → 6/668 | 30.60 → 44.30 ms |

These are single runs of ordinary swipes without a controlled concurrent
package refresh. The differences are within the scope of a regression
check. A causal improvement to steady-state swiping or performance on the
reporting phone remains unmeasured.

An archive-only experiment replaced paired light/dark image nodes with a
single source expression in matching generated MAML descriptors. It changed
925 manifests in the phone pack and two in the AVD pack. Six common phone
swipe windows yielded P95s of 12.51, 12.26 and 12.15 ms in the original,
experiment and repeat-original runs. App deadline misses were 3, 0 and 3.
The AVD had five common windows and high run-to-run variation. The overlap
with both phone controls offers little justification for changing the
selector on every compatible host. The checked-in renderer retains the
existing descriptor format and authored animations.

## Selecting an app in Home edit mode

On Xiaomi 17 Pro with HyperOS `OS4.0.8.0.XBLCNXM` and Home `801030308`,
selecting an app on a dense page changes the bottom tray to page thumbnails.
The trace showed launcher UI and raster work alongside MAML initialization
and ZIP metadata cleanup. The selection window had no recorded KeiHome
icon refresh sweep. With the 64-slot queue, capacity fallbacks were recorded
in the launcher process. In a separate five-cycle run at fixed nice 10,
the queue logged 216 additional capacity fallbacks.

The queue now accepts up to 256 pending metadata releases and one active
release. The extra slots add 4,608 bytes of fixed ring storage on arm64.
They can temporarily retain more metadata until the worker catches up.
The worker retains its existing priority thresholds. The thumbnail renderer
keeps its normal icon content.

The four common selection windows in the baseline and candidate traces had
median longest launcher frames of 155.8 ms and 86.2 ms, respectively. The
respective maxima were 168.6 ms and 95.5 ms. The fixed-priority comparison
peaked at 443.2 ms. The expanded queue reached 69 pending entries and
completed all 973 queued releases since the candidate process started,
with zero synchronous fallbacks recorded. The remaining frame stalls include
work outside the release queue. These figures describe this page and device.

The candidate `115000749` was installed in place and the replacement
launcher process loaded the new native image and initialized the release
hook. The edit recording covers selection, multi-selection, deselection
and exit. The theme icon archive hash matched before and after the run.
ASan/UBSan and TSan each covered 9,287 exactly-once releases, including
saturation, ticket wraparound and concurrent producers. Traces, counters
and the recording are in `.artifacts/research/launcher-edit-douyin-20261125/`.

The Debug build passed and six focused JVM suites passed all 80 tests.
AVD API 102 receipts verified critical Java reloads. The physical install
was verified separately, and the native libraries matched the preceding
build byte for byte. On the AVD a temporary package with no launcher entry
produced one package callback. A light-to-dark switch still completed the
selective resource refresh with 90 callbacks. Motion recordings on both
devices show page one to page two and back with the Dock and icons
rendering during the gesture. The original icon packs and light mode were
restored, and the temporary package was removed.

Local evidence is in `.artifacts/research/launcher-page-swipe-20261124/`.
`frame-summary.json` contains the comparable marker windows and
`swipe.sql` the frame query. The directory also contains the deployment
receipt, AVD refresh logs, motion recordings and full traces.
