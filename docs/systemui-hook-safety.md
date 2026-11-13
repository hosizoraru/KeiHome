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

## Status-bar app artwork

The notification card and the status bar use separate producers and caches.
`SystemUiStatusBarIconProfile` resolves the status descriptor producer, its
descriptor caches, the drawable reader and the view update method by linked
DEX roles. A bounded content identity invalidates the cached descriptor profile
when the host APK changes even if its version labels are unchanged. Resolution
and host-member lookup run off the UI thread.

Only artwork proven to come from the native app-bitmap branch receives the
shared, already masked snapshot. People/conversation icons and native small
notification icons retain their native branches. Descriptor and view bindings
are weak and bounded. Snapshot readiness replays existing owned views, so a
notification need not be sent again to repair its cached artwork.

Cold adoption inspects existing views once. Exact native bitmap-cache identity
can prove historical Icons, but an empty cache or a framework copy cannot.
The additional entry path reads an already initialized manager from dependency
values or a component provider's volatile instance field on a worker. It never
calls a provider or lazy getter. Verified field paths then link the manager's
collection, the exact notification object, and an existing pack/store view.
The retained APK declares the manager collection through an interface and the
connected-display store through concrete pipeline/collection types; comparison
uses object identity along the verified field path, not declared-type equality.

Only current bitmap views for non-important-conversation entries with a ready
shared snapshot receive one bounded native safe entry update. The actual app
producer is intercepted with that held snapshot, so recomputation cannot fall
through to native bitmap IPC/decode. Adoption still requires the nested producer
and returned Icon factory witness; native people/sensitive/small-icon decisions
remain authoritative. Pending entries replay on cache readiness, and native
connected-display listeners retain their normal fan-out. An unavailable owner,
ambiguous link or ownership mismatch leaves native behavior intact. Retirement
removes callbacks, stops owned work and releases host handles; no display-frame
polling or per-icon DexKit scan is added.

Debug417's live bootstrap recorded zero adopted views and the Updater remained
square. The subsequent cold-owner implementation and concrete-collection repair
require an integrated deploy and fresh bootstrap/visual receipts; synthetic
fixtures and successful profile resolution do not establish visual acceptance.

Debug419 resolved the schema-5 entry/store profile but reported
`native-owner=false`, with no linked/rebound entries. Natural descriptor calls
now retain their actual manager receiver weakly and schedule one retry per
bounded weak owner against the already scanned views. This bypasses unavailable
cold owner discovery while retaining the same collection/SBN/view identity and
cache-only producer checks. A notification used to obtain that consumer witness
must be reported as new-notification-assisted acceptance, not successful
zero-notification bootstrap. Retirement clears these weak witnesses and views.

The retained APK preserves `Provider<IconManager>` on the component's manager
provider field. Cold lookup now prioritizes exact generic owner arguments on the
application's shallow component roots and existing registry values. It reads only
already initialized volatile Object slots, checks all matching slots for distinct
owners, and returns before traversing unrelated Dagger descendants. It invokes no
provider method or runtime method scan. The existing 1,024-object, 4,096-field per
class-chain and 8,192-field total limits remain; priority inspection counts
toward the fallback's field budget. A wide fixture places the typed provider last
after 3,000 fields with unrelated descendants that would exhaust a broad walk.
This addresses the budget hypothesis; Debug419 did not record the rejecting
guard, so the next live bootstrap must still establish the actual result.

For API102 continuity, authenticated view binding also writes a collision-checked
private keyed tag. Its entire value is a JDK Object array containing a versioned
String marker, package String, user Integer, and exact JDK WeakReference to the
original framework Icon. No module classes, lambdas or Bitmaps enter that tag.
Retirement deliberately leaves this data on the native view. New-loader
bootstrap restores it only for the same user and the exact current bitmap Icon;
replaced Icons, avatars, malformed tags and foreign values cannot supply proof.
Normal authenticated binding may replace an owned tag, but never a foreign tag.
Restored views register in the new weak ledger and replay when the existing
shared cache becomes ready, without native owner or bitmap-cache discovery.

The deployment that first acquires proof on untagged notifications may require
an explicitly isolated QA notification to obtain the native manager consumer.
Report this as assisted acquisition. A subsequent ordinary API102 reload must
show `proof-tags restored` and ready-cache `replay refreshed` receipts, with no
new notification, plus the actual existing-icon screenshot. Neither successful
fixtures nor assisted acquisition alone establishes that continuity acceptance.

Heads-up reports must distinguish the module's own reload notification from
notifications arriving after activation. Release425 logs show old-runtime
retirement at 16:02:32.339, the cached header boundary at 33.623, an authenticated
view bind at 33.657, bridge installation completing at 33.764, and the first
finalized snapshot applying at 33.851. Previously the shared cache started only
after all bridges installed, so requests from that early bound view were dropped
while inactive and had to wait for startup replay. The cache now starts before
consumer hooks become visible; partial bridge-install failure stops the runtime
and cache. Native fallback and the existing ready replay remain unchanged.
This removes the evidenced inactive-cache interval; it does not prove absence
of every pre-hook handoff frame. No separate heads-up consumer or global mask was
added. Acceptance requires the first ordinary self-reload banner, not an extra
reload or a later notification, to verify the transient report.

Focused evidence for this extension is retained under
`.artifacts/research/perfect-night-statusbar-20261113/`. Source contracts,
synthetic device fixtures, live screenshots and API102 handoff checks establish
different boundaries and are recorded separately.

## Earlier validation

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

### November 8 checkpoint

- All 821 JVM tests and Release lint passed. The focused material/splash/
  preference suite contains 84 tests, including 13 guard/lifecycle tests.
- Signed Release `1.5.0+g30f20b3.b0284` (`105000284`) uses the KeiOS Release
  certificate. Its packaged native library is byte-identical to build 280.
- Initial API 102 Java reload preserved Launcher 19914, SystemUI 9840 and
  system-server 8726, including their parent/start identities.
- Subsequent gesture interaction did **not** pass uninterrupted-process
  acceptance: SystemUI 9840 exited with ANR at 10:32:19.792. The recorded
  input channel was `miui-aosp-back`, waiting 5001 ms for a MotionEvent UP.
  Its full Java stack was unavailable. This is not evidence attributing the
  stall to any particular hook or module.
- Android automatically started SystemUI 29427. No process/device restart
  was forced. After ordinary unlock, material compatibility was restored to
  its original ON setting; splash adaptation also remained ON.
- On that recovered generation the control center opened through the system
  command entry, all three process identities remained unchanged, and an
  accessibility service dump returned in 0.58 seconds. Volume-panel display
  was attempted but not visually confirmed; audio levels were restored.

The receipt therefore records **partial device acceptance**, not an
unqualified SystemUI stability fix. Gesture-path ANR and the earlier AVD
Watchdog remain unresolved. No other module was installed/disabled, and no
physical-device acceptance is claimed.
