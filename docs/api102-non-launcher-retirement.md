# API 102 retirement outside Launcher

Source audit: November 8, 2026. This checkpoint covers SystemUI,
system_server and the Themes import process. It does not change the
resident native Launcher payload.

## Handoff is not collection

The [libxposed lifecycle contract](https://github.com/libxposed/api/blob/master/api/src/main/java/io/github/libxposed/api/XposedModuleInterface.java)
requires the retiring module to stop its own work and external callbacks
before accepting hot reload. The replacement generation receives the old
hook handles and saved state. The default replacement callback unhooks the
old handles; KeiHome overrides it to replace its named hooks explicitly.

This contract does not guarantee immediate ClassLoader collection, DEX
unmapping or native-library unloading. An acknowledgement or a quiet worker
proves neither the absence of external roots nor reclaimed physical memory.

## Existing owners

| Process | Retirement responsibilities in current source |
| --- | --- |
| SystemUI | Close hook admission; drain preference/profile/diagnostic work; unregister preference listeners; stop splash preparation; export the host loader outside the admission monitor; replace old hook callbacks. |
| system_server | Close admission; stop owned monitors, registered receivers and refresh work; close/drain native socket broker requests; wait for worker exit; validate converted-import retirement; export the existing host service. |
| Themes | Reject retirement during an active import or queued initialization; verify initialization thread exit; export the host loader; replace named import hooks. |

The saved host loader/service is intentional. No old module runtime instance
is intentionally exported as saved state. This source inspection is not a
heap-root or DEX-unloading proof.

Themes now delegates to the same two-phase lifecycle gate used by SystemUI.
Its previous gate called worker checks and framework state export while
holding the admission monitor. The shared gate closes admission briefly,
then performs external work outside that monitor. An incoming Binder
request can reject promptly while state export is waiting. Failure reopens
admission; successful retirement remains idempotent.

## Lessons checked against KeiMi

Reference checkout: KeiMi `1658dc0a`, particularly
`ModuleDexRetirement`, `RetiredDexQueue`, `RetiredDexReachability`,
`RetiredDexGcFence`, `ModuleRegistrationTraceCache`,
`ModuleParcelCreatorCache` and `ModuleNetworkCallbackCache`.

KeiMi goes beyond callback cleanup:

- It carries retirement state in platform-owned container/reference types
  rather than carrying the retired module's runtime objects.
- It checks specific platform caches for retired-module roots, including
  registration Throwable backtraces, Parcelable creator caches and network
  callback metadata. It does not indiscriminately clear host caches.
- It tracks retired loaders with reference queues and preserves reachability
  and GC-completion evidence across generations.
- Its guarded DEX close requires stronger evidence than a cleared weak
  reference, checks the native cookie through public close behavior, and
  retains a bounded pending-generation limit.
- Collection hints have budgets and backoff. The current source includes a
  delayed tail policy; the older September audit described that policy as
  a proposal, so that dated audit must not be read as the current source.

KeiHome now has its own loader ledger and retirement maintenance owner,
described below. It deliberately does not copy KeiMi's DEX-close queue or
its GC-hint policy. Its DexKit descriptor cache reduces repeated JNI
initialization, but a cache hit is not proof that an earlier module
loader/native library has been unloaded.

## Implemented handoff and maintenance

`ModuleReloadState` wraps the existing host payload in a versioned
`Object[]`. It reads older plain-loader/null handoffs as before, rejects
missing/corrupt history in a recognized new envelope, and refuses to
export its own loader, a child of it, or a module runtime object.

`RetiredGenerationLedger` transfers only exact bootstrap arrays, strings,
boxed longs and exact `WeakReference` instances. It bounds labels to 96
characters and pending records to 16. Failed export leaves the imported
history uncommitted, so a retry at 15 pending entries is still possible.
Concurrent retirement requests reject promptly. Repeated successful
retirement exports the same accepted envelope.

The journal reports `pending`, `weakCleared` and `oldestAgeMs`, using the
same process's monotonic clock. Weak clearing is **not** reported as DEX
close, native unload, completed ART GC or reduced PSS. At the pending cap,
reload rejects before stopping the current runtime. Cleared references
are pruned on the next lifecycle event; there is no periodic collector.

After adoption, the successor performs one finite maintenance task:

- Inspect Parcel's two creator caches for keys belonging to retired
  loaders, under the platform's common cache lock. Report them as
  `parcelBucketsDeferred`; **do not delete them**. AOSP resolves creators
  outside that lock and later writes into the bucket, so locking only
  during deletion does not prove an in-flight decoder has finished.
- Snapshot at most 1,024 receiver/service registration locations from the
  current application's and system context's LoadedApk registries.
- Outside the registry locks, materialize only Throwable backtraces that
  actually contain a retired loader's Class frames. Preserve receiver and
  connection registrations. Skip overridden stack-trace getters and
  module-defined Throwable instances. Verify whether the backtrace still
  holds the old frames afterward.
- Unsupported platform layouts or remaining roots are reported; optional
  cache hygiene cannot invalidate an otherwise completed handoff.

Cache locks run on the successor's maintenance worker, **not** the
framework/Binder reload callback and not under runtime admission locks.
The worker has a platform context loader, no loop or timer, and exits
after one bounded batch. Before retiring that generation, wait at most
300 ms for it; if it is still active, reject before stopping the runtime.
Do not interrupt it to force success or carry its Runnable/Thread into
the next generation.

The Launcher Java/native path is excluded. Existing native generations,
leases and code patches are unchanged. KeiHome does not register its own
ConnectivityManager network callbacks in these runtimes; no generic
network cache sweep or host callback unregistration was added.

Related sources:

- [Parcel creator lookup and caching](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/os/Parcel.java).
- [LoadedApk registration maps](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/app/LoadedApk.java).
- MiuiBackGestureHook `a471042` reference checkout:
  `HotReloadHookRuntime.onHotReloading`. Its refusal to retire during live
  gesture/native work informed the admission ordering, rather than
  transferring its gesture/session-specific saved state.

## Acceptance and remaining evidence boundary

Before adding explicit DEX-close logic, identify the actual surviving module
loaders/DEX by content and inspect their external roots in each live host.
Then verify listener removal, callback drain and bounded retirement across
several normal same-process reloads. Keep pending, collected and closed
counts separate from Size/PSS/SwapPSS and GC/blocking-GC time.

Do not clear unrelated host caches, force unmap, rewrite ART cookies, or
introduce periodic GC just to make a generation counter reach zero.
Unregistration errors also need an explicit outcome: thread exit alone
does not certify that the platform released the listener.

66 focused JVM tests cover the neutral wire format, corruption, cap/retry,
concurrent admission, platform-cache preservation, owner drain and finite
maintenance shutdown. See the receipt for the final count.

On HyperOS AVD and Xiaomi 17 Pro, an independent app_process loaded a tiny
fixture into an isolated DexClassLoader. The probe executed production
`materializeOwnedTrace`: owned frame present before, result 1, owned
frame absent afterward; an unrelated host Throwable was unchanged.
This establishes that operation on the actual ART builds, not actual
collection of a whole module generation. No GC was requested.

The initial Debug deployment passed normal Java/API 102 identity checks.
The opt-in continuous-reload instrumentation then failed before entering
its test method: ActivityManager killed the target for a ContentProvider
publication timeout. The AVD also recorded a system_server pre-Watchdog.
There was no new retirement/adoption record proving those requested
rounds ran. Preserve this as a blocked live stress test, not a passing
reload or evidence that cache maintenance caused the timeout.

The implementation was subsequently tightened to move platform cache
work to the successor worker. Consult
`.artifacts/research/api102-retirement-20261108/acceptance.json` for exact
build/install scope, evidence and limitations. The physical phone was
used only for the isolated ART probe, without APK installation or
changes to its loaded hooks.

The prior SystemUI gesture ANR and AVD Watchdog remain unresolved; see
[SystemUI hook safety](systemui-hook-safety.md).

### Follow-up: Provider startup and resumed stress acceptance

The historical blocked run above is retained. The
[publication-timeout investigation](provider-publication-timeout-20261108.md)
adds kernel mmap-wait evidence, startup phase logging, deferred ProfileInstaller
initialization and an explicit ART preparation/health gate for development
instrumentation. Subsequent AVD runs entered all four device tests and completed
two rounds on each of the three Java hosts. Their machine-readable receipt is
`.artifacts/research/provider-timeout-20261108/acceptance.json`.
These passing same-process handoffs do not certify collection/unmapping of
every old generation or resolve the initiating cause of the earlier kernel stall.

### 2026-11-10: icon-cache retirement under framework lock contention

The Phone AVD's 06:43 update was refused by the old SystemUI and
system_server generations. The theme host succeeded. The system log records
a `KeiHomeSurfaceIcons` worker waiting 4,790 ms for the framework lock in
`ContextImpl.unregisterReceiver`; its cache stop budget was only 1,500 ms.
This is evidence of an undersized cleanup deadline, not proof of a permanent
deadlock, a crashed host, or the precise SystemUI cleanup stage.

Cache shutdown now has separate nonblocking stop-request and bounded wait
operations. The standalone cache budget is five seconds. Recent-task shutdown
requests cache cleanup before joining its other worker and shares the outer
retirement deadline across joins, cache cleanup, and callback drain. SystemUI
cache cleanup shares a six-second outer deadline. Main-thread and cache-worker
callers still never wait for cleanup.

Failed unregister operations retain the worker and acquired registrations for
an explicit subsequent stop attempt. Successfully released registrations are
not released twice. No background retry timer or forced class-loader release
is added. A timed-out or failed cleanup still refuses generation handoff.
Recent-task replay posting is now serialized with shutdown admission and
token removal, preventing a late queued callback from retaining the old
generation after stop.

Retirement diagnostics distinguish callback drain, worker liveness, archive
close and registration cleanup. The original failure log did not distinguish
these stages; do not retrospectively infer an exact SystemUI stage.

See `.artifacts/research/hot-reload-failure-20261110/acceptance.json` for the
device checks, process identities and test results. Same-APK API 102 reload
probes must respect the framework's native-library rule: after the stale
target has updated, another request can legitimately return `UNSUPPORTED`
because native reload is allowed only for stale targets. Such a second probe
is not a failed old-generation cleanup. Use increasing-version APK updates
to verify repeated normal updates.

During this verification, several preflight screenshot probes exceeded their
eight-second limit before installation. A separate full-resolution PNG
capture completed in 9.73 seconds while the independent SystemUI renderer and
main-thread probes responded normally. The screenshot encoding allowance is
now fifteen seconds; renderer responsiveness, thread state, recent
Provider/Watchdog failures and PNG integrity checks remain mandatory. The
rejected preflights remain in the evidence directory and are not counted as
failed module handoffs or as passing installations.

A later busy update reproduced SystemUI refusal with the new diagnostics:
`stage=unregister observer cleanupComplete=false worker=RUNNABLE`.
Increasing the deadline alone was therefore insufficient. The cross-surface
bitmap caches no longer register their own Settings `ContentObserver`.
The system-server palette publisher already owns the final palette-state
write: it now invalidates the two existing, non-exported host receivers using
package-scoped, registered-receiver-only broadcasts after that write.
The original Settings notifications needed by native/MAML consumers remain.
Theme, configuration and time invalidation remain unchanged. No polling or
additional receiver registration is introduced.

The deployment check also now requires both `retired` and `adopted`
acknowledgements from system_server and SystemUI, and rejects any fresh module
deferral. Previously, one fresh system-server acknowledgement was enough to
reach the process-identity check, which could miss a simultaneous SystemUI
refusal. The earlier two successful rounds have matching acknowledgements for
both hosts; the later reproduced refusal remains a failed intermediate round.
