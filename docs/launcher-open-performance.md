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

The replacement keeps one worker and 64 pending slots plus one active release.
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

## Final acceptance checkpoint

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
