# Gesture navigation backdrop investigation

## OS4 settings hierarchy

The Desktop entry opens an overview with a refreshable system status card,
the MIUIX switch preference and a short app-restart reminder. The toolbar Help
button opens a scrollable details sheet. App selection has its own destination
within the Desktop route. Older saved status and help routes restore to the
overview.

The app selector keeps All/Selected counts in a persistent Glass segment and
filters the existing inventory locally. Its toolbar offers origin filters and
sorts by name, package or update time. Pulling down at the top of the list or
tapping Search reveals the app-name and package search field. Visible rows
load original icons through the same bounded cache as the original-icon
preservation list. Missing selected packages remain removable in the all-origins
view, and the 64-app limit still permits removals. The status card rereads
configuration and backend status. The app menu has a separate inventory refresh
action. Read/save failures disable edits until the preferences can be read
successfully.

Every destination uses the shared Scaffold backdrop and toolbar buttons.
Runtime acknowledgment remains separate from visual compatibility. The feature
stays experimental, defaults off and still requires a full target-app restart
after changes.

Debug b0683 passed 16 instrumentation checks, including compact and wide
navigation, help dismissal, status refresh, pull-down search, and selection
preservation when filtering. Phone inspection covered the status card, Help
sheet, loaded icons, search and the filter and sort menus. Debug assembly and
the three-locale resource audit passed. Deployment observed nine successful
Java reloads and no failures among the observed targets. Critical process
identities stayed unchanged. Pad coverage came from the wide layout fixture.

## Scope and status

November 18, 2026: HyperOS Phone AVD and ColorOS Phone AVD comparison, followed
by read-only inspection of the authorized physical Xiaomi device.

The earlier capture/overlay experiments below remain disposable AVD prototypes.
The app now includes a separate, default-off **experimental** native-rule feature
under **Desktop > Gesture navigation fill**. The physical device was not modified.
This feature does not use the prototypes' screenshot loop or overlay.

Run evidence: `.artifacts/research/navigation-bar-20261118/`.
Reproducible research fixtures: [nav-backdrop README](../scripts/research/nav-backdrop/README.md).

## Experimental native-rule feature

The screen provides a master switch, explicit app selection (at most 64 apps),
search, current-version backend availability and a configuration acknowledgement.
A saved choice and a choice received by system_server have separate states.
Acknowledgement means rule delivery is configured; it does not prove that a
particular window can be filled. Changes, including turning the feature off,
require a complete restart of the affected app because the OEM client caches its
rules during process initialization. KeiHome does not stop user apps automatically.

`NativeNavigationBackdropRuntime` hooks only the existing system_server
`MiuiNBIManagerService$Inner.getSystemNBIRules(String)` delivery method. It calls
the original method first. For a selected package, `NavigationRuleOverlay` copies
the returned Bundle and changes only disabled `mode=0` activity policies to native
automatic `mode=-1`, enabling delivery. Packages without OEM rules receive an
enabled empty activity map and use native defaults. Explicit colors, popup/dialog
choices, sampling settings, version gates and unknown fields are preserved.
Malformed, oversized, deeply nested or unsupported rule shapes retain the
original result.

The caller must be an app UID in the selected user, and PackageManager must
confirm that the UID owns the requested package. Own app, SystemUI and Home are
excluded. Unselected calls return before package lookup or JSON parsing.
Preferences use the existing API 102 listener; acknowledgement shares the
existing refresh worker. No new module scope, native payload, thread, periodic
capture or overlay permission is introduced. The OEM controller map and cloud
rules are never rewritten.

### API 102 collection transport

The app-side `RemotePreferences.Editor.putStringSet` retains the concrete Set
implementation in its Serializable payload. Passing Kotlin's `emptySet()`
therefore failed in the framework process with `BadParcelableException` caused
by `ClassNotFoundException: kotlin.collections.EmptySet`. Its local map updates
before remote commit, so local readback alone incorrectly suggested success.
`NavigationBackdropPreferences.write` now snapshots selections into a Java
`HashSet`, including empty selections. A device regression captures the actual
editor value and round-trips the wire Bundle with the boot class loader for
zero, one and multiple packages; it also reproduces the former failure.
Configuration acknowledgement remains necessary after this serialization fix.
The existing preserved-original-icon writer uses a Java mutable collection
already and does not have this boundary defect.

### Confirmed remaining boundary

On the HyperOS Phone AVD, Health receives `mIsNBIEnable=true` and an empty native
activity map. Its SplashActivity and MainActivity then log
`already set forced E2E by app`, and `DecorViewImmersiveImpl` returns before
installing native color compatibility. The black strip remains. This is a
confirmed limitation, not a successful visual fix or failed preference delivery.
App-drawn bars and patterned skins also cannot be repaired by merely delivering
native color rules. The UI states these limits rather than disabling application
edge-to-edge behavior or reintroducing a global screen-capture overlay.

Bilibili's MainActivityV2 does enter native `mode=-1`, and `NavImmersive` reports
`ARGB(255, 23, 24, 26)`, matching the default dark tab background. This confirms
the native automatic-color path, not patterned-skin extension.

The Bundle/preference/status device tests are independent of live app choices.
The controlled foreign-UID test additionally requires Health/Bilibili selected
and enabled, the original Bilibili MainActivity mode 0, and
`am instrument --no-hidden-api-checks -e navigationRuleDeliveryActive true`.
The flag is limited to the test process; no device-wide hidden-API policy is
changed. Compact and wide-constraint navigation tests exercise the entry and
return path. Wide constraints are not a substitute for actual Pad acceptance.

## November 18 acceptance

Phone AVD launcher 9259 was validated with Debug b0666 and the original native
payload. Eleven device tests passed: six backend/transport tests and five Desktop
navigation tests, including compact and wide constraints. The real UI was used
to select Health/Bilibili, enable delivery, then disable it and clear both
selections. The backend acknowledged the empty disabled choice. Both target apps
were completely restarted afterwards; Bilibili returned to native mode 0.
Its complete OEM rule text matched the original SHA-256, and Health still had
no OEM rule entry. Theme, palette, night mode and all three critical PIDs matched
the baseline. Temporary native log properties were restored.

Release b0667 builds and passes lint but was not installed: its build variant
produces different native bytes. This task preserves the already initialized
native runtime rather than changing it for a Java-only feature. Actual Pad
hardware and physical-device feature deployment remain unverified.

The final deployment observed eight successful reloads and one failed reload for
`com.miui.securitycenter:cache`. Events identify `umms_selfcheck` system
reclamation of that PID during the update. Critical Java retirement/adoption
receipts were reconciled independently; the failed target is not reclassified
as a success. System log contained non-UTF-8 bytes, exposing a developer-tool
decoder failure. The deploy reader now preserves those bytes as escapes instead
of aborting before reading process-exit evidence; the raw receipt is retained.

## Distinguish the three owners

1. The application and its window decor decide content padding, the background
   behind that padding, and requested navigation appearance.
2. OEM framework compatibility code can choose a background or construct a
   separate surface behind the navigation area.
3. SystemUI draws the gesture handle and determines its contrast.

Changing handle contrast does not fill a missing background. Forcing transparent
navigation alone also does not extend a skin bitmap into an area the application
has left empty. Do not change icon rendering, content insets, or gesture hit
regions to disguise this problem.

## Observations

| Surface | Observation |
| --- | --- |
| HyperOS AVD, Xiaomi Health 3.59.7, target 35 | Bottom content is `#242424`; navigation area `[0,2376][1120,2436]` is black. The view hierarchy's content frame ends at 2376 inside the full-height decor. |
| ColorOS AVD, Xiaomi Health | Same kind of black separation, despite the OEM navigation color-picking property being enabled. ColorOS does not automatically solve every application. |
| HyperOS AVD, default Bilibili appearance | Bottom content and navigation area both measure `#17181A`; there is no color seam in this captured state. |
| Physical Xiaomi, Bilibili 9.19.0, target 35 | Custom skin has a textured light bottom tab background, followed by a dark 65-pixel navigation area `[0,2591][1220,2656]`. |

The physical capture was accepted only after confirming Bilibili
`MainActivityV2` was resumed before and after it. This device has multiple
displays; its `screencap` writes a warning before the PNG when no display ID is
given. The run retains the original stream and the decoded PNG separately.
Future captures should select the display explicitly.

The AVD and physical Bilibili base APKs have the same SHA-256:
`a9a0ed372797d8310ebff3b7237ea9605eea6b41a3e2298eef6a9faae1d0fbd6`.
DexSQL inspection of `HomeStatusBarComponentKt.c(MainActivityV2)` separates
ordinary navigation-color requests from the enforced-edge-to-edge path. The
latter passes the ordinary theme color `R.color.Wh0` and night-mode appearance
to `NavigationBarBackgroundController.updateStyle`. That controller can paint
an application-owned background view as well as request a window color.
The skin footer uses a separate `tailBgPath` bitmap.

This code and the physical pixels support a missing connection between the skin
background and the navigation backdrop. The run did not instrument the app's
branch execution, so it does not prove which branch ran on every frame. No
account, session, or skin-entitlement state was transferred to the AVD.

## HyperOS native policy

The exact installed framework was inspected with DexSQL:

- `com.android.internal.policy.MiuiNBIManagerImpl`
- `com.android.internal.policy.DecorViewImmersiveImpl`
- `com.android.internal.policy.NavigationBarImmersiveController`
- `com.android.nbi.MiuiNBIController` and its parsing/service classes

The native service is `miui_navigation_bar_immersive`. On both the physical
device and AVD, Bilibili's package rule enables NBI, but explicitly sets
`tv.danmaku.bili.MainActivityV2` to `mode=0`. Physical logs confirm:

```text
sf_sampling_mode ... MainActivityV2 is 0
start choosing immersive policy ... MainActivityV2, mode: 0
apply policy: DISABLED
```

This is an explicit OEM activity rule, not a KeiHome preference. The inspected
configuration contains no explanation for the exception. Do not present a
guess about Xiaomi's intent as a confirmed cause.

Xiaomi Health has no package rule in the AVD's loaded NBI table. The framework
also has independent opt-out and enforced-edge-to-edge gates. Therefore,
changing one cloud activity mode is not a general remedy for every application.

A temporary Bilibili main-activity `mode=-1` experiment reached the existing
native dynamic policy and selected `ARGB(255,23,24,26)`, matching the default
AVD bottom tabs. It does not establish that single-color selection will repair
the physical device's patterned skin.

**Native command hazard:** `cmd ... update PACKAGE activityRule::JSON` replaces
the activity map. Do not send a one-activity fragment and assume it merges.
The exploratory run detected this and restored all 15 original Bilibili
activities from the saved configuration. Exact package and all-package `list`
outputs were initially compared, but the no-argument `list` output is only
`No NBI rules for null` on this build, despite its help claiming to list all
packages. That output is not evidence about the complete table. The full
Bilibili package output, including all 15 activities, was verified against its
original snapshot. No Bilibili override remains installed.

## What ColorOS contributes

The exact ColorOS framework contains:

- `NavBarBgPolicyHelper`, with application/activity policies and separate
  edge-enforcement, canvas-pick, and SurfaceFlinger-pick options.
- `ViewRootImplExtImpl.createOrUpdateDecorNavBarBgLayer`, constructing
  `DecorNavBarBg-OverlayLayer` under the application's surface, with a cropped
  hardware buffer and navigation-area geometry.
- `setDecorNavBarBgMetadata` and scheduled effect-refresh on/off paths.
- Motion-dependent refresh handling, rather than unconditional full-display
  screenshot polling.

These are useful architectural examples. The compositor metadata and vendor
effect implementation are not a portable Android API. Java method names and
enabled properties do not prove that an equivalent compositor effect exists
in HyperOS, nor that ColorOS has activated it for the captured Health window.

## AVD visual feasibility test

A disposable, no-network preview APK drew a background only over Health's actual
60-pixel navigation strip, below the SystemUI gesture handle. A timed overlay
was used to test composition, not as the proposed production implementation.

The visible background changed from `#000000` to `#242424`, matching the tabs.
Both Health/Mine tabs remained operable, the handle remained visible, and the
application content bounds did not move. The run captured a 60-fps recording,
active screenshots, and the return to the original black region after teardown.

The preview uses a static, explicitly selected color. It does **not** validate
automatic window tracking, keyboard handling, videos, gesture transitions,
secure windows, performance, or bitmap-skin continuation. The helper is removed
after the experiment; no always-on overlay is left behind.

## Textured-background renderer experiment

A second disposable fixture, `os.kei.hyper.home.navlab`, draws only its own
window and uses no networking or overlay permission. It packages the authorized
footer artwork solely in a local test APK; artwork is not committed. Its modes
compare a dark gap, a mean color, a repeated final texture row, and a gradual
transition from that row to a cached 16-column texture approximation. The last
mode is not a Gaussian blur.

On the 1120-by-2436 HyperOS AVD, the mean absolute RGB-channel difference across
the seam at rows 2375/2376, excluding the outer 24 pixels, was:

| Mode | Mean channel difference, out of 255 |
| --- | ---: |
| Dark-gap control | 184.853 |
| Mean-color fill | 8.452 |
| Final-row continuation | 0.017 |
| Final row with gradual texture smoothing | 0.017 |

The two texture modes had a maximum seam difference of one channel level.
Their pixels differ farther down the strip, confirming that the smoothing
path actually renders. Shader setup occurs on size changes, rather than
repeated screenshot decoding during drawing.

Keyboard, owned dialog, dialog dismissal, and portrait/landscape recreation
were exercised, with screenshots and a recording. The fixture suspends its
own backdrop for IME, dialogs, fullscreen, and its secure-window test mode.
Rotation retained the selected renderer mode. These are cooperative-window
tests, not automatic classification of arbitrary third-party windows.

An attempted AVD Bilibili footer-only configuration was rejected by Bilibili.
The original configuration was restored byte for byte and the owned copied
asset removed. Therefore, the textured renderer has **not** passed acceptance
inside an actually skinned third-party Bilibili window.

## Bounded native edge capture

`NavEdgeCapture.java` is an API-37 AVD-only root probe, not a background service.
It uses the inspected WMS `captureDisplay` / `ScreenCaptureInternal` API with
a four-row source crop immediately above verified navigation insets. Each
output is 512-by-4 pixels. Secure and protected content policies are both
`THROW_EXCEPTION`; the probe never requests protected-content capture.

The final run completed 16 samples, with both image files and timing reports.
Real Xiaomi Health produced eight 512-by-4 frames whose edge was uniformly
`#242424`, consistent with the earlier real-app fill experiment.

| Loop stage | Median | First sample |
| --- | ---: | ---: |
| Initial owner/inset guard | 1.35 ms | 6.39 ms |
| Capture and GPU buffer delivery | 5.92 ms | 32.92 ms |
| Complete loop, including post-capture owner check, CPU readback, PNG, and file I/O | 10.31 ms | 80.60 ms |

These are wall times from a short AVD run, not CPU utilization, physical-device
power measurements, or production frame-time benchmarks. The complete-loop
inclusive P95 over only 16 samples was 36.56 ms. Standalone VM startup is
outside these timings. A 2048-pixel output does not establish that underlying
compositor work or repeated sampling is free.

The final probe stopped after IME appeared, after rotation changed display
geometry, and after Home changed the owning activity. Hidden navigation and
the fixture's `FLAG_SECURE` window produced zero image files. Activity/task
identity is checked again after capture. The standalone process flushes its
report and exits without native-static teardown, which otherwise raced the
capture callback threads in an exploratory run.

The probe's task/activity identity is **insufficient for same-activity dialogs**
or embedded video detection. Keyguard and non-fullscreen windowing modes have
guards in the source but were not independently exercised. Protected DRM
buffers, PiP, split screen, fold transitions, and combined scene changes
remain unverified. The fixture's cooperative dialog guard does not close
these global ownership gaps.

## Handle-contrast transition

Background continuity and handle contrast need coordinated acceptance.
In the fixture, changing to a light fill and requesting light-navigation-bar
appearance immediately still left the old pale handle visible in early
captures. At approximately 0.18 and 0.48 seconds after input returned, contrast
was low; by 0.88 seconds the handle had changed to dark and remained stable
through 2.58 seconds. The run retains the recording and timed pixel samples.

This establishes a visible delay, not its precise allocation among SystemUI
sampling, scheduling, and animation. A production backend needs a verified
handle-appearance path and transition behavior along with its background
layer. Filling the strip alone is not acceptance.

## Shared strategy to validate before product integration

Resolve the navigation rectangle and the owning window first. Keep one policy
decision shared across any eventual rendering backends:

- Preserve an already continuous background and application fullscreen behavior.
- For a verified empty strip beside a uniformly colored bottom edge, consider
  a matching color backdrop.
- For a textured skin, require a background-only drawable or a bounded
  compositor/background source. Do not average the whole skin into one color,
  or copy tab labels and characters into the navigation area.
- If the texture source is unavailable, leave the original rendering in place
  until its ownership and scene qualification are established. The fixture
  validates a controlled renderer; it does not provide a generic
  background-only source for arbitrary third-party windows.
- Do not intervene over IME, dialogs, video surfaces, transient windows,
  multi-window layouts, hidden navigation, or protected content without
  independent support and acceptance.
- Bind any runtime layer to the owning window's lifetime. Invalidate on skin,
  configuration, inset, focus, and visibility changes. A palette-only cache key
  is insufficient. Do not retain a previous application's backdrop.
- Prefer existing OEM/compositor mechanisms when actually supported. Do not
  continuously capture the whole screen to obtain a small bottom background.

The common decision chain should resolve window identity and scene eligibility,
then choose the background source and rendering strategy, and finally coordinate
handle appearance and teardown. A cache must include window/source identity,
geometry, configuration, and skin generation. Prefer an already available
background or an event-triggered, bounded sampling burst followed by reuse;
idle screenshot polling is not the proposed design.

The current evidence separates the possible backends:

| Backend | Demonstrated | Remaining boundary |
| --- | --- | --- |
| HyperOS native NBI | Default Bilibili dynamic color selection | OEM exceptions, E2E gates, and patterned backgrounds |
| Cooperative app-window renderer | Texture continuity and local scene guards | Does not control arbitrary third-party app windows |
| Bounded WMS capture | Actual four-row samples, several stop conditions, secure rejection | Global window ownership, scene classification, timing and power |
| Timed external overlay | Real Health color fill beneath the existing handle | No automatic lifecycle or production security/scene policy |
| ColorOS decor-parented layer | Inspected in the exact OEM framework | No verified equivalent compositor API or port on HyperOS |

An app-local backend can reuse view/drawable information but would need access
to those third-party app processes. A compositor backend avoids that scope
expansion but needs reliable window metadata and supported surface parenting.
Neither capability is established by the disposable fixtures.

The read-only `scripts/probe-gesture-nav-backdrop.py` detects a conservative
uniform-color candidate from an existing screenshot and verified inset
coordinates. It is an offline research classifier, not a runtime owner
resolver or a safety guarantee. Unknown scenes are rejected by default.

```bash
uv run --with pillow python scripts/probe-gesture-nav-backdrop.py screenshot.png \
  --rect 0 2376 1120 2436 --gesture-navigation --scene ordinary
python3 -m unittest discover -s scripts/tests -p test_gesture_nav_backdrop.py -v
```

The classifier accepts the original AVD Health empty strip, reports
`already-continuous` for the successful preview and the default AVD Bilibili
capture, and rejects the physical Bilibili skin as `nonuniform-content-edge`.
These different results are intentional; the patterned-skin problem is not
solved by the demonstrated color backdrop.
