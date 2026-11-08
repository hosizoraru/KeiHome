# Provider publication timeout investigation

This checkpoint concerns the Phone AVD only. No APK or Hook update was installed
on the physical phone. The receipt is
`.artifacts/research/provider-timeout-20261108/acceptance.json`.

## What the timeout does and does not identify

`timeout publishing content providers` means ActivityManager did not observe
publication before its deadline. It does not establish that a Provider's
`onCreate()` was entered, or that it was the expensive operation.

There are two distinct sets of evidence:

1. Before the original instrumentation attempt, SystemUI already had a Scout
   warning. The target process was killed before its test method ran. Other
   process starts were also delayed. Later system_server snapshots show a
   DeviceIdleController -> AMS wait chain; SystemUI's main thread waits for HWUI
   drawing, with RenderThread in `vulkan.ranchu` / `vkGetFenceStatus`. These dumps
   are not atomic and some took over 23 seconds. They do not prove a permanent
   Java lock cycle or establish Vulkan as the original cause.
2. The preserved kernel log reports SystemUI main, Binder and background threads
   in `D` state waiting for mmap read locks. Signal Catcher waits for an mmap
   write lock in `munmap`; diagnostic processes reading a remote cmdline also
   wait for an mmap read lock. This explains why even diagnostics can hang.
   The writer/initial lock owner was not identified. No forced DEX close,
   module unmap or explicit GC was introduced.

Files include `trace_44`, `trace_46`, `trace_50`,
`kernel-before-restart.txt`, `server-lock-chain.txt` and the original incident
under `.artifacts/research/api102-retirement-20261108/`. A single ordinary startup
of the old installed Debug 290 reached its idle loop and AppLog worker despite
the troubled environment. The incident was intermittent.

After preserving the scene, the AVD was stopped normally and cold-started with
the same device and graphics configuration, retaining data. Snapshot load/save
were disabled for that run. This environment recovery is separate from a code
fix and does not prove the old SystemUI hang eliminated.

## Locating the second failure

Debug 294 adds platform-only `KeiHomeStartup` phase logs for
`attachBaseContext.begin/done` and `onCreate.begin/done`, with monotonic time and
PID. These do not initialize MMKV or the app-log archive.

After cold-start recovery and a successful ordinary API 102 upgrade, the first
instrumented process, PID 1289, again exhausted its publication deadline.
Its logs show multi-second `beforeCreateAppContext` / `createAppContext` work,
class-loader setup and HWUI initialization. It never emitted
`attachBaseContext.begin`. The replacement ordinary Provider process, PID 1846,
did emit all four stages and completed Application initialization.

The same Debug target and test APK were then prepared with ART's `speed` filter.
The next run entered all four tests and passed: three deferred-startup tests and
two explicit reload rounds in one retirement test. All six callbacks for
system_server, SystemUI and Themes returned `SUCCEEDED`. The first passing
test run took 6.382 seconds inside the runner, 9.65 seconds including startup and
the host wrapper.

This supports cold loading of the unminified instrumented APK/class-loader
combination as an important contributor to that second timeout. It does not
establish that ART compilation cures the earlier kernel mmap stall, nor that
every real-device Provider delay has the same cause.
These were sequential runs, not a randomized cold-cache comparison: cache
warming and background load also changed. No universal startup improvement
ratio is inferred from their timing.

## Production startup hardening

The source order is:

```
Application.attachBaseContext
  -> construct/create Providers
  -> publishContentProviders
  -> instrumentation.onCreate
  -> Application.onCreate
```

Verified against local Android 37.2 SDK `ActivityThread.java`, notably the
`handleBindApplication()` and `installContentProviders()` implementations.
[AOSP ActivityThread](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/app/ActivityThread.java)
documents the corresponding platform path.

Only automatic `ProfileInstallerInitializer` metadata is removed from Startup.
Lifecycle and Emoji remain eager, explicitly guarded by a merged-manifest
device test. The application registers a one-shot lifecycle callback and posts
ProfileInstaller initialization after the first Activity's `onActivityPostResumed`.
It retains the library's own next-frame scheduling, jitter and background write.
No Activity means no display/vsync initialization for this optional task.

This removes a synchronous DisplayManager/Choreographer dependency from the
Provider-publication path. It is targeted hardening, not attribution of PID
1289's failure to ProfileInstaller: that process had not yet reached the app
attachment stage.

The exact retained library behavior was inspected in
[ProfileInstaller 1.4.0 source](https://dl.google.com/dl/android/maven2/androidx/profileinstaller/profileinstaller/1.4.0/profileinstaller-1.4.0-sources.jar).
ProcessLifecycle remains eager because its initializer requires Startup
discovery. Framework API 102 preferences and retirement semantics are unchanged;
the already committed successor-owned retirement maintenance is included.

## Development acceptance guard

`scripts/hyperos_avd_health.py` checks the authorized Phone AVD before deployment:

- boot completion and a bounded ActivityManager request;
- SystemUI gfxinfo response, including zero-exit-code dump timeouts;
- two samples of the SystemUI main-thread state, rejecting persistent `D`;
- a complete, CRC-validated screenshot of the correct display;
- recent Provider, SystemUI ANR and Watchdog failures, bounded to the current boot.

A copied screenshot or cached gfxinfo response alone is not evidence that UI
interaction is healthy. These checks are admission filters, not a substitute
for real interactions or proof that every thread is unblocked. They neither
restart a process nor repair a failed device.

With matching signed Debug and test APKs already installed, run:

```bash
python3 scripts/test-api102-retirement-avd.py \
  --evidence .artifacts/research/provider-timeout-20261108/driver-acceptance
```

The driver prepares ART for those two development APKs, removes only its own old
result file, runs the opt-in tests once, and requires all four test completions
plus fresh successful callbacks for both rounds on all three hosts. An `adb`
exit code of zero with `Process crashed` is a failure. Missing live hosts also
fail this full-acceptance driver rather than silently reducing the scope.
Timeouts preserve partial output and stop without automatic retry. System
Provider and Watchdog deadlines are not changed.

Formal Release startup and ordinary UI interactions are verified separately
from instrumented Debug startup; the final receipt records their actual scope.
