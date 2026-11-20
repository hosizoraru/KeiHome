# MIUIX f3829d3d Snapshot

KeiHome moved from `0.9.5-66248bac-SNAPSHOT` to `0.9.5-f3829d3d-SNAPSHOT`.
The [upstream comparison][comparison] covers three commits. AndroidX Compose
remains on `1.13.0-rc01`.

| Commit | KeiHome impact |
| --- | --- |
| `b31c60f` | The explicit `GlassTopAppBar` overload activates material during negative overscroll. The shared KeiHome toolbar already uses the scroll-behavior overload, which handles this case. |
| `c16dfa1` | The Glass example resets its scroll position when the tab placement changes. KeiHome retains scroll through layout changes. |
| `f3829d3` | Native `TabRow` and `NavDisplay` defer size reads and the collapsed `GlassNavigationRail` recenters after a density change. KeiHome already measures rail-driven content in layout. Its adaptive navigation and segmented tab wrapper now convert pixel constraints with the current Compose density when the app scale changes. |

The range adds no standalone Glass component. The app's segmented tabs still
own their long-label and shadow allowances. The new scale editor uses the
shared dialog and state-based `GlassTextField` from the previous migration.

## App display settings

- Smooth corners default to on, matching MIUIX's existing
  `LocalSquircleEnabled` default. The shader smooths the curvature of supported
  controls. Turning it off uses the ordinary corner shape. Devices without
  runtime shader support show the unavailable state.
- Interface scale defaults to 100% with an 80–110% range and markers at 80, 90,
  100 and 110. Save applies the slider or typed value at once. Compose density
  changes within KeiHome and retains the system font scale.
- The existing blur preference keeps its progressive default and the user's
  saved choice. Its label covers the navigation, top bars and overlays that
  consume it.
- Scroll end haptics default to on. The shared page scroll path uses MIUIX's
  `scrollEndHaptic()` with the native overscroll modifier. Existing pages that
  disable overscroll and independent modal content keep their scroll behavior.

## Publication

The [Example workflow][example] and the [SNAPSHOT workflow][publish] cancel
older runs for the same ref. The Example workflow sends its Telegram post
after the build jobs succeed. Check the latest published Android modules
together because a running publication can expose only part of the module set.
This run exposed `miuix-ui-android` before the `miuix-ui` root metadata used by
Glass and Preference. Gradle's Android runtime classpath check caught the gap
and resolved after both coordinates were published.

## Settings verification

The signed `113000643` build on MIUIX `66248bac` was tested on the Xiaomi
17 Pro with HyperOS `OS4.0.8.0.XBLCNXM`. The scale editor applied 80%, 110%
and a typed 93%, kept 93% after leaving and reopening App settings, rejected
an out-of-range draft, and supported cancellation and reset to 100%. The
title bounds changed with the selected scale. Smooth corners and progressive
blur were toggled and restored. The vibrator service recorded touch feedback
for KeiHome with scroll end haptics enabled and no new KeiHome event after
the same swipe sequence with the setting disabled.

The adaptive layout regression covers a fixed 760-pixel-wide fixture changing
between compact and wide layouts when its Compose density changes. The
Android test compiled with both snapshots. Runtime coverage on a wide Pad
window is pending.

The signed `113000644` build includes the adaptive layout changes. It
confirmed the restored settings and found Smooth corners in the Chinese
settings search with the `Squircle` alias. APK readback matched the signed
artifact. Eleven observed Java reloads succeeded and the launcher, SystemUI,
and system server retained their original process identities.

On `113000645`, the scale editor scrolled far enough to reach Reset in
landscape. The numeric keyboard opened on the input, and after dismissing
it the Reset action returned to App settings at 100%. The original phone
rotation and foreground Example app were restored.

The final `113000646` KeiOS-signed Release resolved all ten Android AARs
from `0.9.5-f3829d3d-SNAPSHOT`. Debug and Android test Kotlin compiled.
JVM tests reported 2,100 passes and eight skips, and Release Lint had zero
errors. On the same phone, the installed APK hash matched the archived
artifact. Ten observed Java reloads succeeded. The final build showed 100%,
smooth corners on, progressive blur and scroll end haptics on. Repeating
the swipe sequence recorded four KeiHome touch feedback events when enabled
and zero when disabled. The landscape editor again supported numeric input
focus, scrolling and reset. Phone rotation, icon theme, night mode, palette,
critical process identities and the foreground Example app were restored or
verified against the baseline.

[comparison]: https://github.com/compose-miuix-ui/miuix/compare/66248bacc7be29ae6bdc0957de9458b42ac67732...f3829d3d162eb267a792d43b3c8541e64effc28d
[example]: https://github.com/compose-miuix-ui/miuix/blob/f3829d3d162eb267a792d43b3c8541e64effc28d/.github/workflows/example.yml
[publish]: https://github.com/compose-miuix-ui/miuix/blob/f3829d3d162eb267a792d43b3c8541e64effc28d/.github/workflows/publish.yml
