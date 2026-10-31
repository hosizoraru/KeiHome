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
- Earlier versions ran the system-server workers at background thread priority.
  The October 30 follow-up below restores normal priority for workers that can
  hold shared framework locks. Resource decoding still runs in the isolated probe
  process. The original configuration call and UI threads keep their ownership.
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

## October 30 follow-up: icon styles and mixed transitions

The following evidence supersedes any interpretation of the earlier bounded runs
as complete ANR acceptance. Only the authorized HyperOS AVD was operated.

### Implemented changes

- Configuration hooks queue work only on a prepared Handler. Worker creation,
  monitor startup, shutdown and rollback no longer hold the lifecycle lock used
  by framework callbacks. The retirement deadline includes preparation.
- Track the generation's owned probe reply threads, including startup, instead
  of using `Thread.getAllStackTraces()` in system_server. Cancelled probes abort
  their sweep instead of turning into an unknown/all-packages fallback. An
  interrupted refresh remains pending for the successful generation handover.
- Monitor startup/shutdown run outside the monitor lock. Replacement waiters stay
  owned until actual thread termination. Palette queries receive a cancellation
  signal and stop with the retired monitor.
- Cache wallpaper extraction by user/home/lock IDs. Read static image data before
  asking the OEM service to extract reported colors; color callbacks and explicit
  refresh still invalidate the cache. Cancellation is checked between stages.
- Preserve theme-owned icon artwork using the inventory documented in
  `icon-refresh.md`. Both ordinary night changes and icon-style changes refresh
  affected launcher entries without process termination.
- Final version `1793322784` restores normal nice 0 for the icon and Monet workers.
  These execute system-server service code and can hold shared AMS/Settings locks;
  assigning background nice to the owner can delay foreground waiters. A captured
  740.413 ms settings call ran at scheduler priority 130 (nice 10), with
  711.290 ms runnable. Normal priority removes this avoidable inversion risk; it
  does not establish that all OS-side stalls have been fixed.

API 102 reloads succeeded at increasing versions `1793319213`, `1793320162`,
`1793320889`, and `1793322784`. No native payload/ABI changes or native deployment
restart were requested. The final runtime threads were checked at nice 0.
The focused ownership, cancellation, retirement and theme tests passed; the final
priority-only change required Release compilation, R8/lint and runtime checking,
not another complete unit-test run.

### Failures retained, not excluded from acceptance

The Boundless mixed baseline captured a 5,088.406 ms SystemUI configuration
callback. Of that time, 2,477.588 ms was runnable and 2,040.162 ms sleeping.
The critical Binder path was SystemUI -> WMS.watchRotation -> system_server
TID 9023. WM's `performSurfacePlacement` spent 499.210 of its 523.024 ms runnable.
The initial monitor owner and later wake source were different threads.

A subsequent mixed run captured an 18,853.455 ms callback. Its 8,225 ms in tracing
stop were ended by `crash_dump64` processes targeting SystemUI; the longest such
stop was 7,752 ms. The callback also contained 5,387 ms sleeping, 3,530 ms runnable,
1,416 ms running and 295 ms uninterruptible. Only 252.705 ms was synchronous Binder
wall time. The module wrapper added 4.296 ms around a 2,528.069 ms original call.
This is diagnostic amplification plus scheduling pressure, not proof of a single
KeiHome-owned deadlock. The later volume-panel `mDisplay is null` WTF reporting
began after this callback and cannot explain its start.

At **09:12:31 +08**, the asynchronous-fixture run produced a real launcher focus
ANR. ActivityManager terminated PID 9692 with reason `user request after error`;
the spawner later created PID 32243. No module kill/restart operation was issued.
The incident reported CPU pressure `some avg10=73.59` and 93% total CPU usage.
It is a failed no-restart acceptance run. SystemUI and system_server survived.
The priority-only build had not been installed at the time of this ANR.

The final priority build hot-reloaded without changing PID/start identity, but a
night-only run still triggered SystemUI SCOUT hangs. A development-only restart
of the exact SystemUI process preserved launcher and system_server identity;
another night run still reported SCOUT hangs. Thus neither the priority change
nor a clean SystemUI alone is sufficient evidence of resolution.

The AVD native-compat service also contributed CPU during install reconciliation.
Its existing package-change loop performs verified native/VINTF/APEX checks.
Its process nice was changed from 0 to 10 for this boot; verified module assets
and manifests were not modified. In the 18.853 s callback its descendants used
2.859 CPU-seconds (2.53% of six-CPU capacity), less than the diagnostic, GC and
rendering consumers. This temporary AVD adjustment is not a phone-side module fix.

The rear-display process was a further concrete load source, alongside media
decoding and compositor work. It was temporarily force-stopped for a diagnostic
control and automatically restarted as PID 3373; its package remained enabled
and `stopped=false`. The subsequent night-only run and two mixed cycles passed
with unchanged launcher/system_server/SystemUI identities and no SCOUT/ANR event.
These were not tests with the rear display permanently disabled. Restarting this
service changes runtime state and cannot be reported as a controlled KeiHome
speedup or a universal watchdog fix. No rear-display operation was added to the
module. The old SystemUI's development restart and the earlier launcher ANR remain
explicitly recorded above rather than hidden by the later passing baseline.
The first control scripts did not capture keyguard state; their navigation claims
are therefore not accepted. The final harness explicitly wakes/dismisses the
unsecured AVD keyguard and records `showing=false` before measuring transitions.
The rear launcher was separately verified resumed, visible and drawn on display 1.

The explicitly unlocked final mixed run **failed**: the first cycle retained all
process identities, but the second triggered SCOUT hangs in SystemUI, launcher,
KeiHome and system_server and timed out on activity/restoration shell calls.
Transient debugger clones also made some `pidof` snapshots ambiguous. The original
launcher PID 32243 was still present at inspection. Therefore the earlier 225.460 ms
maximum SystemUI callback from the post-rear-restart trace is only a conditional
observation; it is not final foreground/background acceptance. Historical watchdog
causality and the unlocked mixed-transition stall remain open.

After retaining this evidence, testing stopped. Default icons were restored
(no active icons archive; stock fallback), the static wallpaper's SHA-256 matched
its original backup, night mode was restored to `yes`, and the temporary wallpaper
app and named device helpers were removed. A user-authorized development recovery
reboot/relaunch of this AVD was performed to clear the sustained diagnostic/load
state. It is not a runtime switching mechanism or a passing no-restart result.
The native-compat service's temporary nice adjustment does not survive that boot.

Final APK: `KeiHome-1.0-1793322784-release.apk`. Permanent evidence, both failures
and conditional passes, is in
`/Users/voyager/.codex/visualizations/2026/10/30/keihome-theme-anr/`.

### Repeatable checks

`scripts/prepare-wallpaper-fixture.py --output <temporary-directory>` builds a
temporary, explicitly targeted AVD app with only SET_WALLPAPER. It backs up the
owner's static home/lock images, rejects live-wallpaper configuration, uses a real
app identity and increments its own versionCode before installation. Wallpaper
work runs off its main thread. The generated APK/assets are local QA artifacts.

Run `scripts/check-theme-continuity.py --wallpaper-fixture --cycles 2 --evidence
<directory>` for mixed transitions. `--phase night`, `--phase wallpaper` and
`--phase navigation` isolate the triggers. The fixture acknowledges completion;
cleanup attempts wallpaper and night-mode restoration independently. The script
records process identities, action timings, framework events and OEM SCOUT hangs.
It retains failure reports instead of equating missing `am_anr` with success.

After testing, restore the original wallpaper and night mode, then uninstall only
`os.kei.home.wallpaper.fixture`. Never automate launcher data clearing, timeout
suppression, SystemUI restart or rear-display shutdown as a module workaround.


## Follow-up control and reduced refresh work

A module-disabled control was added after the earlier inconclusive stress runs.
KeiHome was disabled for user 0 and the AVD was cold started. LSPosed reported it
disabled, and the launcher had no executable KeiHome mapping (read-only APK icon
resource mappings are not native injection). The unlocked mixed test still
produced SystemUI SCOUT hangs and an activity-start timeout. The first SystemUI
warning, at 12:45:28.166, was in keyguard-hide/insets handling, before the wallpaper
fixture launched at 12:45:29.900 and before KeiHome's UI launched at 12:45:32.387.
Later WMShell stacks waited in `IWindowOrganizerController.finishTransition`.
The original launcher PID/start ticks remained `9676 / 3583`. A temporary SystemUI
debugger clone made one `pidof` inspection ambiguous; subsequent inspection found
original PID 9661/start 3581 still alive. This control establishes that these stalls
can occur without loaded KeiHome hooks. It does not exclude added load when the
module is enabled or establish the cause of the historical AlarmManager watchdog.
The saved trace request failed to recover a useful contemporaneous owner stack.

Simpleperf sampled only system_server, SystemUI and the launcher, at 199 Hz.
A passing 30-second enabled navigation/night capture had 4,689 samples; 39.86%
landed in `goldfish_pipe.ko:writel`, mostly raster/Vulkan threads. The failing
40-second disabled mixed capture had 10,716 samples; 13.75% landed there. These
are different workloads, sample shares are not a speed comparison, and sampling
cannot explain sleeping time. No app-side GPU/driver fix is claimed.
Evidence is retained under `/tmp/keihome-anr-followup/`, including both perf.data
files, `disabled-systemui-stack.txt`, `disabled-logcat.txt`, and `disabled-mixed/`.

Two additional changes remove provably unnecessary module work:

- A color-only publication now relies on the existing launcher color observer,
  without also issuing a full LauncherApps package/shortcut sweep. A foreground-only
  change with the same background still refreshes, since the native Rx value can
  compare equal and suppress notification. First ownership,
  Java hot reload, user/native owner changes, changed fallback selection, changed
  icon-theme sources and explicit apply actions still reconcile package sources.
  Night-resource refresh remains independent of palette publication.
- Before the first enabled native tint policy in a process, filter equality stays
  stock. After enablement, conservative per-instance invalidation remains intact,
  including withdrawal and Java hot reload. The process-lifetime atomic history
  adds no Dart reference, heap write, new caller or ABI assumption. This only
  removes pre-enablement repaint work; it is not a general ANR cure.

The focused publication tests pass (4 cases), along with the native publisher's
ASan/UBSan tests, 69 AArch64 bridge cases (81 invocations), 41 resolver rejection
cases and Android ARM64/API 37 compilation. Release assembly, R8 and required lint
passed for versionCode 1793336340. No complete unrelated test suite was rerun.

The wallpaper fixture now serializes requests, handles an intent delivered to an
existing Activity, avoids uiMode recreation and acknowledges the exact request ID
and mode. The harness records timed-out operations as well as successful ones.
Older count-only wallpaper logs are not treated as proof for individual requests.
The disabled control's SCOUT/timeout evidence remains valid independently of that
fixture correction; its wallpaper completion counts are not acceptance evidence.

The module was re-enabled after the control. Installation into that already-running,
previously disabled system_server did not produce a reload owner; that deployment
is not counted as API-102 hot-reload success. The following development recovery
loads the native payload at normal process startup. Emulator shutdown/host recovery
is recorded separately from normal theme-switch process continuity.


### Classic switch recurrence on the final candidate

Version 1793337343 hot-reloaded successfully, keeping launcher `9679 / 3501`,
system_server `8814 / 2705`, and SystemUI `9673 / 3500`. The preceding candidate
1793336914 also passed a wallpaper-only green/original cycle with no new target
ANR, SCOUT hang or restart. Its two palette publications logged
`packageRefresh=false` and did not emit a package sweep.

Applying Classic through the native theme manager nevertheless produced a real
launcher ANR at **13:32:04**: `[Gesture Monitor] swipe-up` did not respond to DOWN
for 5,012 ms. ActivityManager killed PID 9679 at 13:32:06 with `user request after
error`, and the spawner created PID 8576/start 146965. No module or QA process-kill
command was issued for this event. This is a failed continuity run. The Classic
publication's 70 callbacks completed at 13:31:04; the logs do not establish them
as the owner of the subsequent block. CPU pressure was `some avg10=67.49`, with
87% total CPU including about 30% IRQ; rear display, media decoding and graphics
services were prominent consumers. The recovered native ANR report contains
waiting channels, not a conclusive lock owner. No new native tombstone was found.

Evidence: `classic-events.txt`, `classic-home-anr.txt`, `classic-module.log` and
`classic-switch.log` in the follow-up evidence directory. Later checks after the
system-created replacement cannot retroactively pass that failed run.

A secondary-display load control uses `cmd display power-off 1`, followed by
`power-reset 1` for restoration. It does not disable a package or add a runtime
mitigation to KeiHome. Results under that condition must be labeled separately
from normal two-display acceptance.


### Final bounded checks and restoration

- API-102 Java replacement passed at `1793336340 -> 1793336914 -> 1793337343`.
  Both validated deployments preserved all three process identities and requested
  no native replacement. Native code was loaded by the separately documented
  development recovery boot; Java hot reload is not a native-code swap claim.
- With secondary display 1 powered off, two complete mixed cycles passed with
  launcher `8576 / 146965`, system_server `8814 / 2705` and SystemUI `9673 / 3500`
  unchanged, with no new target SCOUT hang or ANR/crash in that run. Classic's
  blue/green wallpaper palettes and both night modes were visually checked on
  KeiHome and both ErinAO icons. Boundless's light/dark Monet colors, stock themed
  artwork, and HyperPods ordinary-icon fallback were also visible. Default was
  restored through the same native picker. These are functional checks under
  reduced display load, not normal two-display stability acceptance. Dock drag
  setup was unsuccessful and made no persistent layout change; it supplies no
  new Dock acceptance evidence.
- After `power-reset 1`, a final normal two-display mixed cycle passed with the
  same identities, three individually acknowledged wallpaper applications
  (including restoration), and no new target ANR/crash/SCOUT hang. The updated
  harness verifies the actual resumed package on display 0 after launching
  KeiHome and after HOME, rather than assuming successful key injection changed
  the foreground. Both foreground checks passed in each direction. This is one
  post-control run, not evidence that the earlier Classic ANR is fixed.
- The original blue wallpaper hash was restored, default icon source returned
  to stock fallback, night mode remained `yes`, and native Monet stayed enabled.
  Display 1 power was reset. Other LSPosed module enablement was not changed.
  The wallpaper fixture and named on-device capture helpers were removed.

The final installable Release is versionCode **1793337343**. Permanent evidence
and the APK are retained in
`/Users/voyager/.codex/visualizations/2026/10/30/keihome-anr-followup/`.
The historical watchdog owner, SystemUI stall causality and normal-load Classic
ANR remain unresolved. Do not label this version an ANR fix or a fully accepted
no-restart release on the basis of its reduced refresh work and bounded passes.

## October 31: 8593 disposed-image failure during status verification

The native-aware status work reproduced a separate failure after Monet off/on
followed by a night-mode change. The private-provider build `1793379907` finished
dispatching generation 4 (70 callbacks) at `01:12:46.247 +08`. The launcher
brightness callback arrived at `01:12:48.467`; the first `Cannot clone a disposed
image` exception followed at `01:12:48.877`. SafeRescueParty reached its error
threshold and launcher PID `1202` died. Subsequent provider-start timeouts affected
replacement processes, including `25635`; they did not precede the original
image error. The recovered launcher was `25951 / 1121724`. SystemUI retained PID
`9628` but reported SCOUT hangs. This is a failed continuity check, not an ANR
closure or a status-only deployment failure.

Read-only analysis of the exact 8593 ELF identifies a narrower ownership lead:

- `RawImage.updateRenderObject` calls `Image.clone` at `0x158b98c`, before passing
  the clone to the RenderImage setter. The exception at `0xa06ba8` follows the
  disposed flag check on the Dart Image wrapper. `Image.dispose` writes that flag
  at `0x93e3a0`; another handle retaining the engine image would not repair this
  already-disposed wrapper.
- `MonochromeController._refreshIcons` calls `MonochromeIconCache.clear` at
  `0x1a5e658`, before notifying icon controllers. The cache disposes the first and
  optional second image at `0xed2774` and `0xed278c`, then clears its map.
- `MonochromeIconCache.get` at `0x13a3b24` returns the cached entry without cloning
  its images. `_MonochromeIconWidgetState` stores those images and passes them to
  RawImage from its build path. Its widget-update comparison does not include a
  disposed flag or cache generation. By contrast, the inspected IconCacheV2
  getters clone their returned images.

These contracts could leave a mounted widget holding a wrapper disposed by cache
invalidation. The missing evidence is the identity link between a specific
cache disposal, the widget-held wrapper and the failing RawImage. The next useful
observation is that disposal caller/identity relationship on the Dart thread,
without retaining raw Dart references or bypassing GC ownership. Do not replace
this proof with suppressing the exception or preventing all image disposal.

Existing Java package/shortcut callbacks can overlap the launcher's asynchronous
work; the 220 ms pacing is between packages, not between each package callback
and its shortcut callback. Completion of dispatch does not establish completion
of that image work. Native tint/selection hooks do not directly dispose images,
but scoped filter equality overrides and enabled-state re-emission can influence
rebuild timing. Neither is established as the cause by the captured stacks.

This failure also occurred with the intermediate Settings reply transport.
Moving status replies to the private provider removes that observer interaction
but did not remove the failure. See [status-dashboard.md](status-dashboard.md)
for the successful status/reload checks and retained evidence paths.

## October 31: community pack apply and periodic rollback

The older Arcticons apply capture does not contain the actual final launcher
exit. At 05:34:06 the native theme application succeeded; the system sent an
ActivityRelaunchItem and launcher 9698 explicitly skipped process termination
because a secondary display was present. Its Flutter isolate was recreated in
the same process. The captured exception was `Application label not found for
package: os.kei.hyper.home.test` in RecentsTaskLoader, not disposed-image cloning.
The capture ends at 05:34:22.958. Persisted ApplicationExitInfo separately records
PID 9698 exiting at 05:34:30.747, reason UNKNOWN/status 0, with no trace or ANR
description. The missing interval prevents assigning a cause.

After the next Phone boot, a distinct 18:46 event was proved to be
`miui.drm.ThemeReceiver$ValidateThemeTask` invoking `ThemeRuntimeManager.resetDefault`
and deleting the community icons archive. KeiHome now registers only completed,
validated static conversion digests with that framework check. It does not
suppress resetDefault, arbitrary DRM failures, or launcher errors.

On releases 1793448125 and 1793448956, system/Theme API 102 reload, Arcticons
reapplication, a real periodic validation, and Lawnicons native application
preserved launcher 9685/start 3565, system-server 8810/start 2708 and SystemUI
9676/start 3561. Native application still recreated a Dart isolate within the
same process. Long app-start waits remain in the AVD observations, so this is
bounded continuity evidence rather than closure of the earlier watchdog,
SystemUI ANR, or disposed-wrapper lifetime issues.

Evidence: `/tmp/keihome-pack-followup/launcher-exit-info.txt`,
`reapply/`, `registered-validation/`, `second-validation-deploy/`,
and `lawn-native/`. The image-recovery branch still has no positive runtime
counter in the accepted captures.

### October 31: MAML apply gesture-monitor ANR

The later Arcticons MAML apply run produced another SystemUI input timeout:
`miui-aosp-back` and `MultiTaskSwitch` gesture monitors were reported
unresponsive, followed by ActivityManager killing SystemUI PID 9676. The
launcher did not restart in the subsequent MAML light -> dark -> light cycle;
it remained PID 9685/start 3565. The exact log is
`/tmp/keihome-pack-followup/maml-arcticons/apply/logcat.txt`.

This incident is kept separate from the generated MAML selector: the selector
was consumed and redrawn successfully after the apply step, but there is no
module-off comparison proving that the SystemUI stall is caused by KeiHome.
The project therefore does not claim the watchdog/SystemUI ANR family is
resolved.
