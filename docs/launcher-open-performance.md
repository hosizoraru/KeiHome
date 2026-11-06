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
`.artifacts/research/launcher-open-20261107/`, including
`coloros-reference.md`, archive audits and independent trace reports.
