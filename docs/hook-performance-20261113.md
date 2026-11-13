# Hook performance and API 102 lifecycle review

## References and boundaries

This review compares KeiHome with the Apache-2.0
[MiuiBackGestureHook performance change](https://github.com/wxxsfxyzm/MiuiBackGestureHook/commit/e3bcb784260f5e29c550c23f2bf0b7b7e4adeed4).
The local reference checkout was `c4a39e0a82016a861940555425e4312f2d304164`.
Its useful patterns are installation-time reflection, shared configuration
snapshots, fewer repeated IPC queries, and gating diagnostics before preparing
their contents. No external implementation was copied.

The API contract was checked against retained libxposed API `102.0.0`, commit
`45e7c5cfe54725b6d828d8b7be65e22ce60c67e4`, particularly `package-info.java` and
`XposedModuleInterface.onHotReloading` / `onHotReloaded`. A module that accepts
retirement must finish its external-callback and worker cleanup. An apparently
finished thread alone does not establish that cleanup succeeded.

Earlier improvements remain documented in `hook-hot-path-performance.md` and
`libxposed-api102-r8.md`. This review does not add version-based native
allowlists, new scopes, forced GC, explicit DEX closure, or native changes.
The public changelog is unchanged while the next release is being prepared.

An additional framework reference is JingMatrix/Vector at
`91e77e439619bdee53c106ba9c82b9e08051c3e9`, retained under
`.artifacts/inputs/libxposed/vector-20261113`. Its installed-device identity has
not been established, so these are reference-implementation observations:

- `VectorModuleManager` serializes a module's reload across the handoff. The
  Hook registry lock used to freeze the old context is released before calling
  its `onHotReloading`; a refusal or exception unfreezes the old context.
- Preparing the new loader includes mapping work before the old generation's
  approval. That does not prove the old loader has been collected or unloaded.
- `VectorService`'s `BOOT_COMPLETED event received` diagnostic is dispatched
  from `ACTION_LOCKED_BOOT_COMPLETED`. That log text alone is not evidence of
  user unlock or usable desktop state.

These observations do not establish the cause of the captured AMS watchdog.

## Changes

### Argument handling on icon callbacks

`HostAppIconRuntime` now captures package-item and String argument positions when
installing a Hook. PackageManager and MAML callbacks use indexed argument reads
instead of taking an argument-array snapshot and rediscovering these positions
on every call. Direct package validation avoids constructing a synthetic URI.
Runtime `ApplicationInfo` precedence, user identity and provenance checks remain
in place; malformed input still follows the original callback.

`RecentTaskAppIconRuntime` reads the reflected task ID once before entering its
cache monitor. A notification that has no replacement invokes the original
method without copying its argument array. An actual replacement copies once,
preserves the typed task payload and unrelated arguments, and invokes the
changed path once. A native failure propagates without a second invocation.

These are reductions in known operations, not measured FPS or overall
cold-start improvements. Cached reflection and existing log gates are retained.
Broader per-listener enumeration/IPC reuse needs separate user-isolation and
freshness acceptance and is not included here.

### External-callback ownership during retirement

`FrameworkManagerReceiver` uses an owned worker for both registration and
unregistration. Its caller closes admission immediately rather than making a
synchronous framework call from the retirement callback. The shared retirement
deadline also waits for this worker.

The worker owns its receiver token before calling the registration API: a failed
reply might follow a successful registration. Such a failure never makes the
receiver ready. Cleanup runs on the same owned worker, and cleanup failure keeps
the token for an explicit stop/rollback retry. A replacement registration waits
for the old worker to finish; it does not overlap a still-owned callback.
Permission, actual-sender identity and request-deadline validation are preserved.

`MonetColorMonitor` keeps individual successful registration tokens until their
corresponding cleanup succeeds. Failed unregister operations no longer silently
become completed cleanup. Display, content and wallpaper callback ownership is
recorded before registration IPC to cover failed replies. Cleanup diagnostics
cannot discard the remaining registrations.

`RestartableMonitor` distinguishes a terminated worker from completed external
cleanup. Retirement refuses incomplete cleanup, and an explicit stop/rollback
can retry it. This adds no periodic retry timer and transfers no old worker or
token to a new module generation.

### Early notification-icon requests

`CrossSurfaceIconCache` remembers a bounded set of requests received before its
worker starts. SystemUI can install its Hooks first and then start resource
registration/decoding, without losing those requests. A cold request does not
start a thread, perform ZIP work, or register a receiver. Stop still closes
admission and clears this pending set.

Successful insertion can notify the presentation layer. A negative-cache
insertion does not announce a changed icon or cause an unnecessary rebind.
The workload test covers a real ZIP/Bitmap request admitted before start.

## Startup evidence and limits

Evidence is retained under `.artifacts/research/hook-performance-20261113/`;
earlier captures remain under `perfect-night-statusbar-20261113/`.

Before these lifecycle edits, Debug build `109000434` passed ordinary API 102
Java handoff with unchanged critical-process identities. A subsequent
instrumentation attempt exited after 45.678 seconds with `Process crashed`.
System events show that AMS killed the target because it did not publish its
Provider in time; there was no assertion result for that attempt. Both test
packages had received the established ART `speed` preparation.

After a data-preserving recovery through the AVD's own launch scripts, the same
installed Debug App reached its cold-start activity in 2,209 ms. Its cache suite
then ran ten tests and exposed an incorrect new test expectation: a missing icon
was expected to send a changed-icon callback. The test now waits for the actual
worker queue to drain and checks the negative result directly. This failure
does not establish a decoding or performance regression.

Recovery and ART preparation are test-environment operations. Neither is a
production performance fix or an ordinary-hot-reload acceptance result.
The separate startup failure remains unproven as to root cause; the lifecycle
defects above must not be presented as a confirmed explanation for all AMS,
Provider, graphics or emulator stalls. This review includes no physical-device
performance measurement.

## Readiness means unlocked and on the desktop

All recovery and performance acceptance starts **after unlocking and entering the
system desktop**, followed by initialization of the relevant module runtimes.
`sys.boot_completed=1`, a running process, and a successful managed-launcher exit
do not establish this readiness.

The fresh Phone boot on November 13 demonstrated why two different unlock
checks matter:

- `am get-started-user-state 0` returned `RUNNING_UNLOCKED`, while
  `dumpsys window policy` still reported `KeyguardServiceDelegate` with
  `showing=true` and `inputRestricted=true`. User storage being available does
  not mean the lock screen has been dismissed.
- After dismissing the keyguard and entering HOME, both policy flags were
  false, and the primary display's resumed activity was
  `com.miui.home/.launcher.Launcher`. Before/after screenshots are retained.
- The same boot's module log already contained system-server icon hooks at
  18:26:28 and SystemUI icon hooks at 18:26:40, before the recorded unlock.
  This does not contradict the physical-device report that many functions
  become usable only after entering the desktop. It distinguishes early
  injection from functional readiness.

API 102 documents process/package lifecycle callbacks rather than a universal
"all modules start after screen unlock" boundary. Code needing an application
context, user resources, services, or a resumed host can become usable later.
Early system hooks must still be installed at their required lifecycle boundary;
the desktop requirement applies to our **test admission**, not to delaying all
production hooks.

The deployment health check now rejects unavailable user-state evidence, a
locked user, a visible/restricting keyguard, and ambiguous keyguard output.
It stays read-only. Ordinary updates do not force HOME or dismiss the lock screen:
the current App or test screen can legitimately be foreground after the initial
recovery was admitted. Recovery acceptance separately records actual entry into
the desktop and the relevant module initialization receipts.

## Additional failures and their limits

Diagnostic 109000436 installed, but its ordinary reload was not accepted:
Security main deferred retirement because an owned worker had not exited.
Other hosts handing off successfully does not turn that partial result into
all-host acceptance. The later system-server watchdog reported a main-handler
wait of 68 seconds; its Java thread dump failed, so it did not identify a
specific module lock owner. Earlier startup measurements lacking recorded
unlock/desktop evidence remain historical observations, not comparable
post-unlock performance samples.

On the explicitly unlocked recovery, the matching test APK installed
successfully in 54.152 seconds. The emulator host then aborted at 18:32:39,
before instrumentation produced any test results. The macOS crash report
records `SIGABRT` after `mutex lock failed: Invalid argument`, through
`MVKFence::getIsSignaled`, `vkGetFenceStatus`, and gfxstream's
`DeviceOpTracker::PollAndProcessGarbage`. This locates that exit in the host
graphics stack; it does not identify the initiating workload or establish a
KeiHome Java lock cycle. No device tests from that attempt are counted as
passing. The host log, macOS report, and empty runner output are retained under
the performance evidence directory.

The second managed recovery again confirmed an unlocked user, hidden keyguard
and resumed primary HOME. Its first admission attempt rejected a boot-property
timeout; a later attempt passed the new unlock checks but rejected a
15-second screenshot timeout. These are readiness failures, not passing
performance samples. Device acceptance and all-host ordinary-reload acceptance
for the performance changes therefore remain pending. No new Release has been
declared device-validated.
