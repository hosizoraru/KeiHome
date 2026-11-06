# Dock geometry customization

## Choices and preserved behavior

The existing Recents and Rounded choices remain, with Recents as the default.
Default geometry remains 18 dp of requested vertical padding and zero vertical
translation. All choices retain the native glass material and unlock/drag/edit
animation path.

Additional choices:

- **Pad** uses the retained Pad launcher's relation: `16 / 60 * iconExtent +
  (plateHeight - iconExtent) / 2`, bounded to the actual plate.
- **Screen-concentric reference** adjusts plate width and radius from the
  system-reported bottom screen radius. Vertical placement remains an explicit
  user adjustment. Five icons retain the full available plate width and edge
  clearance.
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
| 24-31 | Reserved, must be zero |

Native Dock layout capability is bit 32; existing Dock capability bit 8 is still
required. Legacy 0/1 remains compatible. Lease identities/status ACKs compare
full options. Unchanged renewals stay quiet; one committed change uses the
existing scoped refresh.

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
