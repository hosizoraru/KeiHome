# Launcher cross-page drop cleanup

## Reproduction and attribution

The 2026-11-06 physical-device report describes a pause after an application is
dropped on another desktop page in editing mode. Launcher 8602 uses Rust MAML
for dynamic icon resources, including icons in page thumbnails.

The captured launcher PID and process start identity did not change. The
visible reload is associated with native widget/thumbnail lifecycle work,
rather than evidence of process replacement.

Three 14-second physical traces isolate the name and Dock settings. In the
two seconds following the injected pointer's UP event:

| Configuration | Longest app frame | App deadline misses |
| --- | ---: | ---: |
| Two-line names, Dock enabled | 270.61 ms | 6 |
| Native names, Dock enabled | 237.05 ms | 6 |
| Native names, Dock disabled | 291.04 ms | 6 |

These are diagnostic runs, not paired optimization results. All have valid
frame timelines and no reported trace loss. Their similar teardown bursts
rule out attributing this particular pause solely to the name or Dock feature.
Each run logs 116 completed native MAML destructions. CPU samples identify
`Arc<zip::read::zip_archive::Shared>::drop_slow` on Flutter's UI thread.
The traces also contain Dart old-generation collection; this is a separate
remaining source of work.

## Scoped native release

The optimization leaves native MAML view finish, destruction, texture ownership,
animation and thumbnail reconstruction on their original threads. It transfers
only final-owned immutable ZIP entry metadata to one background release worker.
That metadata contains archive entries, names and comments; it does not retain
a MAML view, Dart object or image/texture owner.

The admitted native ABI passes the `ArcInner` allocation directly in `x0`.
The original final-release function still performs the complete data destructor
and implicit weak-reference release exactly once. No caller stack reference is
retained. A bounded instruction/call contract authenticates the selected Rust
MAML function. Unknown or ambiguous code retains native synchronous cleanup.
The 37-node, 238-instruction normal destructor graph includes entry elements,
the names table, allocator import and complete weak release. There is no SDK
version, package-version or image-hash admission table.

The queue holds at most 64 pending allocations plus one being released.
It uses fixed storage and a nonblocking producer lock. Full, contended or
unavailable queues retain synchronous cleanup. There is no per-icon thread,
timer, polling loop or deferred GPU operation. The worker sleeps on a condition
variable when empty and runs at background niceness.

Thread creation runs from the existing specialized-launcher policy worker.
Until preparation completes, UI callers retain native cleanup. The HYOS spawner
does not create the release worker. Java/API 102 reload retains the resident
native worker and does not register another instance.

## Verification boundary

The native fixtures cover two different Rust MAML SDK images: the retained
HyperOS Phone AVD SDK and the SDK pulled from the reporting physical device.
Source identity and matching are separate from on-device execution evidence.
The existing five launcher AOT fixtures are unchanged by this optimization.

Host ownership tests exercise 8,067 allocations with concurrent producers,
saturation, startup fallback, worker recursion rejection and full drain. Both
Address/UndefinedBehavior Sanitizer and ThreadSanitizer runs pass.
Each SDK fixture also passes 328 matching, mutation, wrong-target and bounds
checks, including runtime-style relocated dynamic pointers.

An initial AVD cross-page drop completed 48 background releases, with all 48
drained and no queue fallback. Its launcher PID/start identity remained unchanged.
This proves execution and cleanup in that SDK; it is not a physical-device
performance comparison.

The physical device has loaded the new native library. Its shared spawner,
Gallery, Weather, SystemUI and system_server identities remained unchanged
across the explicit launcher-only development restart. After the user unlocked
the device, the paired comparison below completed without further restarts.

The verification receipt records source checks, the physical comparison,
native identities, diagnostic-gate restoration and build identity:

`.artifacts/research/launcher-drop-20261106/acceptance.json`

The private native diagnostic gate permits same-process paired profiling.
It has no public UI or unprivileged write transport and defaults to enabled.
Any profiling session must restore it to enabled and verify the authenticated
resident native image before writing it.

## Physical paired acceptance

The matched sample contains four valid drops per condition: two round trips
between pages 11 and 12, with the same temporary application, start/end cell,
gesture timing and release window (-200 ms to +1800 ms). The user's two-line
names, glass Dock, icon theme and wallpaper were preserved. Both directions were
checked through the actual launcher hierarchy after each drop. An unsuccessful
exploratory drop onto a full page and the initial setup round are excluded from
the primary summary.

The private native gate changed only ZIP cleanup dispatch. Launcher PID 4099
(start 16644038), SystemUI and system_server identities stayed unchanged
throughout the alternating off/on trials. No installation or restart occurred
between these trials.

| Metric | Cleanup on UI | Deferred metadata cleanup |
| --- | ---: | ---: |
| Longest frame per trial, range | 104.5-217.8 ms | 28.6-33.8 ms |
| Median of trial longest frames | 176.0 ms | 28.8 ms |
| Median trial p95 frame duration | 26.9 ms | 12.8 ms |
| App Deadline Missed frames / observed frames | 25 / 330 | 8 / 382 |
| Median UI-thread CPU in the 2 s window | 837.9 ms | 362.3 ms |

The median longest frame decreased by 83.6%; UI-thread CPU in the release window
decreased by 56.8%. These are bounded Xiaomi 17 Pro / launcher 8602 measurements,
not an overall FPS, energy, or all-device guarantee. Each trace passed the
recorded error/loss/overwrite checks.

Remaining native work is still observable: up to 24.8 ms for a UI.Build slice,
14.2 ms for UI.Layout, 28.9 ms for UI.Finalize and 54.1 ms for an old-space GC
slice in the enabled samples. GC can occur in the native idle callback and its
duration must not be equated directly with a missed frame. Native collection,
layout, thumbnail invalidation and drop animations remain intact. The change
does not eliminate every missed deadline.

At cleanup, the default-on gate was restored, queued/completed counts both
reached 774, fallback stayed 0, and peak queued metadata was 56 of 64 entries.
The worker remained asleep with zero additional CPU ticks in a three-second
idle check. The temporary app and device-side helper/traces were removed; the
original ten apps on page 11 and four Dock apps were verified visually and in
the launcher hierarchy.

The installed, KeiOS-signed Release is `1.4.0+gb7e36dd.b0207` / `104000207`.
Its resident native Build ID matches the archived release payload. The final
Java/API 102 install and paired user-flow acceptance did not restart Launcher.
The original explicit development native activation is recorded separately.

Follow-up evidence in the same research directory: `paired-summary.json`,
per-trial `paired-*-stages.json`, `paired-final-native.json`,
`paired-worker-idle.json`, and `paired-final-home.png`.
