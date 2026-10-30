# MIUIX pages, icon previews and refresh

The four pages retain the existing MIUIX top bar and sample-based liquid-glass
navigation. English, Simplified Chinese and Japanese cover all new controls.

## Page responsibilities

- Status: four compact MIUIX cards for framework identity, runtime, Monet and app
  counts. Runtime and Monet details expand independently. The framework version
  name/code come from API 102 service getters, separately from the API level and
  module build. A loaded hook is not proof of a visually updated icon.
  The light/dark refresh timestamp remains unreported without a runtime receipt.
- Apps: search/filter the accessible launcher applications and show their actual
  light, dark, Monet-light and Monet-dark resources. The default filter includes
  detected night or monochrome resources. Invalid/missing monochrome layers use
  the original icon and a fallback label; unavailable palettes are not invented.
- Monet: retain the existing source/seed/style picker and unapplied draft while
  navigating or reading new palette data. Applying is a separate action.
- Settings: app-only System/Light/Dark appearance, language, icon-preview
  visibility, preview-cache clearing, and copyable app/version/platform details.
  Appearance also updates system-bar contrast and the liquid-glass chrome.

## Refresh scope

| Button | Work requested |
| --- | --- |
| Status | Read framework/target state, palette and last application receipt; rescan app capabilities and invalidate preview cache. |
| Apps | Rescan accessible profiles, invalidate preview cache, and read the current palette. Keep filter and search text. |
| Monet | Request fresh wallpaper extraction through API 102, await its read receipt, then read palette/settings/framework state. Keep the unapplied draft. |

The button shows progress and prevents repeat submissions for its page. Existing
contents remain visible. A completion time or partial-failure message is exposed
as a polite accessibility live region. Wallpaper refresh is described in
[wallpaper-colors.md](wallpaper-colors.md#explicit-palette-refresh).

## State and rendering costs

`HomeViewModel` owns scans and pending actions across configuration changes.
Each page collects its own lifecycle-aware state. The root navigation does not
collect every scanner, framework and palette update. Only the visible page polls
framework/palette data, and polling stops below STARTED. App scanning starts on
Apps entry or explicit refresh; package callbacks invalidate results without a
new manifest receiver. Search, drafts and per-page scroll positions survive tab
navigation. Search/filter work is remembered, and application rows have stable
user/package keys and content types.

Framework snapshots project SDK targets into scalar state/version values. Release
compiler reports now infer `FrameworkSnapshot` and `FrameworkTarget` as stable;
the baseline snapshot was unstable. `AppCapabilityCard`, `StatusPage`,
`MonetSummaryContent` and `PreviewPreferences` are restartable/skippable. No mutable
SDK or bitmap types were falsely annotated as immutable.

`IconPreviewRepository` renders off the main thread with at most two concurrent
jobs and a 4 MiB LRU bitmap budget. It resolves the actual launcher entry and
profile by user serial. Keys include component, app version, APK stamps, resource
configuration, density and requested size. Cache hits avoid creating Resources.
It preserves adaptive-icon child bounds/masks and checks actual monochrome alpha
quality. Cached alpha masks are recolored at draw time; palette changes do not
rerasterize them. Item cancellation cancels its load. Cache clearing prevents old
in-flight work from repopulating the cache, and published bitmaps are not recycled.

Generated palettes are cached by seed/style/family overrides rather than repeatedly
converted to JSON. Picker scheme previews are computed off the main thread once per seed.
Relative application time updates in a small lifecycle-aware leaf. Settings
setters publish directly to shared StateFlow after updating private preferences;
they do not depend on a weak SharedPreferences listener to repaint the UI.

Status, Monet and Settings are forms, not collections: each uses a scrollable
Column under the tab's SaveableStateProvider. Previously a single giant lazy item
owned their nested saveable state and discarded it during tab disposal. Device
tests reproduced both the draft loss and the relevant expansion-state boundary;
the revised containers retain them. Apps keeps its keyed lazy list. Per-page
scroll state and the existing MIUIX top/bottom chrome remain in place.

The MIUIX color dialog uses TextFieldState/InputTransformation for HEX input.
Picker movement changes local editor state; confirmation changes the saved draft;
Apply alone submits the runtime request. Slider changes preserve requested HCT
values instead of round-tripping them through gamut-mapped RGB. The dialog gives
scrollable content the remaining height after its buttons, including with the
keyboard open, and uses the overlay dismissal lifecycle before returning a result.
Exact role/background-family changes reuse tonal ramps and recolor existing icon
masks. Neither preview generation nor wallpaper/Settings/Binder access was added
to composition or to color-slider callbacks.

Optional compiler diagnostics: `-PcomposeCompilerReports=true`. For complete
reports after an incremental build, add `-Pkotlin.incremental=false`. Inspect
Release reports; debug timings are not used as performance evidence.

## Focused validation: 2026-10-30

Only `emulator-5580` / `KeiOS_HyperOS4_Phone` was used. Installed versionCodes
increased from 1793305279 to Debug 1793309703, Release 1793310425, and final
Release **1793311572**. Release assembly, R8 and required lint passed.

- Six selected JVM tests passed: three search/filter tests, two language tests,
  and the read-only refresh request test.
- Eight device tests passed for icon resources, adaptive geometry, bad-mask
  fallback, profile resolution, cache invalidation/eviction and cancellation.
- Four distinct UI cases passed: page responsibilities, languages, filter/top-bar
  refresh, and search retention. An initially ambiguous tab text matcher was
  narrowed to selectable tabs; only that case and the new retention case reran.
- Final Release manual checks covered all four pages, Status/Apps refresh,
  compact and four-icon layouts, and live appearance/preview preference changes.
  The original Release persisted settings without repainting; direct StateFlow
  publication fixed this, and the corrected Release was visually checked.
- Monet refresh produced a new receipt while preserving an unapplied Vibrant
  draft and the accepted Tonal Spot configuration. Its final Release receipt was
  1793311808197. The themed-icon switch and background setting stayed unchanged
  with the same wallpaper. Draft was restored to Tonal Spot without applying.
- The AVD scan found 45 launcher apps, four with night resources and 17 with
  monochrome resources (18 unique matches for the default support filter).
- API 102 Java reconciliation completed at every installation. Launcher identity
  stayed PID/start ticks `9692 / 3609`; system_server `8839 / 2743`; SystemUI
  `568 / 199972`. No native replacement was requested. Final logs contained no
  new crash for these processes or KeiHome during the final checks.

Final AVD preferences are System appearance, previews enabled and language
following the system; system night mode remains `yes`. Test AppOp 10021 was
restored to `ignore`. Home-wallpaper/Tonal Spot customization remains enabled.

Local evidence: `/tmp/keihome-ui-polish/`, including focused test logs, baseline
and Release compiler reports, deployment identities and final screenshots.
Selected screenshots and reports are also saved under
`/Users/voyager/.codex/visualizations/2026/10/30/keihome-ui-polish/`.
This is compiler/state/cache and focused visual evidence, not a measured FPS or
frame-time improvement. The earlier watchdog/SystemUI ANR observations documented
in `wallpaper-colors.md` remain unresolved; this UI work does not claim to fix them.
