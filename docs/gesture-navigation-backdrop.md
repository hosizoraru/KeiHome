# Gesture navigation backdrop investigation

## Settings status card during scroll (November 22)

The 1.13.0 b0647 report and screen recording showed a strip missing from
the status card's title, badges, and artwork while scrolling. The reporter
used a Xiaomi 17 on HyperOS OS4.0.6.0.XPCCNXM. The same clipping reproduced
on a Xiaomi 17 Pro on OS4.0.8.0.XBLCNXM. Removing the card's inner clip in
b0661 still reproduced it with progressive blur. Turning off app blur cleared
the artifact during the same swipe sequence.

Keep the list's `layerBackdrop` ahead of `keiHomeTopBarScroll` in the
modifier chain. MIUIX captures the viewport for the Glass toolbar, while
overscroll places the list in a translated, clipped layer. Capturing outside
the translation gives the toolbar a stable viewport and retains the list's
native rebound and the card's rounded clipping. See the [Glass list example
in the MIUIX playbook](miuix-os4-playbook.md#33-录制页面内容glass-在外部采样).

On the Xiaomi 17 Pro, signed b0662 retained the title, summary, badges and
status artwork through slow swipes, quick reversals and return to the top.
Captures cover light and dark progressive blur, light progressive blur at
130% system font scale, and dark Gaussian blur. Recordings and the signed
candidate are in `.artifacts/research/navigation-status-scroll-20261122/`.

## Soft glass gesture handle (November 19)

The independent **Soft glass gesture handle** switch in Desktop → Immersive
gesture bar changes the existing primary-display SystemUI handle. It takes
effect when SystemUI receives the preference, including on an already visible
handle. Existing installations start with the switch off.

The renderer follows the native pill rectangle and corner radius on each draw.
On a supported HyperOS device with the soft material style, a pill-sized view
in the native handle overlay draws the vendor glass paint. It uses the installed
SystemUI clock material preset, MIUI view material type, glass shader, blur radii
and a pill-local clip. The preset is read once per renderer. A translucent
finish draws after the material child in the handle's own overlay. Keeping it
in this pass preserves contrast when the vendor composites the child separately.
The system's handle polarity selects the light or dark finish. The emulator's
ranchu renderer produced a flat mask despite exposing the same methods. There the
navigation window uses a rounded `BackgroundBlurDrawable` region in SurfaceFlinger.
The same compositor path covers missing OEM methods and other system styles.
When window blur is unavailable SystemUI draws its stock handle. Disabling the switch or replacing the hook releases the
compositor region and redraws the native handle. The touch surface, animation
geometry and window owner remain under SystemUI control.

The OEM material needs a real `View` in the handle's overlay render pass.
A regular `ViewOverlay` only exposes drawable insertion in the public SDK.
KeiHome probes its container only in the privileged SystemUI process. The
verified HyperOS API 37 SystemUI declares `usesNonSdkApi=true`. If the container
is missing or inaccessible on another build, the renderer uses the existing
window blur or stock handle. Failed insertion removes the partially attached
material. This path uses the host's existing policy without changing hidden API
exemptions. The Lint suppression applies only to the container lookup.

In glass mode the Bilibili integration skips its handle-color sample. The footer
can still extend its original artwork, or match a solid tab background. The
separate general app setting still uses the HyperOS backdrop policy. These
controls address the visible gap outside the pill and can be used independently.

### MIUI Extra reference

Static inspection of the supplied MIUI Extra 2.7.3 APK (build 130804, r95,
SHA-256 `2788e2ffc1f9e039feefc5b670f35626ffdbd883b90ac1d5931f45f897fe9a58`)
found calls by name to `setMiGlass`, `setMiGlassBlurRadius` and
`setMiGlassSdfMaxSize`, with separate preferences for refraction, blur radius,
background scale and opacity. Its immersion and animation paths also reference
`WindowState.getInsetsState`, navigation-bar inset sizes and a touch-event relay
in system_server. These are distinct responsibilities. The binary establishes
implementation choices, but does not establish which choices were active in the
supplied screenshot or their cost on this device.

KeiHome uses the installed OEM preset, a bounded drawing region and the SystemUI
pill's animated rectangle. No touch relay or inset rewriting is needed for this
material. Local APK inspection results, device captures and timing samples are
under `.artifacts/research/gesture-glass-20261119/`.

### HyperOS material and motion on build 113000589

The installed Xiaomi SystemUI and its control-center plugin use different
materials for clock strokes, control-center tiles and compact/expanded volume
controls. The sliders also distinguish their tracks from progress. On the
physical phone a clock-derived preset preserved wallpaper detail inside the
390 × 17 px pill. An experimental pass-through setting erased the pill on
both solid black and solid white. The final renderer uses the vendor surface
for the background and draws the edge separately. On the dark Settings page
the center pixel was RGB 127/128/129 against 0/0/0. On the light page it was
166/167/168 against 243/243/243. The wallpaper and Bilibili samples retain
their local color differences. The Home swipe returned to the launcher and
re-entered the original Bilibili activity.

On physical OS4.0.6.0.XBLCNXM we ran two six-swipe batches per state,
with glass on, off, off and on. Window-scoped `gfxinfo` frames from
`NavigationBar0` were matched by intended vsync. Pooled median/P90 were
2.62/7.85 ms on (239 frames) and 2.65/7.88 ms off (240). Per-run P90
ranged from 6.96 to 8.46 ms. These are navigation-window samples from a
short interaction. GPU compositor cost, sustained power and device-wide
frame pacing require separate measurement.

### Clearer body and directional highlight on build 113000591

On the physical OS4.0.6.0.XBLCNXM phone, the control-center brightness
slider has a bright curved corner and a short falloff into the body.
The compact volume panel also picks up nearby color along its edge. In 589
the finish spanned the entire height of the handle, which made its center
look gray on plain surfaces. The revised finish uses a low-opacity neutral
fill with a directional highlight on an inset stroke. A second, fainter
stroke softens its inner boundary. Both fit inside the native animated pill.
The native and compositor blur paths share the finish. The opaque
system-blur-disabled fallback retains its existing colors.

Builds 589, 590 and 591 were photographed at native resolution in the same
light Settings, dark Settings and themed Bilibili home scenes. Build 590
made the body clearer, but left a relatively sharp outline on black. The
second iteration softened that line and raised the body slightly on dark
backgrounds. On the light page the central scanline median was 167 in 589
and 224 in 591 against a background of 243 (8-bit luma). In the same
120-pixel slice of Bilibili artwork, the difference between the 90th and
10th percentile luma inside the handle grew from 22.5 to 40.4. The artwork
just below it measured 48.5 in both captures. The fixed region and images
are in `.artifacts/research/gesture-glass-20261119/rim-study/`.

The two physical API 102 hot reloads completed with 10 and 12 successful
target receipts respectively. SystemUI PID 24018 and Bilibili PID 8236
survived both updates. A primary-display swipe reached the launcher and
returned to the same Bilibili window. The AVD's 589 to 591 update also
completed with 12 successes. SurfaceFlinger reported the 358 × 15 px blur
region while enabled and removed it when cross-window blur was disabled.
Both devices finished in their original night and blur modes.

### Rounded highlights during gestures on build 113000593

We recorded the stock HyperOS control-center volume slider moving in both
directions on the physical phone. Its upper shoulder brightens as the curved
surface changes shape. The middle of the long edge stays comparatively quiet.
The media level went from 86 to 141 to 78 during the recording and was
restored to 86. Both runs of the Bilibili-to-launcher gesture were recorded
on the primary display at 1220 × 2656.

Build 593 places a short reflection at each end of the pill and a diffuse
shadow below the leading shoulder. The length and angle of the reflections
follow the cap geometry. The OEM `NavigationHandle` supplies the pill rect,
radius and continuous dark intensity. When its width changes relative to
the resting width, the reflection follows the change in either direction.
Only changes in the geometry update the shader transforms. SystemUI owns
the transition timing. A short simulated press on this ROM showed 389–390
px of visible width and 17 px of height throughout. The larger stretch and
return were verified in an instrumented rendering test.

In registered captures of light Settings, the center measured 224/255 in
both 591 and 593. The median peak difference between the center and the edge
along the middle 200 px fell from 53 to 0. The Bilibili pink-stripe region
retained a 40.43 luma spread in both versions (48.51 below the handle). The
swipe reached the launcher and returned to the same Bilibili window with
SystemUI PID 24018 and Bilibili PID 8236 throughout both Java hot reloads.
The final physical update recorded ten successes and one security-center
cache process reclaimed by `umms_selfcheck`. The final AVD update recorded
ten successes. The device rendering and footer-sampling tests passed on
the AVD. Captures, motion comparison and per-frame measurements are under
`.artifacts/research/gesture-glass-20261119/rim-study/motion-study/`.

### API 102 hot reload and the two framework notifications

KeiHome uses the `io.github.libxposed` API and service 102.0.0 contracts.
`onHotReloading` releases SystemUI listeners, material views and owned drawing
state on the main thread before handing off. The successor restores hooks
and finds existing windows explicitly. Bilibili passes weak references to
the host activity and observed Compose restart scope. When the existing
tab strip still lacks an image binding after invalidation, only the confirmed
`WindowAwareComposeView` with id `tab_host` recreates its composition. This
preserves the activity, tab selection and scroll owner. The system rule list
for general apps can still be cached for the lifetime of the client process.
[API 102 lifecycle](https://libxposed.github.io/api/io/github/libxposed/api/XposedModuleInterface.html)
and [service source](https://github.com/libxposed/service).

On AVD OS4.0.31.0.XFRCNXM, the 20:31 status failure followed an early LSPosed
attempt to open an obsolete APK path. The package manager published the
replacement path after system startup. Once both paths agreed, a single AVD
cold start restored early module loading. Subsequent in-place updates retained
the system, launcher and SystemUI PIDs. On the final build the status page
reported the system loaded, launcher ready and system navigation interface ready.

For the separate 20:42 notification, the installed `lspctl` on the AVD
reported API 102, LSPosed 7944, module enabled and eight scopes for owner 0.
The Folder-Lab user (10) was disabled with zero scopes. The verbose service
log recorded 11 successes and a failed callback for Settings provider PID
17190. Android exit info recorded `umms_selfcheck` reclaiming this empty
process at 20:42:30.797. Bilibili PID 7117 finished successfully at
20:42:43.178. Deployment diagnostics now allow 25 seconds for outstanding
targets. Existing CLI commands `status`, `module show --user` and
`scope list --user` were used interactively. On the physical phone
`status` worked and this transport's root shell was denied the scope query.

For the final 588 → 589 update, the physical phone (LSPosed 7940) recorded
11 successes. The AVD recorded 11 successes and one system reclamation of
the security-center cache process. Native reload was not requested. The
Bilibili activity and process survived on both devices. On the AVD, drawing
the original artwork through the 60 px inset resumed on the already visible
Compose page. On the phone, the legacy image resumed through the 65 px inset.
The scoped deployment receipts, `final-589-real-handle.png`, before/after
captures and motion clips are in the research directory above.

The 593 → 594 AVD failure was in `com.miui.securitycenter:cache`, PID 18765.
The service requested reload at 21:55:37.193. Android reclaimed the empty
process at 21:55:38.616 with reason `umms_selfcheck`. LSPosed reported status
4 for that PID at 21:55:41.477. This worker hosts Xiaomi's game predownload
provider and services. KeiHome owns icon hooks in Security's main and `:ui`
processes. From build 595 onward, unused private Security and Settings workers
skip hook installation and retirement bookkeeping during an in-process update.
The parser retains the framework failure and only attributes a reclaim to a
process-death result with matching PID, name, system reason and timing.

During 595 → 596, Android also reclaimed cache PID 19660 after reload began.
The subsequent cache PID 32759 loaded KeiHome from the installed 596 APK
path. The result for 19660 remains a failed reload, and 32759 is a fresh
load. Empty-process lifetime and the LSPosed notification are controlled by
Android and the framework. The module leaves the system's reclamation policy
in place. Evidence is under `summary-card/` in the research directory.

## Immersive gesture bar (November 19)

The general switch asks HyperOS to sample rendered content for a selected app's
resolved launcher activity. A missing launch rule can be added to the delivered
copy. Existing positive modes, explicit colors, view selectors, nonzero sampling
policies and unrelated activities retain their own sampling choices. Apps that
opt out of the OEM controller can still have a discontinuous bottom edge.

Bilibili's separate home-tabs setting follows the active footer. A validated
image continues to the bottom using the original artwork transform. If the
visible tab host reaches the navigation inset and has a uniform bottom edge,
KeiHome can instead fill the strip with that color. A pending sample is discarded
when window focus, geometry or artwork changes. A hidden tab host, IME,
three-button navigation, split screen and secure windows cannot supply a sample.

Once the window frame commits, PixelCopy reads a small strip of the app surface
into a 33 × 3 bitmap. The artwork path samples the area behind the center of the
handle, compares dark and light contrast across the strip and retains part of its
hue. Hysteresis avoids toggling between two appearances on small luminance
changes. Static artwork is sampled once per placement. A visible solid tab bar
can refresh during redraws at most once per 650 ms.

HyperOS can composite a pale handle even when the app window requests dark
navigation icons. The SystemUI integration colors the native handle for the
verified Bilibili home window on the primary display, preserving its geometry
and view-level fade animation. An opaque tint avoids washing out the sampled hue
when the stock paint itself is translucent. Window focus loss, an IME or another foreground owner, process
death, and hot reload release the tint. A build mismatch or unknown SystemUI
method leaves the standard window appearance request in effect.

## OS4 settings hierarchy

The Desktop entry opens an overview with a refreshable system status card,
the MIUIX switch preference. Its summary identifies color matching as experimental
and explains when to fully close and reopen the selected apps. The separate
soft glass switch applies immediately. The toolbar Help
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
The app includes default-off native color compatibility and Bilibili theme
artwork under **Desktop > Gesture navigation fill**.

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
the returned Bundle and enables native automatic `mode=-1` for disabled
`mode=0` activity policies. Packages without OEM rules receive an enabled
empty activity map and use native defaults. The Bilibili home policy can
also prefer OEM pixel sampling as described below. Explicit colors,
popup/dialog choices, version gates and unknown fields are preserved.
Malformed, oversized, deeply nested or unsupported rule shapes retain the
original result.

The caller must be an app UID in the selected user, and PackageManager must
confirm that the UID owns the requested package. Own app, SystemUI and Home are
excluded. Unselected calls return before package lookup or JSON parsing.
Preferences use the existing API 102 listener. The launcher control worker
publishes the latest choice fingerprint and backend availability. It
coalesces queued notifications, suppresses duplicate receipts after successful
delivery, and republishes on user unlock when credential-protected storage is
available. API 102 retirement stops and joins the worker.
Native color compatibility runs in the existing system scope and leaves the
OEM controller map and cloud rules intact.

### Status acknowledgement during an icon refresh

On the Xiaomi 17 Pro with HyperOS `OS4.0.8.0.XBLCNXM` and Home `801030308`,
switching system appearance triggered a `RESOURCE_CHANGE` sweep across 202
packages. The report at 16:02 coincided with a 44.6-second sweep. On b0730,
the system observed the idle switch change within 0.4 seconds. Enabling it
after switching from dark to light took about 41 seconds. The receipt waited
behind the per-package sweep on the same worker. On signed Release b0732
(`114000732`), the card confirmed the new choice during both appearance
transitions. The matching receipt was observed within 0.4 seconds of the
tap, over 41 seconds before the sweep finished in each direction.

For regression verification, wait for the `Icon probe` line with
`cause=RESOURCE_CHANGE` after each system appearance change, then toggle
**Match bottom bar color** while the sweep is active. Check the matching
choice fingerprint before the same generation's `Icon refresh` completion
line and confirm that the settings card returns to **System interface ready**
with the new switch state. Exercise both appearance directions. The Bilibili
artwork pill still follows the separate home-window drawing receipt.

The baseline and fixed-build timing, UI snapshots, and APK identity are in
`.artifacts/research/navigation-backdrop-ack-20261123/`.
The [shared control acceptance](status-dashboard.md#receipts-during-an-appearance-change)
covers the later b0736 build, including both directions of this switch test.

### Bilibili theme artwork

Debug and Release declare `tv.danmaku.bili` as an optional LSPosed scope.
The seven core targets still determine activation and the request-all action.
The artwork switch requires a confirmed grant and a current system backend.
It adds Bilibili to the effective selection even when general color
compatibility is off. While bound, the app row explains its automatic selection.
Turning the artwork switch off restores the independent manual selection.
Changes to the rules take effect after a complete app restart.

The bridge runs in the Bilibili main process. It uses the app's own loaded
static artwork, measured width and placement, and current gesture inset.
DexKit resolves the home Activity through the bottom-tab intent protocol. It
resolves the Compose footer through its tab action and its image request,
layout caller and image ABI. Both resolutions require a unique match. The
system sampling rule uses the installed launcher activity or its alias target.
The View path resolves `tab_background` from the installed resources and uses
the active drawable, its scale matrix and ancestor opacity. Both align the
original image bottom with the window bottom and
require that the projected image fit within the allowed background region.
They change drawing only, leaving labels, touch targets and the SystemUI
gesture handle to their original owners.

The bridge clears detached, replaced or ineligible artwork and releases its
window state on destruction or reload. Without a usable image, the native
color policy supplies the fallback. IME, tappable navigation, side insets,
secure windows and multi-window surfaces retain their original rendering.
Cold startup can show the native background before the theme image loads.

The status card reads the installed version from PackageManager. Its artwork
pill reflects the most recent home-window outcome from the current installation.
A drawing receipt is sent after the original artwork is painted into the gesture
inset. The receipt is bound to the Bilibili UID, package, install timestamp,
KeiHome build and live process, and is dropped when that process exits.

Affected apps uses the same published icon source, component mapping and bounded
preview cache as the Applications browser. It loads visible rows on demand and
uses the published mask, current night mode and active Monet colors. The app
inventory is fetched when the list is opened, and reused across filter, sort
and search changes.

#### Optional scope verification

On KeiHome Debug `112000731` with Bilibili `9190400`, the Phone AVD used the
Compose path at `1120 × 2436` with a 60 px inset. The physical Xiaomi phone used
the View path at `1220 × 2656` with a 65 px inset. The 秋星曜野 and 孤独摇滚
artwork needed −8.54 px and +64.41 px of vertical movement respectively. Mean
absolute error against the projected artwork in sampled opaque inset pixels was
0.248 and 0.308 RGB levels. Both devices also exercised the fallback with the
artwork switch off.

Motion captures on the same renderer covered tab changes, settings return and
task return. The gesture region matched in all 438 AVD and 449 physical samples
where the theme background was already visible above the inset. These tests
cover two portrait window sizes. See
`.artifacts/research/bilibili-artwork-scope-20261118/` for captures, authorization
checks and deployment receipts.

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
asset removed. That run validated the cooperative fixture. The follow-up
below uses Bilibili's active themes in its real home window.

## Bilibili theme artwork continuity

Bilibili 9.19.0 (9190400) used the same APK on the physical Xiaomi phone
and HyperOS Phone AVD. Its `TabHost` loads `Garb.tailBgPath` into a
width-scaled `ImageView` in the 52 dp footer layout. The home window handles
the navigation inset separately through an integer color and a handle
appearance flag. HyperOS NBI's automatic fill also produces a color. On the
physical phone's active themed homepage, row 2590 contained 309 colors.
At row 2591, the top edge of the 65 px navigation inset, it changed to a
solid `#E7E7CF` strip.

The original 1242 x 249 artwork has enough height after width scaling.
On the 1120 px wide AVD it covers about 225 px, while the 52 dp footer plus
60 px inset needs 216 px. The cooperative fixture extended the image clip
to the bottom of the window on both devices. It kept the artwork transform
and tab hit bounds in place and the gesture handle visible. Tab selection
and dialog suspension passed on both devices. Keyboard suspension and
restoration passed on the AVD. The old and extended artwork above the
navigation area differed by at most one RGB level per channel.

The AVD's Bilibili instance continued to use its default dark footer when
given the temporary theme fixture. The APK's garb initialization switches
to a plain garb in its night theme. The original garb files and metadata
were restored. The physical device supplied the actual themed-app evidence.
After the separate fixture ran, the original Bilibili activity, process,
theme file hashes, and native rules matched their starting values. The
fixture was removed from both devices. Evidence is under
`.artifacts/research/bilibili-footer-continuity-20261118/`.

An app integration would need to resolve the active footer drawable and
its transform inside the home window, draw the remaining artwork through
the bottom inset, and leave the tab layout and gesture hit area intact.
It would also need to re-evaluate eligibility when the active scene,
theme or insets change. A scene without sufficient authored artwork can
keep its existing color fill. The current NBI rule delivery cannot carry
that drawable. The fixture proves the rendering geometry on HyperOS.
An earlier research build integrated this approach in the live Bilibili
home window. Build it with `:app:assembleDebug` and
`-Pkeihome.research.bilibiliFooter=true`. This explicitly adds Bilibili to
that APK's static scope. The default Debug and Release scopes keep the
original seven targets. The existing navigation-fill switch and app selection
gate the prototype after the framework grant.

The same APK uses different footer renderers in practice. The AVD renders a
Compose image. The resolver finds the home footer's image call from its
Compose trace anchor and validates the call shape. The prototype observes
the image loader's successful static bitmap and reads its measured placement.
The physical device uses a legacy `ImageView`. The prototype reads its
drawable, image matrix, visible bounds, and parent opacity. Both paths
extend just the drawing below the existing tab strip. They leave the original
tab views, layout, touch targets, and SystemUI handle in place. A missing,
short, or ineligible image leaves the regular navigation fill in control.

### Live home window acceptance

Debug research b0716 ran on the HyperOS Phone AVD with Home 9261 and on
the physical Xiaomi with Home 9259. The AVD's blue 秋星曜野 theme filled
the 60 px navigation inset. The physical phone's 镜音铃·连 theme filled
the 65 px inset. A parent of the legacy footer draws at 0.98 opacity.
Applying the same accumulated opacity to the continuation preserved
the image across the boundary. Rejecting all non-opaque parents had
left this theme on the color fallback.

Both devices preserved their active themes and exercised the Home,
Following, Shopping and Profile tabs. A 560 x 1218 AVD recording sampled
529 frames across the tab transitions at up to 24 fps. Both ends of the
navigation inset retained the blue artwork throughout the captured frames.
A separate AVD run with b0708 exercised the ordinary dark tab background,
which continued to use the native `#17181A` fill, and restored the original
theme before the final b0716 run. The critical system and Home processes
kept their PIDs. AVD deployment observed seven successful Java reloads
and one system-reclaimed Security Center process.

The default Debug and Release APKs were inspected and both retained
the original seven scope entries. The research APK adds only Bilibili.
Those builds preceded the optional scope and dedicated switch described above.
Run evidence is under
`.artifacts/research/bilibili-footer-integration-20261118/`.

### Scope-free continuation

A reusable renderer needs an eligible owning window, the active background
artwork with its transform and opacity, and the current navigation inset.
The two live renderers verify that these inputs can drive the same
continuation even when their source views differ. Bilibili's hooks are
research adapters for obtaining those inputs. The intended production
path keeps the original scope list and needs app cooperation or a verified
OEM window-side source for the artwork. NBI delivers a color, and screen
sampling sees only already displayed pixels. Until the source is available
for an eligible window, retain the existing native fill. Timed handle
hiding remains separate.

#### Existing-scope data on HyperOS

DexSQL inspection used the framework, services, MIUI framework, MIUI
services, and SystemUI files matching the installed Phone AVD
`OS4.0.24.0.XFRCNXM` by SHA-256. The inspected paths expose different inputs:

- WindowManager's `DisplayPolicy` selects an owning window and forwards bar
  appearance and inset information. Window and SurfaceFlinger dumps show a
  composed app surface for the home activity and a separate one for settings.
  There is no independently composed footer image layer in these windows.
- MIUI's NBI service registers a pixel sampling listener. The app-side
  `NavigationBarImmersiveController` turns the returned color samples into
  a navigation bar color. The SystemUI bar attributes carry appearance flags
  and regions. None of these paths carries the footer image or its
  image-local crop and scale.

The available surface transform applies to the entire rendered window.
It can place the app buffer during a task animation. The scale and crop
used to draw the source image inside that buffer are app-side data.
This investigation found no source for exact continuation in the existing
scopes on this build. Default builds keep the seven existing scope entries
and use the existing color path. The experimental adapter remains available
to validate exact artwork and window ownership.

#### OEM sampling without Bilibili scope

The physical Xiaomi on `OS4.0.6.0.XBLCNXM` and the Phone AVD framework
both have a native path for `sf_sampling_mode=1`.
`NavigationBarImmersiveController.handleSfCloudRule` selects that path and
`doUpdateNavigationBarTarget` registers OEM sampling instead of picking the
detected tab view as the color source.

The minimal compatibility policy runs through the existing NBI rule delivery.
It applies only to the selected Bilibili package's exact home activity
when its OEM rule explicitly disables both immersion and pixel sampling.
It opts that home into automatic color with pixel sampling. Other activities
retain their sampling rule, including Settings. No Bilibili process hook
or independent sampler is required. The final output is an OEM color fill.

On the physical phone with Bilibili 9.19.0 and the user's replacement theme,
seven-scope Debug b0720 served as the ordinary automatic-color control.
Seven-scope b0721 was retested after the user removed the old Bilibili grant
and Bilibili started in a fresh process. Its OEM log reported
`sf_sampling_mode ... MainActivityV2 is 1`; the OEM table retained its
original values. At the 65 px inset boundary, the difference between the
dominant colors of adjacent rows dropped from 2.67 to 0.00 RGB levels
per channel. The Home, Following, Shopping and Profile captures all had
matching dominant colors at that seam. During settings and Home task
transitions, none of the 398 captured frames with warm footer pixels
above the sampled seam had a dark strip below it. This sample covers
the new warm theme on the physical phone. Exact artwork continuation
for themes with distinct edge patterns still requires the artwork source.
Evidence is under `.artifacts/research/navigation-scope-free-20261118/`.

#### Complete artwork and window geometry

The same `秋星曜野` theme on Bilibili 9.19.0 provided a byte-identical
1242-by-249 footer PNG on both devices. A root-assisted, read-only
inspection identified the file selected by the app's theme descriptor.
This was diagnostic access. Ordinary KeiHome builds do not use it to
read the app's private theme cache.

| Renderer | Image top | Image bottom at observed scale | Gesture inset |
| --- | ---: | ---: | ---: |
| Physical Xiaomi, legacy `ImageView` | 2347 | 2591.59 | 2591–2656 |
| Phone AVD, Compose | 2220 | 2444.54 | 2376–2436 |

On the physical phone the source already ends at the tab strip boundary,
within one display pixel. The artwork would have to be repositioned to
include the gesture area. On the AVD the previous continuation exposes
60 px of genuine artwork at the original crop and scale. The source
still extends about 8.5 px past the bottom of the display. The recorded AVD
research frame matches the projected original in the inset to 0.29 RGB
levels per channel in the sampled regions outside the handle.

`NavLabActivity` now also tests a bottom-anchored background. It uses the
full width-scaled image and places its last row at the bottom of its own
window. The interactive tab strip keeps its independent inset and bounds.
On the physical phone all three tab bounds matched the color and
fixed-origin controls, and a real tap selected Feed. In the fixture,
fixed-origin continuation reaches about 238 of the 249 source rows by
the screen edge. Bottom anchoring reaches all 249, keeping the original
aspect ratio. This test demonstrates the geometry with the real asset in
a cooperative window.

The inspected OEM view rule contains a view name and ID, for color
selection. It provides no way to substitute the background, position the
image, or re-render behind the tab controls. The app's footer and controls
are already composited when system scopes receive the window buffer.
Full-art rendering needs an app or OEM-provided background source and a
way to draw it behind those controls. Until that contract is available,
the production path retains OEM color fill. This run kept Bilibili's
scope disabled on the physical phone. Its fixture and geometry reports
are under `.artifacts/research/bilibili-source-geometry-20261118/`.

#### Theme package metadata and bottom-aligned artwork

The three supplied theme descriptions point to public Garb ZIP packages via
`package_url`. Each package contains one static `tail_bg.png` at 1242 x 249,
separate tab icons, and a personal-page video. The descriptions carry tab
colors and asset playback settings. They contain no platform-specific footer
path, image alignment, or navigation inset geometry. The Android 9.19.0
APK resolves the package's `tail_bg` to `Garb.tailBgPath`, which feeds both
the legacy and Compose renderers. The Autumn Stars PNG from the package is
byte-identical to both previously verified installed copies. This confirms
the source asset on Android. It does not establish how the iOS client lays
out or selects that asset.

At the measured physical phone width, the PNG scales to 244.59 px high.
Bottom alignment moves the image down by 64.41 px and leaves the same
amount of space above it within the measured footer area. Draw that space
with the owning surface behind the image, accounting for partial PNG alpha
on the Blue Archive and Mountains and Seas themes. Stretching the image
to cover the entire 309 px footer would enlarge it vertically by 26%.
On the measured Phone AVD the same alignment moves the image up by 8.54 px.
Resolve this placement from the live width and bottom edge on each device.

The offline comparison shows original-color fill, edge-row repetition,
and the width-fit, bottom-aligned image. Repeating the row elongates
artwork details on all three themes. Use bottom alignment when the
current app window can supply and draw a background independently of its
tab controls. An explicit asset import can supply PNG bytes but cannot
establish current app theme identity or move pixels already drawn with
the controls. Keep native color fill for unavailable or ineligible
backgrounds. The comparison is under
`.artifacts/research/bilibili-theme-json-20261118/`.

The next physical theme, Bocchi the Rock (`1756725692001`, asset revision
`1756802826`), uses a partially transparent 1242 x 249 PNG. Its package
and active device asset have the same SHA-256. On the Xiaomi phone the
original image starts at y=2347, the gesture area starts at y=2591, and
the display ends at y=2656. At the installed width the image ends at
y=2591.59, leaving less than one pixel of artwork for simple continuation.
The live screenshot shows a solid pink band below the original artwork.

The cooperative fixture now permits bottom alignment when the image covers
the gesture inset and only part of the tab area. Tests with the Bocchi
PNG used a 244 px initial crop on the phone and 156 px on the Phone AVD,
with 65 px and 60 px gesture insets respectively. The full image moves
down 64.41 px on the phone and up 8.54 px relative to the AVD fixture crop.
Compared with a width-fit projection, the mean RGB channel error in the
gesture area outside the handle was 2.86 on the phone and 0.11 on the AVD.
The test controls kept their bounds, responded to a tab tap, and suspended
and resumed artwork across a dialog. The fixture uses its own underlay for
transparent pixels. Its result verifies geometry and input behavior in a
cooperative window.

On the physical HyperOS build `OS4.0.6.0.XBLCNXM`, the SHA-verified native
NBI service exposes activity rules and pixel-color listener registration.
DexSQL traced the registration to `CompositionSamplingListener` and the
client result to an integer color. The inspected rule and sampling paths
provide no independent footer bitmap or transform. The live Bilibili
home footer and tab controls share a composited app surface. A new system
layer confined to the gesture area cannot move the artwork above it, and
the tested AVD geometry cannot be reused for that phone layout. Both
devices returned to the prior activity after the fixture was removed.
Screenshots, numerical checks, and process and settings receipts are under
`.artifacts/research/bilibili-bocchi-20261118/`.

#### Independent background drawing in the live home window

The scoped Debug research build uses the same bottom edge on both sides
of the gesture inset. Compose resolves the active footer image call,
observes its loaded bitmap and layout coordinates, and replaces only the
image's draw operation. A draw-phase state retains the original draw
operation whenever the replacement is unavailable. The legacy path keeps
the `ImageView` scale and applies a translation to a copy of its matrix.
It restores the matrix only while the view still has the applied value,
so an app update to the matrix remains authoritative. The gesture region
draws the same source with the matching transform. The app still owns the
labels, icons, layout, hit testing, and underlying color for transparent
pixels.

Debug `112000726` was used on Phone AVD `OS4.0.24.0.XFRCNXM` with Autumn
Stars and on Xiaomi `OS4.0.6.0.XBLCNXM` with Bocchi the Rock. Both ran
Bilibili `9.19.0`. The Compose fixture geometry moved the art up 8.54 px.
The live legacy view moved it down 64.41 px, retaining its original scale.
Home, Following, Profile, Settings, return from Settings, and task
leave/return were exercised. Tab bounds stayed fixed during the test.
Settled gesture-region pixels outside the handle and over opaque source
pixels matched the projected artwork to a mean RGB channel error of
0.25 on the AVD and 0.31 to 0.55 on Xiaomi. The Settings activity used
its own background. At 12 samples per second, all 428 AVD and 455 phone
warm-transition samples that matched the theme above the seam also matched
it in the gesture region. A separate AVD cold launch still showed a
dark gesture region before the artwork was available.

This live result requires the temporary app-side research scope. The
original scopes were restored, including removing Bilibili on Xiaomi
and cold-launching it back into native color fill. The AVD retained its
pre-existing research scope. The default build continues to omit the
Bilibili scope. The independent background source is still unavailable
through the inspected system-only NBI interface. Evidence is under
`.artifacts/research/bilibili-isolated-background-20261118/`.

#### Window-owned layer versus an opaque app buffer

DexSQL traced the ColorOS `PMX110_17.0.0.114(CN01)` pick-and-fill path
from `enableSfPickFill` to `createOrUpdateDecorNavBarBgLayer`. The latter
uses `createDecorNavLayerEmptyBuffer` and configures a child layer, crop,
position, visibility and effect metadata. The inspected calls do not
pass a footer `Drawable` or a layout transform from the app. HyperOS
`OS4.0.24.0.XFRCNXM` framework/service JARs were independently matched to
the running AVD by SHA-256. The NBI paths inspected there register a
pixel-color listener. `DecorView`'s legacy navigation background path
operates on its own view. Neither inspected path publishes the
independent footer artwork or the bounds needed to lay it out.

The NavLab fixture now compares the previously tested in-window
composition, an independent `SurfaceView` under the tab controls, and an
opaque-window control. Both independent-layer modes retain the same
SurfaceFlinger layer IDs and draw the same bottom-aligned source buffer.
The exposed mode lets the window reveal the surface beneath its controls.
The opaque control retains the normal cropped footer and fill and covers
the separate surface. The source uses a fixture-owned underlay and
RGBA8888 buffers. `SurfaceView` owns the parent-window compositing
relationship. An earlier generic `AttachedSurfaceControl` child with
local Z=-1 did not preserve that relationship against the opaque control.

Phone AVD (1120 x 2436, 60 px gesture inset) and Xiaomi (1220 x 2656,
65 px gesture inset) both passed. The separate-layer and in-window
renderings differed by 0.20 RGB levels per channel on average in the
gesture region on each device. In the opaque control the sampled outer
edge exactly matched the original color fill on both devices. Tab bounds
and clicks passed, the layer suspended for the dialog and IME, and it
recomputed on rotation and returned to the original portrait layout.
Both fixtures and their surfaces were removed after verification.

The positive result establishes a supported independent drawing path
when the owner supplies the artwork and exposes the background. The
negative control establishes why hanging a texture beneath an opaque
third-party window does not replace the pixels that the app has already
drawn. An above-window layer would also cover foreground pixels in the
same area. A general renderer can use an app-provided background surface
or a corresponding OEM contract when available. The system-only policy
can keep using native fill for opaque windows with no such contract.
Neither the third-party package nor the production NBI hook was changed
for this probe. Evidence is under
`.artifacts/research/bilibili-window-layer-20261118/`.

#### Window transition lifetime

Research Debug b0719 keeps a validated continuation in the home decor over
focus loss and pause. It stops the pre-draw observer at activity stop and
revalidates on the first draw after start. The retained drawing belongs to
that decor, so it moves with the home surface through the animation.
Settings draws its own color. Disabling the option, destroying the decor,
or retiring the hook removes the continuation.

On the Phone AVD, the left-side sample found 19 frames with blue theme
artwork over a dark gap during settings entry on b0716, and zero on b0719.
Video inspection also covered the return from settings, the home gesture,
and warm launches from the actual desktop icon. The cold-start recording
still includes the OEM black starting window and Bilibili's splash before
the home footer is available. The home continuation appears after the
bitmap and placement become valid, with normal fallback before that point.
Evidence is under `.artifacts/research/navigation-window-source-20261118/`.

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
| Cooperative app-window renderer | Texture continuity and a separate surface beneath app controls | Requires the window to expose its own background region |
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

### Keyboard spacing with fullscreen keyboard optimization disabled

On physical pandora OS4.0.6.0.XBLCNXM, the Settings search field showed WeType
4.0.1 with `enable_miui_ime_bottom_view=0`. The IME hierarchy reported
`android:id/miui_bottom_area` as `GONE` and 0 × 0. With gesture glass on,
off, and restored, the 1220 × 2656 display reported the same geometry each time.

| Measurement | Pixels |
| --- | ---: |
| IME top | 1643 |
| Navigation bar top | 2591 |
| WeType root height | 948 |
| WeType keyboard panel height | 868 |
| Space inside WeType below its panel | 80 |
| System navigation inset | 65 |

The two bottom regions account for the observed 145 px below the keyboard
panel. Toggling the material changed none of these dimensions. This measurement
covers WeType in the reported Settings scene. The before/off/restored window
and view measurements are in
`.artifacts/research/process-reload-20261119/ime/comparison.json`.

### Soft-glass gesture handle on pandora

The b0603 finish spreads reflected light along the upper shoulder of the capsule.
The return light is weaker and follows the moving lower shoulder. The small
alpha in the center and 4 dp backdrop blur let Bilibili's footer continue through
the handle. The HyperOS material child and the compositor drawable use the same
finish. With window blur disabled SystemUI paints its standard handle. The setting
card draws the finish over a curved color band so transmission is visible even
on a plain settings page. The preview simulates the transmitted color locally.

Compared b0599 and b0603 in light and dark Settings and Bilibili on pandora,
with the OEM material selector on soft glass. The OS selector's example and
the launcher dock, search capsule and folder captures were visual references.
During the recorded swipe to Home and return to Bilibili, SystemUI PID 24018
and Bilibili PID 18779 stayed alive. The light Settings capture still shows
low contrast on white. Compositor blur on/off/on was checked on Phone AVD.
The native-scale comparison, card screenshot and `b603-swipe.mp4` are under
`.artifacts/research/gesture-reload-acceptance-20261119/`.

### Capsule optics on OS4.0.6.0.XBLCNXM

MIUI Extra r95 sets the gesture material's SDF bounds to the actual view size.
HyperLight 1.2.3 maps the shoulder, thickness, reflection and refractive index to
separate OEM fields. Its APK also contains a shader with a background input,
whose use for the navigation handle was not established. KeiHome scales its own
profile to the handle radius and updates the SDF when its bounds change. A
single analytic draw lights the shoulders. The settings preview refracts its own
background, while live app content stays in the compositor.

On the physical 390 × 17 px handle, OEM view blur mode 1 produced a black fill
on the Bilibili detail fixture. Mode 3 retained the image under the pill. In
b0606 the central stripe's P90–P10 luminance span was 40.50 against 48.51 in
the adjacent image, compared with 36.13 in b0603 and zero in the mode 1 trial.
This measures visible texture. Its displacement across app windows and the
performance cost were not established. On flat white the capsule remains subtle.

The switch to a framework View for retirement safety preserved the b0606
light-page and Bilibili measurements in b0625. The b0627 physical recording
shows the Bilibili-to-Home transition and return in the same processes. Home
was triggered with the Home key after an injected swipe stayed in Bilibili.
This recording verifies transition rendering, not completion by touch alone.

### Liquid glass comparison on November 20

On Phone AVD (OS4.0.31.0.XFRCNXM, LSPosed 7944) KeiHome's glass was switched
off and each reference module was enabled separately in SystemUI. MIUI Extra
r95 exposes separate controls for optical thickness, refraction, tint and blur.
Its gesture runtime sets the SDF to the bounds of the actual handle. HyperLight
1.2.3 has separate controls for gesture dimensions and spring-back. Its liquid
material shader derives a surface normal, uses reflected and refracted ray
directions, and samples a background input. Its non-launcher editor showed
refraction height 20, strength 48 and depth 1. Those values belong to that
renderer and have no direct mapping to the OEM handle. Enabling the liquid
renderer registered its material guard on the AVD. SystemUI subsequently had
an input-dispatch ANR while the notification shade held focus. The full shader
was not verified on a live HyperLight gesture handle. Both reference packages
were removed and KeiHome's glass setting was restored after comparison.

KeiHome b0628 places the peak of each reflection inside the curved shoulder,
using the reflected view vector to orient the light through the rounded ends.
The preview computes its sample offset using the refracted ray and capsule
depth. The live renderer keeps the existing compositor path and one finish
pass, with no additional capture, animation clock or background sample. A GPU
pixel assertion checks that the highlight falls off at the outer edge.

At physical size on OS4.0.6.0.XBLCNXM, the Settings edge's peak luminance
contrast against a flat page fell from 71 to 54 on white, and from 142 to 92
on dark gray (b0627 → b0628, 0–255 scale). On the Bilibili detail fixture the
texture span across the same interior stripe was 40.50 → 41.93. SystemUI
31827 and Bilibili 18779 stayed alive through the installation and recorded
Home transition. The injected swipe stayed in Bilibili, so the transition
recording uses the Home key. These measurements cover optical appearance.

### Plain handle with soft glass off, b0629

On the physical Bilibili artwork fixture the previous tint reduced the whole
sample to one opaque color. The app now chooses neutral black or white and the
lowest alpha that gives a 3:1 luminance contrast on at least three quarters of
the sampled pixels, subject to the native-style alpha floor. If the artwork is
too varied to meet that target, it covers as many samples as that ink can make
legible. Hysteresis stabilizes the polarity across nearby shades. The pixels
under each part of the handle therefore retain their local hue. The settings
preview uses translucent plain paint with the glass switch off.

SystemUI checks the active owner and preferences before accepting a tint. The
glass switch or removal of the artwork rule clears the old session. The app
invalidates its footer when switching back to plain to request a fresh sample.
Without a custom tint the OEM draws the handle and owns its hit area, animations
and insets. Glass support is now resolved when the switch is enabled.

On OS4.0.6.0.XBLCNXM, in the same Bilibili screenshot region, the interior
P90–P10 luminance span rose from 0 to 24.76, against 48.51 beside the handle.
An off/on/off cycle restored the same 24.76 span (41.93 with glass enabled),
with SystemUI PID 31827 and Bilibili PID 18779 throughout. In Settings the
plain handle center measured RGB 153 on white and 146 on the dark surface in
both builds. With `enable_miui_ime_bottom_view=0`, WeType still started at
1643 px, the navigation inset at 2591 px, and `miui_bottom_area` was hidden
with zero height. Phone AVD settings/material instrumentation passed 18 tests.

## Plain handle contrast during theme and keyboard changes

On pandora OS4.0.6.0.XBLCNXM, the native region sampler requests a contrast
change through `NavigationBar$9.onRegionDarknessChanged`. The navigation bar
then supplies a device-dependent duration to `LightBarTransitionsController`.
With glass off, build 630 caps that duration at 120 ms for a selected app on
the default display in gesture mode. It checks the window owner, foreground
activity and profile at a tint decision. The keyboard, lock screen and dedicated
Bilibili artwork path keep their own timing. SystemUI supplies the sample,
interpolator, cancellation on reversal, and final paint. There is no polling or
additional work per rendered frame for this transition.

Physical recordings used Xiaomi Health (361000), Via (20261113) and WeType with
the keyboard bottom view disabled. Both glass and dedicated Bilibili artwork
were off. The interval starts at the first frame with a new bottom-strip luma
and ends when the handle-to-strip difference stays within eight luma levels of
its native endpoint for 120 ms. It includes OEM detection time. Values below
are individual observed intervals in milliseconds.

| Surface | Build 629 | Build 630, selected | Build 630, removed from selection |
| --- | ---: | ---: | ---: |
| Health, dark to light | 570, 589 | 298, 364 | — |
| Health, light to dark | 688, 462 | 373, 339 | — |
| Via, dark to light | 605 | 381 | 631 |
| Via, light to dark | 481 | 407 | 574 |

Via's address field exercised keyboard show/hide in both themes, including a
second quick open/close in light mode. During these interactions the luma
difference kept its sign. Its largest single-frame change was 28 before and 27
after on the light page, and 16 on the dark page in both builds. The keyboard
surface itself still changes shade as it slides. These captures cover this IME
and these app versions. They do not measure sustained power use.

In a 15-second Perfetto capture, the SystemUI main thread made six `getTasks`
Binder calls during four Health theme switches (0.322–0.709 ms each, 3.069 ms
total). The four seconds of idle time before the switches and the four seconds
after settling contained zero such calls. The counts include all SystemUI
callers. The signed in-place upgrade retained SystemUI PID 31827. LSPosed
reported seven successful targets and zero observed failures. Traces, recordings,
frame measurements, preferences and upgrade receipts are in the local evidence
folder `.artifacts/research/ordinary-handle-motion/`.

### Reversing a transition inside the same app (build 631)

On the same pandora device, Termux 118 opens a white settings activity from
its black terminal. Both can report `LIGHT_NAVIGATION_BARS` even when their
actual bottom surfaces are opposite. Sampling the displayed region remains
the authority for the final handle color.

Four build 630 Health theme changes were captured with AIDL tracing and the
screen recorder running together. Frame times came from the recording's
Winscope v2 elapsed-clock track. From the first frame of the new background,
the last observed SystemUI `onSampleCollected` call preceding its next
`getTasks` was at 95–142 ms. The foreground check at the tint decision arrived
at 122–193 ms and the native endpoint was visible by 298–356 ms. The checks
themselves took under 0.9 ms. These are correlated process-level events. The
trace does not identify the sampled rectangle or give a precise queueing
breakdown. The remaining 151–184 ms after the decision includes animation
and presentation. The implementation retains the 120 ms native duration cap.

Build 631 adjusts the paint only on the verified ordinary handle. For a
translucent achromatic paint, the additional opacity and gray preserve the
composited endpoints on matching black and white surfaces. On the opposite
pure background they give about 48 luma levels of separation while the next
native sample is pending. The calculation runs in the existing draw hook and
uses integer arithmetic. There is no new sampling, frame scheduling or IPC.
A weak view reference clears on ownership or preference changes. Visible IME
insets exclude this paint during keyboard handoff.

For the physical interaction, open the Termux drawer, tap its settings gear
and press Back at multiple delays. The Termux soft-keyboard switch was
temporarily disabled for the timing recordings, then restored and checked by
opening and dismissing WeType. With Back issued at 307 ms in build 630, the
black handle and black page coincided, spending 108 ms less than 24 luma
levels apart on return. With Back at 287 ms in build 631, the closest distance
on either the white or black surface was 47. None of the six recorded returns
in that run spent a frame below 24 on the black surface. At the shortest
delays the white bottom surface did not fully arrive before reversal. No claim
is made for arbitrary mid-gray surfaces.

In a separate four-switch Health run, time to a 24-level difference went from
173–306 ms to 0, 0, 74 and 124 ms. Time to the final appearance still ranged
from 116 to 364 ms on build 631. An initial frame can still blend into its
background when ownership changes or the handle has yet to be verified.
The local evidence folder `.artifacts/research/plain-page-return/` contains
synchronized recordings, traces, samples and the upgrade receipt. The release
upgrade kept SystemUI PID 31827 and reported seven successful target reloads.
