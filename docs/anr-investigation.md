# Theme-switch latency and historical ANRs

## Evidence and limits

The historical 2026-10-30 03:03 watchdog and 03:52 SystemUI input ANR are different
observations. The watchdog's main thread waited for the AlarmManager lock. An
AlarmManager thread held alarm/AMS locks while waiting for AppOps. The AppOps
owner was not captured conclusively; several thread dumps took tens of seconds
and no longer form a simultaneous lock snapshot. This does not establish a
KeiHome-owned deadlock or identify an OS lock to bypass.

The SystemUI main thread was blocked in `IWindowSession.remove` while its
configuration listener removed the navigation bar. Its gesture monitor then
timed out. The saved system-server trace does not recover the corresponding
blocked reply/lock owner. KeiHome's color worker was idle and its icon worker
was sleeping between callbacks at their sampled moments; neither observation
proves their state at the start of the timeout.

Sources remain in `/tmp/keihome-wallpaper-validation/`: `watchdog.txt`,
`system-native-trace.txt`, `system-watchdog-trace.txt`, and
`systemui-gesture-anr.txt`. These incidents remain causally unresolved. The
changes below fix concrete work/scheduling problems; a passing regression is
not evidence that every historical cause has been eliminated.

The investigation follows [Android's ANR diagnosis guidance](https://developer.android.com/topic/performance/anrs/diagnose-and-fix-anrs):
inspect runnable time, lock contention and Binder reply threads, and distinguish
system load from application blocking. MiuiBackGestureHook's local system-server
and lifecycle code also informed the owner-thread and hot-reload review. No
AlarmManager, AppOps or SystemUI hook, scope expansion or timeout override was added.

## Changes

- Prepare the icon worker's Looper at installation and failed-reload recovery.
  A normal first theme callback no longer waits for a new background Looper
  while the caller can hold the window-manager lock. Failed installation closes
  the worker and monitor. `KeiHome:night-observer` traces only our scheduling
  callback, not the original configuration transaction.
- Run color monitoring, icon callback dispatch and isolated resource probing at
  Android background thread priority. The original system configuration call
  and UI threads retain their priorities and ownership.
- Full palette sweeps already include every launcher entry, so skip the redundant
  night-resource probe that formerly preceded them.
- Retain up to four mask-result configurations, including both night variants.
  Package sources or foreground user changes invalidate them; unknown results
  retain their retry deadline and same-source unusable-mask fallback.
- Publish through one authenticated native exchange instead of a separate
  readiness exchange followed by publication. Every publication still requires
  the exact greeting, current owner and ACK before any setting is persisted.
- An unchanged palette/mask set no longer sends another color notification.
  The last successful native owner includes PID and start ticks, so a new
  launcher process or a fresh Java generation still receives initial refresh.
  Disabling and manual-selection ownership are preserved.

The native payload and Dart ABI were not changed. API 102 still replaces the
Java runtime and drains its owned jobs. No launcher termination/restart path
was introduced.

## Focused Release validation

Only `emulator-5580` (`KeiOS_HyperOS4_Phone`) was operated. Installed versionCodes
increased `1793311572 -> 1793313148 -> 1793314134 -> 1793314486 -> 1793315520`. Each deployment
completed API 102 Java reconciliation and preserved launcher, system_server and
SystemUI identities without requesting native replacement.

| Trace | Configuration changes captured | Probe-service binds | Maximum SystemUI configuration callback |
| --- | ---: | ---: | ---: |
| Baseline | 3 | 9 | 237.1 ms |
| First candidate | 4 | 3 | 571.9 ms |
| Candidate `1793314486` | 10 | 2 | 193.1 ms |
| Final Release `1793315520` | 10 | 2 | 293.5 ms |

The baseline ring buffer retained three complete changes. The final trace used
a larger buffer and ran more changes. These are bounded observations, not a
controlled performance benchmark or an overall speedup percentage. The first
candidate's slower SystemUI timing was retained, rather than discarded. The
final module scheduling slice reached **0.085 ms** at most; the complete system
configuration transaction reached **121.96 ms**. Candidate `1793314486` reached
1.173 ms and 49.81 ms respectively. SystemUI timings still vary; this work does
not demonstrate lower global configuration latency. None is a worst-case guarantee.

`scripts/check-theme-continuity.py --cycles 6 --evidence <directory>` performed
12 light/dark selections with app launch, recents and HOME input on each of the
last two installed versions. All six rounds on the final Release and the final
settling period retained these PID/start-tick identities:

- Launcher: `9692 / 3609`.
- system_server: `8839 / 2743`.
- SystemUI: `568 / 199972`.

The event log contained no new ANR/crash for those processes or KeiHome, and the
script restored the previous night mode (`yes`). Its longest shell operation
was 0.184 s; that is command completion time, not the time all icons finish
refreshing. Screenshot acceptance separately confirmed Expressive light/dark
icons for KeiHome and both ErinAO packages, with HyperPods retaining its normal
icon. No physical device was operated.

The 41 selected runtime tests passed (native transport, hot-reload lifecycle and
bad-mask retention). The 17 color/customization tests passed in the focused
Kotlin/JUnit run. Release assembly, R8 and required lint passed. No full suite
was rerun for later thread-priority/worker preparation and adaptive-label changes;
their checks were Release compilation, real hot reload and the device regression.

Evidence: `/tmp/keihome-anr-fix/`, particularly `final.perfetto-trace`,
`final-sql.txt`, `final-continuity/continuity.json`, `final-continuity/events.txt`,
`deploy-complete/deployment.json` and the light/dark screenshots. The final APK,
reports and selected visual evidence are also retained under
`/Users/voyager/.codex/visualizations/2026/10/30/keihome-anr-monet/`. A temporary
ContentObserver helper did not receive even known color changes, so its zero
counts are excluded from acceptance. The code-level notification deduplication
is not presented as an experimentally measured notification-rate reduction.

Future recurrence requires a trace covering the blocked Binder reply and the
contemporaneous lock owner. The historical incidents should not be closed solely
because these theme-switch runs passed.
