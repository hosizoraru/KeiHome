# MIUIX a09d4f32 Snapshot

KeiHome moves from `0.9.5-07d48590-SNAPSHOT` to
`0.9.5-a09d4f32-SNAPSHOT`. The ten upstream commits in this range cover
Glass bottom-sheet header blur, sheet and dialog motion, top-bar overhang,
predictive Back state, and dynamic-color contrast. The
[snapshot publication][publish] completed successfully, and Gradle resolves
all Android MIUIX modules to the new snapshot.

| Upstream change | KeiHome integration |
| --- | --- |
| `c262ff7` adds the progressive-blur header and header-bottom content contract to `GlassBottomSheet`. | `Os4DetailsSheet` now applies the `PaddingValues` supplied by the sheet to its scroll container. This keeps the first detail row below the floating header while preserving the existing scroll, navigation-bar padding and dismissal sequencing. |
| `3c56086`, `03d1578`, `b5d2d69` and `9131b24` refine overlay touch release, closing rebound, predictive Back state and dropdown hold-state cleanup. | Existing `Os4Dialog`, `Os4DetailsSheet`, anchored menus and toolbar actions inherit the upstream behavior. App-owned `onDismissFinished` callbacks remain the boundary for follow-up actions. |
| `ae3be93` reduces the default `GlassTopAppBar` band overhang. | Shared KeiHome top bars use the upstream default and keep their existing adaptive action spacing. No local overhang compensation is needed. |
| `0f2f05a` extends the bottom-sheet surface during upward overscroll. | Details and guidance sheets use the updated surface through `GlassBottomSheet`; their existing content height and navigation-bar handling remain app-owned. |
| `a09d4f3` tunes dynamic-light, static-light and dark contrast for `GlassCard`, `GlassDialogButton` and `GlassTextField`. | KeiHome uses the upstream defaults across settings, dialogs, pack guidance and editor surfaces. No duplicate local color override is retained. |

## Verification

- `:app:compileDebugKotlin` succeeded after the dependency and sheet-content
  migration. Existing compiler warnings remain limited to the pre-existing
  unnecessary safe calls in `ApplePackScreen.kt`.
- `:app:dependencyInsight --dependency top.yukonga.miuix.kmp
  --configuration debugRuntimeClasspath` resolved `miuix-blur`,
  `miuix-core`, `miuix-glass`, `miuix-glass-icons`, `miuix-icons`,
  `miuix-material`, `miuix-nav`, `miuix-preference`, `miuix-squircle` and
  `miuix-ui` to `0.9.5-a09d4f32-SNAPSHOT`, publication
  `20261124.162013-1`.
- `:app:testDebugUnitTest` passed. `:app:assembleDebug` and
  `:app:assembleDebugAndroidTest` both succeeded.
- On the authorized Xiaomi 17 Pro physical device
  (`25098PN5AC`, `OS4.0.8.0.XBLCNXM`), the in-place Debug update and test APK
  installation succeeded. `ui-miuix-migration` passed all 20 tests, and
  `GlassInteractionsUiTest` passed all 6 sheet, tab, dismissal and Glass
  interaction tests.

The upstream commit comparison is available at
the [MIUIX comparison page][comparison].

[comparison]: https://github.com/compose-miuix-ui/miuix/compare/07d4859021d7aed1a1f78afaee12c5c8a6917466...a09d4f3268ece3fc262d0bba433a3941a312bb9a
[publish]: https://github.com/compose-miuix-ui/miuix/actions/runs/45434910324
