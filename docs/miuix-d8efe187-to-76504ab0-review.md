# MIUIX source review: d8efe187 to 76504ab0

Reviewed on 2026-11-18 against the exact upstream Git objects and current
KeiHome source. This is an adoption assessment; it does not implement the
proposals below or claim new device/performance measurements.

Implementation follow-up:
[adaptive Pad navigation and shared chrome](pad-adaptive-ui.md).

## Scope and sources

- Before: `0.9.4-d8efe187-SNAPSHOT`,
  `d8efe1872c08c863a8b33e7044dd2a8b43038032`.
- Installed migration: `0.9.4-76504ab0-SNAPSHOT`,
  `76504ab028bdff42dff346577b4e3780069a1969`.
- Range: 21 commits, 82 changed files, 5638 additions and 1348 deletions,
  including documentation, examples and upstream tests.
- Primary sources: upstream `miuix-glass`, `miuix-ui`, `miuix-blur`,
  component documentation, examples, and commit titles/bodies.
- Exact selected source is available locally at
  `.artifacts/inputs/miuix-76504ab0`; range history is in
  `.artifacts/inputs/miuix`.
- Previous migration/publication evidence:
  [snapshot migration](miuix-snapshot-76504ab0.md).

Reproduce the review:

```sh
git -C .artifacts/inputs/miuix log --reverse --format=full d8efe187..76504ab0
git -C .artifacts/inputs/miuix diff d8efe187..76504ab0 -- miuix-glass miuix-ui miuix-blur
```

## Substantive changes

| Family | Actual change | KeiHome consequence |
| --- | --- | --- |
| New floating components | `GlassFloatingActionButton` and `GlassFloatingToolbar` (`447e614`) share native surface rendering, fallback, stroke and shadow. FAB is circular; toolbar accepts arbitrary content. | Adopted through `Os4FloatingToolbar` for labeled page actions and Apple/Perfect batch actions. Keep readable action labels; circular FAB is only a future fit for genuinely icon-only actions. |
| New navigation rail | `GlassNavigationRail` (`cd5d4b9`) morphs between a collapsed top capsule and expanded start-edge panel. Expanded destinations scroll; collapsed selection supports dragging. | A useful tablet/landscape candidate, requiring a deliberate root layout change. It is an overlay, not a drop-in replacement for a phone bottom bar. |
| Segmented tabs | Horizontal dragging with a morphing indicator (`63d51cd`); preview selection during movement, commit on release, cancel without selection. | Already inherited by `Os4ViewTabs`. Preserve count labels, selected-tab visibility, RTL, large fonts and the wrapper's overflow scrolling. |
| Navigation badges and geometry | Native badge content is placed around each icon (`afc2bbe`), with updated navigation indicator/drag geometry. Floating-bar spacing is corrected (`5983238`); another spacing change is iOS-specific (`ac0af46`). | Already uses per-item native badge slots instead of a fixed middle-item offset. Avoid adding a second inset or badge overlay. |
| Top bars | Small-title blur transition (`68d06c6`); compact-only mode via `expandable=false`; shared title layout; refined fallback gradient (`613db99`). Default Glass title blur changes from large-title-only 12dp to title blur 8dp. | Existing Glass bars inherit transitions. Shared KeiHome wrapper does not yet expose compact mode. Good candidate for dense detail/tool screens, with the main category pages retaining their hierarchy. |
| Scrolling controls | `PullToRefresh`, `ScrollBar`, `Slider`, and top-bar paths move rapidly changing reads to layout/drawing or narrowly derived state (`3a980f7`). Pull refresh keeps hot-path distance outside Compose state; scrollbar geometry is calculated in its offset lambda. | Current native pull refresh and Dock `SliderPreference` benefit through the dependency update. `ScrollBar` is not yet used in app pages. No new Glass slider/switch was introduced in this range. |
| Blur pipeline | Progressive stack reduced to full/light levels, normalized gradient uniforms precomputed, Android alpha-normalization work combined into a later pass, sharper-band recording/downscale refined, shader sampling/color/noise work reduced (`c4fd942` through `76504ab`). | Shared backdrop users inherit these changes. Do not add redundant recording layers or infer measured FPS gains from code changes. Skiko-specific changes should not be reported as Android gains. |
| Blur limit | `BlurDefaults.MaxBlurRadius` reduced from 150f to 100f (`d45ca18`, explicitly marked breaking). | Current custom backdrop radius is 10f, so this cap does not truncate its requested radius. This is separate from title-blur configuration. |
| Insets/lifecycle fix | Scaffold keeps its consumed-insets observer stable, avoiding unattached-node traversal during movable-content reattachment (`9594868`). | Keep the upstream fix; the reported crash is iOS-specific, not evidence of an Android crash fixed in KeiHome. |
| Examples/docs/tests | Added floating/rail examples and interaction coverage; About example always uses the collapsed title (`7188729`); blur/material support guidance was clarified. | Useful implementation patterns. Documentation edits do not imply a new material API or a new Glass counterpart for every basic component. |

## Recommended next adoption

### 1. Compact Glass top bars for selected secondary screens

Extend `KeiHomeTopAppBar` in `ui/components/Os4Chrome.kt` with an
`expandable` argument, forwarding to the native component. Default to the
existing expanded behavior.

Evaluate `DesktopToolsScreen`, `LogSettingsScreen`, and focused environment
details first: users open these screens to read state or execute a task,
so a permanent large title may use space better reserved for content.
The About page has an intentional hero and currently an empty bar title;
do not flatten that design merely to copy the upstream example.

This is a shared wrapper improvement, not separate title-height workarounds.
Check first-frame height, return navigation, 160% font, long translations,
light/dark mode and blur-off fallback.

### 2. Native scrollbar for long icon lists

Evaluate `ScrollBar` alongside the existing `LazyListState` in `AppsPage`,
`CustomIconsScreen`, `PreservedIconsScreen`, and `PerfectPackPreviewScreen`.
It is an existing component optimized by this update, not a newly added one.

Start with a long catalog where quicker positioning is useful. Keep search
and filters, share list state, and avoid creating another scroll container.
Use the same shared wrapper if several pages benefit.

Check interaction with pull refresh, the system back edge, trailing actions,
bottom toolbars, filtered counts and restoration of position. Large lists can
have variable-height rows; assess position behavior on real data before
enabling draggable positioning across every list.

### 3. Wide-screen navigation

`MainScreen` currently couples five logical destinations to a pager and
bottom navigation. A rail should reuse those destinations, selection,
badges and retained page states.

Use available width/window constraints to decide the layout. Place the rail
outside the backdrop recording. Let foreground content move with
`GlassNavigationRailState.progress` in layout; hide or yield the top title
when the collapsed capsule occupies it. Preserve page state when resizing.

This is the largest design task here. Evaluate it on tablet, landscape and
split-screen rather than forcing the rail into the phone bottom-bar slot.
For grouped secondary destinations, review the subsequent rail fixes before
committing to the initial rail API.

### 4. Keep floating actions consolidated

The main migration is already complete. Future batch actions should go
through `Os4FloatingToolbar`; use native circular FAB only when the icon is
unambiguous without a text label.

Retain labels for import, download, export and operations with busy/cancel
states. Preserve a single accessible action node, disabled semantics, actual
toolbar height and scaffold-owned safe/keyboard insets.

## Subsequent source changes: separate review scope

The eight commits after the selected snapshot, through the previously
inspected `6d2554a850b8960e2616c968263ff64beb5ffb03`, include:

- Rail expanded secondary destinations (`9981e09`) and icon alignment
  (`42b3b53`).
- Glass icon cleanup (`758bb3e`): `CaremaOutline` to `CameraOutline`,
  removed `DrawerListHandle`, updated `ExpandMore`/`Sort`, added `UnfoldMore`.
- Library baseline-profile/generator coverage (`5e66cbf`) and regenerated
  example profiles (`bf8281e`). App-specific journeys still need their own
  profiles; copying example profiles is not app acceptance.
- Example popup ownership moved into `TopLayer` (`189ef10`).
- Example `AdaptiveTopAppBar` status-bar background correction (`4d2e718`).
- Version bump to `0.9.5-beta01` (`6d2554a`).

These are not included in the selected dependency. In the preceding migration,
publication run 44105631618 failed and the 6d artifacts were incomplete across
modules, so 76504 was selected as the newest complete common snapshot at that
check. This review does not assert that this publication status remains current.

The example status-bar fix is not the Xiaomi launcher wallpaper contrast
problem investigated separately. Root-layer popups are already an app
architecture rule; do not add another portal layer based only on that example.

## Cost and adoption boundaries

- Prefer the shared chrome, tabs, toolbar and backdrop pipeline over new
  page-specific visual implementations.
- Keep information cards and ordinary settings controls opaque where they
  provide hierarchy and readability. No blanket glass conversion.
- Keep disabled-control behavior; native Glass icon buttons do not yet replace
  every basic fallback used by KeiHome.
- Do not increase blur radius, add sampling layers, rewrite stable sliders or
  change application/native runtime code merely because a new example exists.
- No performance numbers were collected for this source review. Validate any
  future performance claim against an R8 Release on the same device and flow.
