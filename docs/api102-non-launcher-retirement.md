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

KeiHome does not currently contain the equivalent cross-generation DEX
retirement queue or collection diagnostics. Its DexKit descriptor cache
reduces repeated JNI initialization, but a cache hit is not proof that an
earlier module loader/native library has been unloaded.

## Follow-up evidence boundary

Before adding explicit DEX-close logic, identify the actual surviving module
loaders/DEX by content and inspect their external roots in each live host.
Then verify listener removal, callback drain and bounded retirement across
several normal same-process reloads. Keep pending, collected and closed
counts separate from Size/PSS/SwapPSS and GC/blocking-GC time.

Do not clear unrelated host caches, force unmap, rewrite ART cookies, or
introduce periodic GC just to make a generation counter reach zero.
Unregistration errors also need an explicit outcome: thread exit alone
does not certify that the platform released the listener.

The new Themes concurrency regression and shared lifecycle tests pass on
the JVM. No APK was installed, no GC requested, and no device process was
restarted for this source-audit checkpoint. The prior SystemUI gesture ANR
and AVD Watchdog remain unresolved; see
[SystemUI hook safety](systemui-hook-safety.md).
