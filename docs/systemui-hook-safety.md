# SystemUI hook safety

## Boundaries

KeiHome's SystemUI runtime owns material compatibility, control-center and
volume resource refresh, and neutral splash-color adaptation. It does not
own the system accessibility service, AMS locks, or other modules' hooks.
These changes address reproducible risks in KeiHome; they are not proof
that KeiHome initiated the [November 8 AVD Watchdog](avd-stall-20261108.md).

No firmware-version allowlist or new native payload is introduced.
Existing DexKit role proofs and APK-identity-bound descriptor caches remain
the admission mechanism.

## Lock ordering and Binder

The old retirement path called listener removal and state export while
holding the lifecycle admission monitor. The configuration callback took
that same monitor. A framework which invokes listeners under its listener
monitor can therefore invert the two locks:

```text
retirement: lifecycle monitor -> framework listener monitor
callback:   framework listener monitor -> lifecycle monitor
```

The [LSPosed remote preferences implementation](https://github.com/LSPosed/LSPosed/blob/master/core/src/main/java/org/lsposed/lspd/impl/LSPosedRemotePreferences.java)
illustrates this listener-lock contract. The regression test reproduces the
ordering with independent threads and a bounded framework-lock acquisition;
it does not depend on provoking a real system-server deadlock.

Retirement now closes admission under its monitor, releases the monitor,
checks workers, removes listeners, and exports state. Callbacks arriving
during this interval return immediately. Success permanently retires the
generation; failure reopens admission and rereads the current cached
preferences so a rejected callback cannot leave the active generation stale.
Listener registration is restored if export/cleanup fails.

Initial remote preferences are acquired on an owned background worker,
without holding a module preference monitor. The initial snapshot may use
Binder. Preference callbacks only read cached preferences and update atomic
state; they do not query a provider, wait for a worker, resolve DEX, or call
the framework logger directly.

Hook publication/replacement also happens outside the module handle-list
monitor. Concurrent installation of the same method/ID is claimed briefly
under that monitor, without waiting for another installer.

## Optional hook failures

`SystemUiHookGuard` isolates only module enhancement work:

- Each hook has its own fuse. A recoverable failure keeps the original
  result and disables that enhancement until the next runtime generation.
- Reflection wrappers are unwrapped before classifying failures.
  `VirtualMachineError` and `ThreadDeath` are not disguised as successful
  recovery. Interrupted work preserves the interrupt flag.
- A thread-local guard suppresses recursive enhancement when resource
  refresh reenters the same hook. The OEM call still executes.
- `chain.proceed()` remains outside the optional-work exception boundary.
  Native exceptions are not swallowed and the native call is not retried.
- Main-thread-owned tile/slider/volume revision maps are touched only on the
  main thread. The volume refresh still uses the native safe reinitialization
  flag rather than rebuilding a showing/animating dialog.
- Splash request inspection is skipped while disabled. Failed inspection
  clears the request for the nested native call, preventing accidental reuse
  of an enclosing request.
- The material capability getter is not invoked for disabled compatibility
  or when the original result already permits the material. The previous
  extra first-call diagnostic query has been removed.

Failures are reported locally immediately and forwarded to the framework log
on an owned background worker. Delivery is coalesced, the queue is bounded to
eight entries, and a blocked/unavailable logger cannot make the caller wait.
There is no diagnostic polling timer. A blocked owned worker prevents safe
retirement; it is not forcefully interrupted or replaced while still active.

Owned profile/reporting workers use Android background priority. Thread
construction/start failures balance lifecycle admission. Plugin source
resolution remains off UI/configuration callbacks.

## Plugin lookup source check

DexSQL inspected the retained SystemUI APK and the AVD-installed APK hash:

`067556deaa7ad2791debcef6a13903a8f6aa76b79a29a9c3a7aea9107ec5672b`

`PluginInstanceInjector.<clinit>` only creates the `sClassLoaders` ArrayMap.
KeiHome reads the existing loader cache on a background worker; it does not
instantiate a second plugin or invoke a plugin factory to recover a loader.
This source check applies to this APK, not every future OEM implementation.
An unavailable/changed lookup leaves native behavior in place.

## Validation

Evidence lives in `.artifacts/research/systemui-hardening-20261108/`.

Focused JVM coverage includes independent-thread listener-lock inversion,
callback admission while cleanup waits, external worker-readiness checks,
failed-export recovery, per-hook failure isolation, nested resource refresh,
reflection-wrapped fatal failures, interrupt preservation, and reporter
failure. Existing material-role, control-center, volume and preference tests
are retained.

Device acceptance must use normal API 102 Java reload, preserving Launcher,
SystemUI and system-server PID/start identities. No native reload or forced
restart is necessary for these Kotlin-only changes. The acceptance receipt
records actual screenshots, settings toggles and service responsiveness.
Host tests do not establish universal ROM compatibility or eliminate the
possibility of failures in OEM code or native libraries.
