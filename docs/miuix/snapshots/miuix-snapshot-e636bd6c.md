# MIUIX e636bd6c snapshot

KeiHome now uses `0.9.5-e636bd6c-SNAPSHOT`, published from
`e636bd6c07f14d588fe084f0c3a5b57eafe20230` (upgraded from
`bc1024d78a154f5167efff262ae5ed66264c481e`; identical commit to Maven Central's
`v0.9.5-rc02`). Gradle resolved `debugRuntimeClasspath` to
`0.9.5-e636bd6c-SNAPSHOT:20261127.091800-1` across all MIUIX modules.
[Publish run][publish] · [Comparison][comparison]

| Upstream change | KeiHome integration |
| --- | --- |
| `e7c108ee` adds an iOS `.icon` asset in `example/` (`#499`). | Example-only change; no app code change required. |
| `f763b5aa` (`#500`) constrains popups and tooltips to window safe areas, defers inset/size reads to layout phase, aligns anchor reveal pivots, guards short dropdown viewports, and deduplicates `captionBar` in `DialogContentLayout`. | Adopted automatically by `OverlayIconCascadingDropdownMenu`, `Os4InfoPopup`, `Os4Dialog`, and `Os4DropdownPreference`. Existing KeiHome portal/focus wrappers carry no duplicate safe-area workaround and remain intact. |
| `ff58eff4` precomputes Vogel spiral offsets in `GlassShader`. | Adopted automatically across all Glass controls. |
| `357914e5` records default `LayerBackdrop` in a single pass when `backgroundColor` is passed instead of a custom `onDraw` lambda. | Updated `rememberKeiHomeBlurBackdrop()` in `MiuixSampleChrome.kt` from `rememberLayerBackdrop { drawRect(surfaceColor); drawContent() }` to `rememberLayerBackdrop(backgroundColor = MiuixTheme.colorScheme.surface)` so every blurred screen uses single-pass recording. |
| `9423408e` reuses the recorded content layer for `LayerBackdrop` `onDrawUnderlay` in `GlassTopAppBar` and `GlassBottomSheet`. | Adopted automatically through `KeiHomeTopAppBar` and `Os4DetailsSheet`. |
| `218b24ef` hoists wide glass tint sampling out of rim supersampling. | Adopted automatically across all Glass controls. |
| `e556b713` scales `GlassTransformPopup` close rebound with predictive back progress. | Adopted automatically by `Os4InfoPopup` and `OverlayIconCascadingDropdownMenu`. |
| `13324a08` skips redundant offscreen mask passes for rounded-rect Glass shapes, caches `GlassShadow` offscreen, and adds `BackdropPipeline.requiresOffscreenCompositing(size, density)`. | Glass controls benefit automatically. `KeiHomeBackdropPipeline` (Gaussian bar blur without `drawFront` masking) now overrides `requiresOffscreenCompositing` to `false` so its host layer stays on `CompositingStrategy.Auto`. |
| `e636bd6c` refreshes Android baseline and startup profiles. | Consumed automatically from the published Android AARs. |

## Variable / mutable navigation icons audit

Audited `GlassNavigationBar.kt`, `GlassNavigationRail.kt`, `NavigationBar.kt`,
`GlassTabRow.kt`, `Dropdown.kt`, `Icon.kt`, and `miuix-glass-icons` at
`e636bd6c`. As of `e636bd6c` (`v0.9.5-rc02`), neither `GlassNavigationItem` nor
`NavigationItem` provides a `selectedIcon` or mutable/animated icon slot yet,
and `GlassNavigationBar` keys item slots on the full `GlassNavigationItem` data
class (`key(item)`), so mutating `item.icon` on selection change would recreate
item state and reset tint/press animations. KeiHome keeps stable
`GlassNavigationItem` instances until an upstream variable/selected-icon API
lands.

## Companion dependency updates

- Android Gradle Plugin & `zipflinger`: `9.5.1` → `9.5.3` (`gradle/libs.versions.toml` and `build-logic/build.gradle.kts`)
- Kotlin / Compose Compiler / Serialization: `2.5.0-RC` → `2.5.0-RC2`
- AndroidX Compose BOM: `2026.09.00` → `2026.11.01`, aligned with stable Compose `1.13.0` (`1.13.0-rc01` → `1.13.0`)
- `org.commonmark:commonmark`: `0.32.0` → `0.33.0`
- `com.materialkolor:material-color-utilities`: `6.0.0` → `6.1.0`

## Verification

- `./gradlew :app:dependencyInsight --configuration debugRuntimeClasspath --dependency top.yukonga.miuix.kmp`: resolved every MIUIX artifact to `0.9.5-e636bd6c-SNAPSHOT:20261127.091800-1`.
- `./gradlew :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin`: passed.
- Release build, in-place deployment, and visual verification on Xiaomi 17 Pro (`25098PN5AC`, `OS4.0.8.0.XBLCNXM`) recorded in the delivery summary.

[comparison]: https://github.com/compose-miuix-ui/miuix/compare/bc1024d78a154f5167efff262ae5ed66264c481e...e636bd6c07f14d588fe084f0c3a5b57eafe20230
[publish]: https://github.com/compose-miuix-ui/miuix/actions/runs/45972356205
