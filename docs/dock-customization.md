# Dock geometry customization

## Choices and preserved behavior

The existing Recents and Rounded choices remain, with Recents as the default.
Default geometry remains 18 dp of requested vertical padding and zero vertical
translation. All choices retain the native glass material and unlock/drag/edit
animation path.

Vertical padding uses the native painted icon bounds for the effective
launcher label mode. When either edge reaches the existing paint allowance,
both edges use the same reduced padding. The height control still requests
padding, and the vertical position control still translates the whole Dock.

Left and right padding is a symmetric adjustment from the current plate
edges, from -16 to +24 dp per side. The 0 dp setting leaves the existing
plate unchanged. It moves only the decoration; the native cells, icons, hit
targets and edit/drag geometry are untouched. A positive adjustment can use
the existing decorative edge reserve but cannot paint outside the native Dock
content. A negative adjustment cannot move inside either outer icon's
painted horizontal extent. A full-width plate may have no room to grow.
This applies equally to the supported corner styles, with the radius bounded
again after adjusting width.

Additional choices:

- **Pad** uses the retained Pad launcher's relation: `16 / 60 * iconExtent +
  (plateHeight - iconExtent) / 2`, bounded to the actual plate.
- **Screen-concentric reference** adjusts plate width and radius from the
  system-reported bottom screen radius. Vertical placement remains an explicit
  user adjustment. Five icons retain the original full-width allowance and
  edge clearance before applying horizontal padding.
- **Custom** accepts a 0-64 dp radius, clamped to half the plate's short side.

Height is controlled by 0-32 dp of padding above/below the actual painted icon
extent, rather than scaling icons or assigning an absolute device height.
Native container and decorative-overflow limits still apply.

Vertical translation is -48 to +96 dp relative to native layout. Positive values
move down. The actual native parent bottom boundary limits translation.
Navigation, device and orientation insets remain upstream in the original
layout; there is no screen-pixel coordinate or device/DPI allowlist. Reset
restores the original default choice and geometry.

## Native placement and the concentric limit

The authenticated phone `_getTotalHotSeatsMarginBottom` result reaches native
`Positioned.bottom` unchanged. The positioned subtree contains both background
and icons. A call-free return leaf adjusts its raw D0 result, so painting, native
hit testing and drag positioning share the same container. Zero translation
leaves the native result unchanged.

Automatic placement based on previous child-background geometry was rejected.
The native parent commits its bottom margin before the child gets constraints,
and the child is inside horizontal Padding. Its local width/insets cannot be
treated as screen coordinates. A child snapshot also creates no reactive
dependency for count, inset or rotation changes. No such snapshot or managed
object is retained.

The screen-concentric choice is deliberately labelled a **reference**. It is not
an automatic exact hardware-corner alignment guarantee. Users can lower the
whole Dock and adjust height/custom radius; large five-icon layouts and native
clipping can constrain the result. Exact automatic alignment needs a separately
authenticated global-layout relationship, not a fixed screen offset.

Pad source research uses retained `pad-5490` AOT. Its native corner reference
changes with smart-hub policy; its bottom margin follows grid configuration.
The new Pad choice reproduces the icon/spacing relation, not every Pad window,
taskbar or gesture policy. This is not live-Pad acceptance of the new controls.
Specifically, the source has a normal 16 dp reference and a smart-hub 20 dp
reference, normalized to a 60 dp icon. The new Pad choice uses the normal branch.

## Wire format and reload

API 102 remote preferences remain the configuration owner. The entire choice is
saved/retried as one snapshot. Slider drafts stay local until dragging ends.

Command 8 keeps its 24-byte header and legacy count values 0/1. Extended geometry
uses one validated unsigned word in that field:

| Bits | Meaning |
| --- | --- |
| 0-2 | Corner style, 0-4 |
| 3-8 | Padding: zero means legacy 18 dp; otherwise value minus one, 0-32 |
| 9-15 | Custom radius, 0-64; zero outside Custom |
| 16-23 | Signed relative translation, -48 to +96 dp |
| 24-29 | Signed horizontal adjustment per side, -16 to +24 dp |
| 30-31 | Reserved, must be zero |

Native Dock layout capability is bit 32; existing Dock capability bit 8 is still
required. A nonzero horizontal adjustment also requires capability bit 128.
Older resident payloads remain compatible with a zero delta, and saved nonzero
values are retained until a supporting payload is loaded or the user resets
geometry. Lease identities/status ACKs compare full options. Unchanged renewals
stay quiet; one committed change uses the existing scoped refresh.

Horizontal padding is covered by the host geometry sweep, the linked ARM64 Dock
renderer cases, and preference, protocol and status tests. Device results are
recorded separately below.

The bottom-margin role resolves structurally from mapped AOT instructions. Its
unique native return must authenticate before any patch is published. Five
retained phone sources are checked without version/hash/RVA admission tables.
The new leaf allocates nothing, calls no Dart/C helper, creates no VM frame and
preserves all registers except the result and native scratch state.

## Verification

Evidence: `.artifacts/research/dock-customization-20261107/`. Source admission,
linked ARM64 execution, wire/lease tests and device rendering are separate
checks. Final `acceptance.json` records actual tested settings, screenshots,
APK identity and process continuity.

### Phone AVD acceptance, 2026-11-07

The installed KeiOS Release is `1.4.0+g4ef7363.b0213` / `104000213`.
Native activation was explicit development work. After that activation, all
tested geometry changes and reset kept Launcher PID 20178, system_server 8825
and SystemUI 2694 unchanged. The preceding Java-only update also passed the
API 102 process-identity checks.

The actual UI/device flow covered Pad geometry, padding 18 to 6, relative
translation, screen-concentric reference, five icons, hidden gesture handle,
Custom radius 24 to 0, and reset. The material remained native glass. Tapping
the moved MT Manager icon launched `bin.mt.plus/.MainLightIcon`. A matched
four-icon screenshot pair confirmed downward movement and unchanged opaque
icon extent. Pixel displacement is not claimed to equal Android density times
the setting through every native ancestor transform.

The temporary fifth application was removed. Original four Dock apps remain
in order; gesture-handle visibility was restored. Dock remains enabled with
Recents, padding 18, translation 0, custom radius 24 and name marquee unchanged.
Final native revision/notified both read 9, and the full options ACK is legacy
value 1 after reset.

Checks: 97 focused JVM tests, Release lint, native wire/lease sanitizers including
80 extended choices, 38,602 geometry assertions, 4,609 linked ARM64 cases across
rendering/placement/material/mode/refresh checks, and five source-profile
admissions with mutated margin returns rejected. Pad evidence is source-only;
this task did not install/test on the physical phone or live Pad.

### Horizontal padding on physical Phone, 2026-11-21

The Xiaomi 17 Pro ran `OS4.0.8.0.XBLCNXM`, Home `801030308`, and
KeiOS-signed KeiHome `113000656`. The in-place update matched the archived
APK hash and passed all nine observed Java hot reloads. A separate Home-only
relaunch initialized the Dock renderer and mapped the newly installed APK.
SystemUI and system_server did not restart.

Three- and four-icon layouts accepted -16, 0, +8 and +24 dp. Captures show the
plate narrowing and widening while the icon positions stay fixed for each
count. The +24 dp setting can reach the available-width limit. Dragging a
shortcut out and back updated the plate as the count changed. Tapping the
outer icon at +8 dp opened Via, and the return animation settled back onto the
adjusted plate. Reset restored 0 dp and received the native settings ACK.
The original 206 launcher rows and screen order matched after the test;
the applied icon file and system settings were unchanged.

Evidence: `.artifacts/research/dock-horizontal-padding-20261121/`, including
`physical-acceptance.json`, the three- and four-icon comparison captures, and
the drag and launch recordings. The Release passed lint with zero errors.
The 129 focused JVM tests and linked ARM64 bridge checks passed. A separate
comparison against the prior signed build produced identical native Dock
object graphs in 240 zero-adjustment cases. Five-icon and wide-layout
horizontal padding coverage is host-only; no live five-icon or Pad test ran.

### Vertical centering across label modes, 2026-11-22

The old renderer limited each vertical edge independently. With labels on,
the native icon top can be close to the upper paint limit. Clipping that
padding alone left more glass below the icons. The new renderer uses the
smaller available padding on both sides of the painted icon bounds. It reuses
the dynamically resolved effective no-word mode and native metrics.

On Xiaomi 17 Pro (`OS4.0.8.0.XBLCNXM`, Home `801030308`), signed b0660
rendered a centered four-icon Dock with system no-word mode off and with
all labels hidden, at requested padding of both 18 and 32 dp. The 32 dp
request made a shorter plate in normal mode because the upper paint limit
was closer to the icons. Tapping the first icon opened Camera, and returning
home preserved the Dock. The original all-hidden mode and 18 dp were
restored, and the active icon theme hash matched the pre-test value.

The APK replacement recorded nine Java reloads. A separate Home relaunch
loaded the updated native renderer while SystemUI and `system_server` kept
their process identities. Host checks passed 54,173 geometry assertions,
3,561 linked ARM64 cases and 129 Home 0308 structural checks. The live
comparison covered one Phone layout with four icons.

Evidence: `.artifacts/research/dock-vertical-centering-20261122/`, including
`b0660-four-states.png`, `b0660-native-load.log` and the host test receipts.

## 1.14.0 physical reboot check

On November 22, 2026, the Xiaomi 17 Pro booted HyperOS
`OS4.0.8.0.XBLCNXM` with Home `801030308` and KeiHome
`1.14.0+gb57bce1.b0709.override` (`114000709`). The installed APK digest
matched the signed release. Home mapped the executable segment of
`libkeihome_hyos_lsp.so` from that APK, KeiHome reported a native desktop
connection, and both supported Dock settings were acknowledged.

The existing four-icon Dock accepted side settings of -16, 0, and +8 dp
with app names shown and hidden. Screenshot estimates at the center of
the glass gave widths of 1044, 1140, and 1190 px, respectively. The icons
stayed in place as the side setting changed. At 18 and 32 dp requested
height, the sampled icon center and glass center were within one pixel
in both label modes. With no-word mode fully disabled at +8 dp, the
outer icon opened Via and returned to the same Dock. The initial
widgets-only no-word mode, 0 dp sides, and 18 dp height were restored.
The four icon positions, active theme file digest, night mode, and
Home, SystemUI, and system_server PIDs matched their pre-test values.

The boot capture contained one shortcut enumeration rejected while
user 0 was locked. Enumeration resumed after unlock and returned 77
shortcuts. An older runtime status request had a negative age after boot
and expired under the existing freshness check. The app reported ready
when opened. No KeiHome, Home, or SystemUI crash or ANR was observed in
the captured window. Android recorded a low-memory exit of KeiHome's
background provider process. Separately, SystemUI logged a rejected
`keihome_maml_palette_state` query while live palette coloring was off.
The crash buffer also contained an early `init` SIGABRT without an
available crash dump. The capture did not establish its cause. Pending
icon import recovery and live Monet coloring were outside this run.

Evidence: `.artifacts/research/release-1.14.0-reboot-dock-20261122/`,
including `verification.json`, `dock-padding-comparison.png`, and
`centering-four-states.png`.
