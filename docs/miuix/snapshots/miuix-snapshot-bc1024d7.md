# MIUIX bc1024d7 snapshot

KeiHome now uses `0.9.5-bc1024d7-SNAPSHOT`, published from
`bc1024d78a154f5167efff262ae5ed66264c481e` (upgraded from
`588d029f2b0747a0a8710db0858ed0957d78b122`). Gradle resolved the Android
`debugRuntimeClasspath` graph to `0.9.5-bc1024d7-SNAPSHOT:20261126.071445-1`
across all MIUIX modules. [Publish run][publish]

| Upstream change | KeiHome integration |
| --- | --- |
| `8d3d0bb4` switches progressive blur ramp and sharp-band decay to linear `raw / (1 - m)`, retunes 2x/1x handoff thresholds to eliminate scroll jitter, anchors `GlassTopAppBar` progressive blur `startFraction` to `bottomContent`, lowers `ProgressiveBlurRadius` to `32.dp`, and uses `fastCoerce*` in hot paths. | `KeiHomeTopAppBar` inherits the updated progressive blur automatically, including pages with segmented tabs in `bottomContent`. `Os4ViewTabs` inside `KeiHomeTopAppBar.bottomContent` now follows `LocalGlassTopBarScrolled` (`surfaceAlpha` `0f` at rest, `1f` when scrolled), matching `GlassPage.kt`. |
| `f633ad2c` lowers `GlassTopAppBarDefaults.BandOverhang` to `10.dp`. | Adopted automatically through `GlassTopAppBar` defaults. |
| `bc1024d7` updates the example app (`IconPage` long-press copy and `ComponentPage` Snackbar insets). | Example-only change; no app code change required. |

## Glass UI audit and refinements

Auditing KeiHome screens against `miuix-glass` and the upstream `GlassPage.kt` / `GlassBottomSheetDemo.kt` demo surfaced and resolved the following gaps:

- **Top-bar & backdrop consistency:**
  - `KeiHomeTopAppBar` exposes `LocalGlassTopBarScrolled` to `bottomContent`, allowing `Os4ViewTabs` and `Os4CategoryTabs` to stay opaque at rest and transition into Glass when scrolling underneath.
  - `PerfectCustomStaticScreen` switched its back button from `basic.IconButton` to `KeiHomeIconButton` and removed a redundant nested `KeiHomeTopBarActions`.
  - `CustomIconsScreen` and `PreservedIconsScreen` moved `layerBackdrop` outside `keiHomeTopBarScroll` so elastic rebound does not shift the recorded viewport.
  - `DesktopToolsScreen` now passes `backdrop = backdrop` to `KeiHomeScaffold` and sets `overscrollEffect = null` on its `verticalScroll` container.
- **Glass bottom sheets & surfaces:**
  - `Os4DetailsSheet` now wraps `startAction`/`endAction` with `LocalGlassToolbar = true` (without overwriting `GlassBottomSheet`'s `LocalGlassHeaderContext`) so header actions render as native `GlassIconButton`s, forwards `scrollOffset` and `headerBottomContent`, and provides `LocalOs4GlassSurface = true` to sheet content (shared with `Os4Dialog`).
  - `CustomIconsScreen` and `PreservedIconsScreen` replaced plain `Button` actions inside `Os4DetailsSheet` with `Os4DialogButton` (`Primary` / `Destructive`).
  - `WallpaperPresetSettingsCard`, `PackFileImportAction`, and `MonetPicker` sheets adopted translucent `GlassCard` containers instead of opaque `Card`s; `WallpaperPresetSettingsCard`, `WallpaperDimmingSettingsCard`, and `IconPreviewInspectionSheet` moved their mode tabs into `headerBottomContent`.

## Verification

- `python3 scripts/checks.py i18n --run`: 2,029 base resources, 0 errors, 0 warnings.
- `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest`: passed.
- Installed Debug APK (`116000804`, `1.16.0+g15c86ea.b0804.dirty`) and matching AndroidTest APK in place on physical Xiaomi 17 Pro (`25098PN5AC`, `OS4.0.8.0.XBLCNXM`, launcher `801030308`); `com.miui.home`, `com.android.systemui`, and `system_server` PIDs remained unchanged.
- Device instrumentation on Xiaomi 17 Pro:
  - `ui-miuix-migration`: 21/21 passed (including `topBarBottomContentTabsTrackScrollStateWithoutSelfSampling`).
  - `ui-glass-interactions`: 24/24 passed (including `sheetHeaderActionsUseGlassToolbarSizingAndExposeGlassSurfaceContext`).
  - `ui-os4-controls`: 10/10 passed.
  - `ui-pack-file-import`: 3/3 passed.
  - `ui-status-sheets`: 14/14 passed.
  - `ui-os4-context`: 43/52 passed; all `Os4SearchInteractionTest`, `Os4SegmentedTabsDeviceTest`, `Os4InfoPopupDeviceTest`, `KeiHomeBackdropPipelineDeviceTest`, `CacheStorageUiTest`, and `PackGuidanceDeviceTest` cases passed. The 9 failures are pre-existing issues outside this change (`PreservedIconsUiTest`/`PreservedIconsScreenTest` expecting `ScrollToIndex` on `Os4LazyColumn`'s outer `Box` testTag, and `SettingsSearchUiTest.searchFindsAnOperableSettingAndShowsTheEmptyState`).
- Real-device visual captures confirmed resting vs scrolled `GlassTopAppBar` + `Os4ViewTabs` progressive blur on the Apps tab, sticky tabs in `IconPreviewInspectionSheet`, and translucent `GlassCard` containers in Monet's Seed Source sheet.

[comparison]: https://github.com/compose-miuix-ui/miuix/compare/588d029f2b0747a0a8710db0858ed0957d78b122...bc1024d78a154f5167efff262ae5ed66264c481e
[publish]: https://github.com/compose-miuix-ui/miuix/actions/runs/45773085805
