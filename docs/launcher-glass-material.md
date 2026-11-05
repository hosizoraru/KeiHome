# Native Dock and search glass material

The setting **Dock 与搜索框质感** provides Follow system (0), Light material (1),
and Dark material (2). Follow system is the default.
English, Simplified Chinese and Japanese resources and setting search share
these values. The presets select native material parameters. They do not force
a different GPU backend or modify wallpaper classification or system brightness.

The saved AVD 8593 and physical 8602 Dock filter builders have matching control
flow and explicit material data after relocation/pool normalization. No richer
AVD-only parameter preset was established. The native applied-wallpaper flag
selects visibly different existing presets, independently of system night mode.
Processor generation is not used to choose a preset.

## Source and lifetime

The original Wallpaper Rx getter always runs, preserving subscription collection.
A small copied ARM64 leaf chooses the original native continuation at each
admitted material branch. It leaves the returned boolean, NZCV and the managed
frame unchanged. The native cached filters, parameter objects, blur, refraction,
press behavior and animations remain owned by the launcher.

The source flag means **applied light wallpaper**. Historical profile field
names `darkTarget` and `lightTarget` refer to the filter token appearance.
Wire value Light (1) selects the native true branch; Dark (2) selects false.
This naming distinction is covered by linked native execution tests.

The shipping scope is Dock and the native glass search capsule. Folder press
and resting-background candidates were investigated separately. Resting folders
use theme colors/resources; no equivalent wallpaper-preset pair or safe refresh
for the observed resting folder was established. Folder overrides and refresh
hooks are excluded from Android activation. Their saved-source audits are
compiled on the host only, and the unshipped runtime experiment is archived under
`unshipped-folder/`. Folder animation, resources and material stay native.
Each optional role must have a unique source/allocator/token/control-flow
relationship; an ambiguous or changed role contributes no patch. A hook count
or preference ACK alone does not establish resting-folder visual acceptance.

`launcher_glass_material_profile.cpp` resolves the mapped Flutter AOT graph,
including field-table and Rx links, token agreement, native source calls,
return PCs and stack maps. It has no OEM version/hash/RVA allowlist. Retained
source fixtures are 7722, 8593, 8602, 9209 and 9226. These are source fixtures,
not five device acceptance runs.

A changed preference requests one scoped launcher notification. The first
original discarded logger return PC supplies a real managed checkpoint for
native `Rx.refresh`, without replacing either the label or Dock logger detour.
The leaf checks controller/Rx/subject initialization, canonical values,
listener presence and native busy/closed state. It publishes its primitive ACK
before the wallpaper notification and replays the displaced instruction once on
return. The copied configuration is 128 bytes. No folder-setting callback,
equality bypass or folder-return detour is installed. No managed reference is
retained in C++.

The API 102 remote preference is `theme_effects.launcher_glass_style`.
Command 9/capability 16, status schema 6, a 45-second lease and the existing
15-second worker keep the setting independent of Dock enable/corners, icons,
Monet and app-name mode. Unchanged renewals do not invalidate glass. There is
no added timer, per-frame measurement, image decoding or wrapper widget.
Native callbacks retain their usual allocations during an actual setting change.
Unknown/unloaded capability is reported separately from launcher connectivity.

## Device and performance evidence

The readable evidence is under
`.artifacts/research/dock-material-20261105/`. Earlier development Release 212
exercised Dock/search on the Phone AVD (Home 8593) and physical Xiaomi 17 Pro
(Home 8602). Resting-folder crops remained identical, and later native diagnostics
found no listeners on the experimental folder-refresh route. These experiments
are not full folder-material acceptance. The final shipping scope and artifact
are recorded separately in `acceptance.json`.
Development native activation is separate from subsequent normal PID-stable
setting tests. Normal choices use API 102 and do not request process restart.

The source receipt includes 6,850 checks and 394 rejection cases across five
fixtures and an unrelated-image rejection. These totals include host-only
folder research; dedicated assertions verify that production admits only the
search capsule in addition to Dock. The final linked shipping test receipt is
`scoped-material-bridge.json`. Existing Dock evidence includes 2,785
linked rendering/clip cases and 38,181 geometry checks. The scoped parent
clip preserves the original clipping for a native/empty child list and all
20 single-bit mismatches from the resolved SizedBox CID. It adds no allocation.

Physical traces use Perfetto FrameTimeline, /proc CPU over the capture uptime
interval, native texture-pool counters and closed per-UID GPU accounting
periods. Trace transfer is excluded from CPU. Different GPU accounting-window
counts cannot be compared as total GPU work. These counters do not establish
per-pass blur costs or a battery improvement. Native backend/descriptor forcing
remains unshipped research in the ignored research archive.

## Pad reference

Read-only source analysis of HyperOS Pad Home 5490 on emulator-5582 found a
native radius of `visibleIconSize * 16/60 + min(verticalPadding, 30)` and a native
size/radius change gate. It is based on an icon-size reference rather than the
active icon drawable's mask. The Pad has its own launcher layout and an existing
native Dock. This finding does not claim the phone module was installed or
accepted on Pad. See `pad-native/FINDINGS.md` in the evidence directory.

## Scoped AVD acceptance

Release 220 is installed on the Phone AVD. Its three choices were changed
through the actual settings UI in both system light and dark mode. The native
revision advances once per change (1 through 6), with its notification caught up
after each change. Launcher 11505/11392/9972736 remains unchanged throughout;
new native code was activated once before this acceptance. The user-flow test
performs no force-stop or native reload. Screenshots and native-state snapshots
are under `visual/final-avd-bluewall-{light,dark}*`. Follow system and system dark
mode are restored afterward. Final artifact/version-only deployment is recorded
separately in `acceptance.json`.
