# Native launcher Dock glass

The optional **Settings → System & Hook → Dock glass background** switch adds a
floating background behind the native phone Dock. It defaults to off. The AVD
acceptance setup leaves it on for visual review.

## Appearance and ownership

The renderer uses the launcher's own `GlassOs4Background`, the same material
family used by its soft-glass surfaces. It also follows the native material
decision when the system selects the older blur, basic blur, or plain background.
It does not introduce a separate wallpaper sampler, blur engine, or color scheme.

The plate follows the painted icon envelope for one through five normal-mode
items; an empty Dock has no added plate. Native elderly-mode capacity is handled
separately. Native item centers and icon sizes are retained. Padding is bounded
by the available Dock and screen edges, including the five-item case.

Bottom screen corner radii come from `Display.getRoundedCorner`, converted using
the same display's density. The radius accounts for the plate's horizontal
inset and is clamped to its dimensions. Missing display metadata uses a bounded
default. This is an inset-aware approximation; it does not assume a particular
phone model or reproduce every vendor-specific continuous corner curve.

Only the added material is inside a native `IgnorePointer`. Native icons,
dragging, and the original conditional `ItemBackground` retain their ownership.
This matters: an earlier candidate displayed correctly but consumed page swipes
starting in the blank Dock area. The final renderer explicitly makes its
decorative material transparent to input.

## Dynamic source contracts

Production has no launcher version, APK hash, or absolute-address allowlist.
It authenticates instruction and data-flow relationships for the original
phone background gate, material factory, Stack/Positioned/SizedBox/IgnorePointer
allocations, native `computeMode`, and the existing reactive refresh path.
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

A changed setting, or an enabled corner-radius change, requests one scoped
package callback. It does not enumerate installed apps or regenerate icon
artwork. Unchanged renewals leave the native revision unchanged. Native
`computeMode` reads its dependencies under the existing reactive builder, so
system material changes retain native reactive ownership.

The render bridge is a call-free leaf with a fully checked young-space arena:
336 bytes for soft glass and 320 bytes for other material modes per Dock build.
These are build allocations, not per-frame allocations. Allocation pressure or
invalid geometry returns to the native path. There is no custom Dart frame,
managed reference retained in C++, polling animation, or per-frame JNI call.

An acknowledgement means the live native endpoint accepted the policy; it is
not a receipt for a painted frame. Expiry disables subsequent native builds.
Already mounted widgets are removed by the next relevant native rebuild, not by
a promised visual deadline at exactly 45 seconds. If a scoped callback is lost
or native refresh guards defer it, an idle mounted tree can remain until another
native item event or rebuild. The implementation deliberately avoids entering
managed Dart from the policy-server thread. See [refresh contracts](dock-glass-refresh.md).

## Verification

The final linked production payload passed:

- 1,683 render, gate, geometry, input-wrapper, and fallback execution cases.
- 125 mode bridge and actual native `computeMode` execution cases.
- 470 getter, `Rx.refresh`, and `GetStream` native instruction execution cases.
- 495 refresh source/mutation checks and 40 render profile/rejection checks.
- 28,775 C++ geometry checks with ASan/UBSan.
- 47 focused JVM tests for transport, lease, radius conversion, and status.

These controlled ARM64 execution tests cover original return PCs and simulated
root relocation; they do not replace Dart VM GC or Flutter visual testing.

Actual device evidence lives under
`.artifacts/research/dock-glass-20261104/`. It includes the zero-to-five item
screenshots, a native material change from mode 0 to 1 and back without a feature
revision change, day/night rendering, native app clicking and dragging, and
blank-Dock swipe passthrough. Temporary apps are removed and the original
Gallery / Settings / MT Manager / Via Dock is restored before final acceptance.

Performance receipts and the final artifact/process identities are recorded in
`acceptance.json` in that directory. Measurements are HyperOS AVD observations,
not physical-device energy or frame-time guarantees.
