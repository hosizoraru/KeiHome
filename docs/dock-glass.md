# Native launcher Dock glass

The optional **Desktop → Dock → Dock glass background** switch adds a
floating background behind the native phone Dock. It defaults to off. The AVD
acceptance setup leaves it on for visual review.

## Appearance and ownership

Both corner options use the launcher's own `GlassOs4Background` and
`_DockGlassWidget` filter settings, the material family already accepted for
soft-glass Dock surfaces. Native older/basic/plain material decisions remain
owned by the system.
It does not introduce a separate wallpaper sampler, blur engine, or color scheme.

The plate follows the painted icon envelope for one through five normal-mode
items; an empty Dock has no added plate. The enlarged plate uses 18dp desired
padding on each side. Rounded retains its 28dp minimum corner radius. Native horizontal screen
insets still bound its width. Its decoration can paint up to 12dp above/below
the original box, while native layout and hit bounds keep their original size. Native elderly-mode capacity is handled
separately. Native item centers and icon sizes are retained. Padding is bounded
by the available Dock and screen edges. With five items only, the plate can use
the full native content width rather than retaining the 8dp decorative edge,
and the corner radius is capped at 32dp. This leaves the first/last icons inside
the plate's curved corners. Native item positions, touch bounds and all layouts
with four or fewer items retain their previous geometry.

Bottom screen corner radii come from `Display.getRoundedCorner`, converted using
the same display's density. The selector offers two policies:

- **Recents** (default): observe the actual native task-radius calculation, including its
  inlined flight caller before flight-size scaling. Until a normal native call
  supplies a value, use the source-authenticated ordinary radius. The native
  constants and control flow are resolved from code, not an OEM version table.
  `RecentsSuperellipseConfig.isEnabled` supplies the native device eligibility;
  radius is bounded by half of the actual plate's short side, as in the native
  adaptive-clip predicate. Eligible soft glass uses `ClipRSuperellipse`, otherwise
  `ClipRRect`. Only the owned original Glass inner clip changes class; its original
  native glass-filter parameters are retained. The outer PhysicalShape uses
  authenticated `Clip.none` and introduces no second circular clipping layer.
- **Rounded**: retain `max(28, screenRadius - nearerHorizontalInset)`, capped by
  plate dimensions, with the original native Dock glass material.

Both respond to current display metadata. Recents refers to reuse of native
card geometry/material primitives; it does not equate a rendered card's local
radius with an unscaled physical screen radius. The native card's animated
transform, task layout and asymmetric corner distribution are separate. Older
blur/plain material modes retain their own native material/clip behavior.

An earlier candidate reused the native window/Recents glass component and
produced a flat gray surface on the AVD. It was rejected. The final design keeps
the original Dock filter and uses a genuine native ValueKey with a Smi token to
scope adaptation to module-owned material instances. Ownership is captured
while the original receiver is live, carried only as a Smi in a proven unused
future child slot, then transferred to the fresh native DockGlassWidget key.
No managed pointer is retained in C++ or borrowed across an untracked GC slot.
All original native scope, opacity, filter and child parameters remain intact.

The injected nested Stack uses an authenticated native `Clip.none` value so
negative decorative insets are not cut off. The original phone Stack also
selects none only for the module's first SizedBox decoration, preserving the stock
clip for an empty/native child list, disabled state and every fallback. Its clip store is a call-free leaf using only the register that the
next authenticated native instruction overwrites. This prevents full five-cell
layouts from cropping the taller decoration again at their parent. Only the added material is inside
a native `IgnorePointer`. Native icons,
dragging, and the original conditional `ItemBackground` retain their ownership.
This matters: an earlier candidate displayed correctly but consumed page swipes
starting in the blank Dock area. The final renderer explicitly makes its
decorative material transparent to input.

## Dynamic source contracts

Production has no launcher version, APK hash, or absolute-address allowlist.
It authenticates instruction and data-flow relationships for the original
phone background gate, material factory, Stack/Positioned/SizedBox/IgnorePointer
allocations, native `computeMode`, Recents eligibility and clip dispatch, native
ValueKey and Glass frame liveness, and the existing reactive refresh path.
Unrecognized or ambiguous contracts are rejected before publishing patches.

The five source fixtures are 7722, 8593, 8602, 9209, and 9226. Only 8593 has been
accepted on the current HyperOS AVD for this feature; fixture execution is not
an assertion of visual acceptance on five installed launcher versions.

Resolution must precede overlapping patches. Startup prepares the Dock contracts
from the original graph, installs labels and highlight, completes Monet and
selection resolution, and only then installs the prepared Dock patches. A
prototype's earlier order prevented Monet resolution; the final order preserves
all native feature installations.

## Lifecycle, refresh, and cost

Preferences use the framework-owned API 102 `theme_effects` group. These remote
preferences intentionally remain framework APIs rather than app-local MMKV.
Native capability/command 8 has an independent 45-second policy lease. Display
changes and settings reuse the existing worker; no new timer or thread is added.

An enable/style change, or an enabled display-radius change, requests one scoped
package callback. It does not enumerate installed apps or regenerate icon
artwork. Unchanged renewals leave the native revision unchanged. Native
`computeMode` and native curve eligibility run under the existing reactive
builder, so system material changes retain native reactive ownership. The
three-phase continuation uses the original native return PC and stack map;
only primitive mode/eligibility values leave the managed call. Task-radius
observers replay the original frame load or return instruction; they retain
only raw numeric bits and never call a controller themselves. The copied
render configuration is 456 bytes, mode configuration is 72 bytes, and the complete
payload is 4,168 bytes. The executable mapping rounds that bounded payload up to
the host page size; this uses two pages on a 4 KiB system.

The render bridge is a call-free leaf with a fully checked young-space arena:
400 bytes for Recents soft glass plus a 16-byte native child key when eligible,
384 bytes for Rounded soft glass, and 368
bytes for other material modes per Dock build.
These are build allocations, not per-frame allocations. Allocation pressure or
invalid geometry returns to the native path. There is no custom Dart frame,
managed reference retained in C++, polling animation, or per-frame JNI call.

## Native unlock transition

The decorative plate is wrapped in the same native `UnlockAnimWidget` category
used by the small search bar. Previously the icon and search animations ran
while the added plate was already painted at its final size and opacity.
The wrapper now participates in the launcher's own prepare, show, completion,
interruption and disposal paths. It adds no independent animation clock,
delayed task, broadcast receiver or preference.

Only the decoration joins this transition. Its existing `IgnorePointer` remains
inside the animation widget; the original icon subtree, callbacks, drag targets,
occupied-cell sizing, five-icon containment and stock background remain outside.
The existing native glass filter and its material parameters are unchanged.
The native search category resolves its live center and screen visibility from
current launcher configuration. A canonical native `Offset.zero` is only the
typed initial seed; no phone size, absolute screen position, DPI or animation
duration is embedded in the bridge.

The wrapper is one immutable 48-byte widget per successful Dock build, included
in the arena totals above. Its state and animation lifecycle are native Flutter
objects. These are distinct from bridge allocation and are not claimed to have
zero cost. On stable layouts the same element/state is retained across builds,
and no module timer drives idle frames.

Admission authenticates the native search caller's center-call edges, complete
widget field stores, allocator size/tag, serialized `createState` dispatch and
state constructor, plus the native location/center roles. There are no new
code patches or managed-call boundaries. All fields are initialized before the
original caller can reach a safepoint. Unsupported or ambiguous contracts fail
closed rather than manufacturing an unverified widget class.

The five retained source fixtures resolve this graph and reject mutations of
its calls, layout, category and state roles. Linked production ARM64 execution
also checks the wrapper's child, canonical fields, exact arena bounds, heap
pressure fallback and preservation of registers/stack. Device acceptance is a
separate HyperOS Phone AVD gate; source coverage is not five-device acceptance.
Current evidence is under `.artifacts/research/dock-unlock-20261106/`, including
baseline/candidate unlock videos and the final `acceptance.json` receipt.

An acknowledgement means the live native endpoint accepted the policy; it is
not a receipt for a painted frame. Expiry disables subsequent native builds.
Already mounted widgets are removed by the next relevant native rebuild, not by
a promised visual deadline at exactly 45 seconds. If a scoped callback is lost
or native refresh guards defer it, an idle mounted tree can remain until another
native item event or rebuild. The implementation deliberately avoids entering
managed Dart from the policy-server thread. See [refresh contracts](dock-glass-refresh.md).

## Verification

The final linked production payload passed:

- 2,485 render, gate, geometry, input-wrapper, corner-style and fallback cases.
- 175 mode/curve bridge and actual native `computeMode`/cached getter cases.
- 470 getter, `Rx.refresh`, and `GetStream` native instruction execution cases.
- 593 owned-glass scoping/pressure/root-handoff cases.
- 495 refresh source/mutation checks and 550 render profile/rejection checks.
- 38,495 C++ geometry checks with ASan/UBSan.
- 55 focused JVM tests for corner preferences, transport, lease and status.

These controlled ARM64 execution tests cover original return PCs and simulated
root relocation; they do not replace Dart VM GC or Flutter visual testing.

Actual device evidence lives under
`.artifacts/research/dock-glass-20261104/`. It includes the zero-to-five item
screenshots, a native material change from mode 0 to 1 and back without a feature
revision change, day/night rendering, native app clicking and dragging, and
blank-Dock swipe passthrough. Temporary apps are removed and the original
Gallery / Settings / MT Manager / Via Dock is restored before final acceptance.

The final normal-settings gate switches Recents → Rounded → Recents and Dock
OFF → ON without a native activation, force-stop or Launcher restart. The
Launcher PID/parent/start remain 30358/27367/3958047 in all five receipts;
system_server and SystemUI also retain their identities. Native ownership,
child-key and Superellipse branch counters are nonzero. A genuine bottom
swipe through Recents captures 44.210527795173505dp on this AVD. Rounded and
Recents screenshots visibly differ while both preserve soft glass. Five icons
were dragged into the Dock and the temporary package was then removed.
Development installs changing machine code deliberately used native activation;
these are separate from the normal-settings result above.

Performance receipts and the final artifact/process identities are recorded in
`acceptance.json` in that directory. Measurements are HyperOS AVD observations,
not physical-device energy or frame-time guarantees.

### Bounded AVD performance sample

Release 194, same four-icon weather page and eight alternating 450ms swipes,
12-second FrameTimeline samples, no trace-loss/overwrite counters:

| Style | Idle frames / CPU of one guest core | Swipe frames / CPU | App deadline misses during swipes |
| --- | --- | --- | --- |
| Recents | 9 / 2.22% | 298 / 66.58% | 70 |
| Rounded | 16 / 3.62% | 292 / 66.37% | 56 |

Idle does not continuously redraw at display refresh rate. Swipe CPU is close;
mean FrameTimeline duration was 34.89ms versus 34.37ms. Both samples still have
AVD frame misses; this single pair does not prove equal GPU cost or physical
phone smoothness. Captures include the existing clock/widgets/native rendering.

## Performance refinement, 2026-11-05

Keep native GlassOs4Background / DockGlassWidget state, filter caches,
WidgetRoot pass-blur registration and all native animation ownership. Search
and folder source analysis is a comparison of responsibilities: their generic
GlassWidget and folder press/drag spring lifecycles are not transplanted.

Two bounded changes reduce construction work without changing visual fields:

- Owned Recents glass skips only its native radius diagnostic segment. The
  original radius spill is replayed; the visual path, DockScope and native
  filters remain intact. Saves 19 original instructions, a 16-byte boxed Double
  allocation/possible slow path, String interpolation and logWithTag calls per
  owned build. The earlier logger setup/interpolation array still runs. Stock
  instances and Rounded glass keep their diagnostic path; errors/warnings are
  not globally suppressed. This is not a per-frame FPS saving claim.
- The native continuous-corner eligibility getter runs only for soft glass in
  Recents style. Material computation and its reactive dependency always run
  for enabled Dock. Rounded/older/plain modes avoid the unused managed call.
  Restoring Recents re-queries eligibility; preferences racing a managed call
  are covered by execution tests.

The skipped callees have independent instruction contracts. The first retained
native call's original GC bitmap must be exactly empty; changing it rejects
admission. Original VM return PCs, frame roots and stock continuations remain
covered by all five source fixtures. No retained Dart widget cache, extra
animation/timer, reduced blur quality or changed shader parameter is introduced.

Evidence: `.artifacts/research/dock-perf-20261105/`. Five normal preference
operations preserve Launcher 10386/5477/4463127 and server/UI identities.
Folder open, search open and app enter/return were exercised on AVD8593. The
source profiles pass 550 checks and linked execution passes 3,723 cases.
Same-scene 12s weather/swipe samples show no established FPS gain; the removed
work happens on relevant rebuilds, not every frame. Whole-launcher CPU includes
clock/widgets/marquee and cannot be attributed entirely to Dock. See the
comparison JSON and acceptance receipt for actual frame and CPU observations.

## Display adaptation checkpoint (2026-11-05)

The plate is built inside the native Dock parent. Width, height, visible icon
size, icon top, cell metrics and mode come from the source-authenticated current
layout. Native positioning/insets remain in charge; no physical screen size,
DPI, screen-bottom Y or test-device coordinates determine the plate position.
The decorative dp padding/overflow are design limits. Public corner metadata is
read from the context's display, with a default-display fallback, and converted
to the same logical density as Flutter.

If the native parent plus bounded overflow cannot contain the complete painted
icon vertically, decoration falls back to the original background. This matches
the existing conservative horizontal admission. The parent Clip.none adjustment
now additionally verifies the module's first SizedBox child through the native
live list root; an empty list or native fallback keeps original clipping.
This guard uses one dead register, preserves NZCV and adds no heap allocation.

Actual Phone AVD checks include density 400 and 560 at 1120x2436, and 960x2160
at density 480. The native Dock reflowed and contained all four original icons.
Original 1120x2436/density480 overrides were restored. Launcher PID 6549 stayed
unchanged through these configuration checks. This is bounded configuration
coverage; it does not establish universal form-factor or landscape acceptance.

Wallpaper material selection and Pad source findings have separate source and
device acceptance from these placement checks.

Additional portrait checks use 1080x1920 and 1296x2800 at the original 480dpi.
The visible Dock icons remain inside the plate, whose vertical location
follows the native layout. Launcher 3844/2126/9661200, system_server and SystemUI
identities stay unchanged; 1120x2436 is restored afterward. The short-screen
vendor layout places the native search capsule close to its last workspace
row; this check establishes Dock placement, not a redesign of vendor workspace
spacing. These are simulated configurations on one AVD, not physical-device
or universal landscape acceptance. See
`.artifacts/research/dock-material-20261105/geometry-device/expanded-layout.json`.

## Five-slot containment regression (2026-11-07)

The full five-slot plate already spans the native content width, but the older
painted-envelope guard still required 8 dp of unused space at both edges before
reaching that branch. Valid layouts with 0..8 dp clearance therefore returned
the original transparent decoration. This depends on the native layout, icon
size and display density; it is not a phone-model or launcher-version rule.

For exactly five items in a native five-slot Dock, both the pure geometry model
and the linked ARM64 bridge now require painted-icon containment instead of
the spare 8 dp margin. Counts one through four retain the original margin and
geometry. Non-finite, negative, out-of-bounds and elderly four-slot overflow
inputs remain rejected. The patch changes no icon positions, hit targets,
material fields, animation owners or native parsing contracts.

The previous header and archived Release 226 native payload both reject the
new exact-edge regression. The corrected model passes 39,347 ASan/UBSan checks;
the linked production ARM64 renderer passes 3,411 cases. Source evidence and
the device acceptance receipt are retained under
`.artifacts/research/dock-five-9226-20261107/`.

On the 9226 Phone AVD, five icons remained visible while the old glass plate
disappeared after changing density from 480 to 560. The screenshot
`five-density560-before.png` records this reproduction. The App-only log ZIPs
supplied by users do not contain this native geometry state. The separate
LSPosed archive confirms glass-hook activation and settings acknowledgement;
it must not be treated as an exact measurement of the painted edge clearance.

The patched Release restores glass in that same five-icon/density560 scene.
Removing the owned fifth probe retains the four-icon glass; resetting density
to the original 480 retains the original Dock order. Launcher identity stays
`25336/25317/8968548` through those ordinary layout changes and App log export.
The earlier native development activation is recorded separately. The original
8593 launcher was subsequently restored by cover installation; its native Dock
and label hooks are active. No physical-device acceptance is claimed here.
