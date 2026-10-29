# Monet customization

KeiHome offers seven color recipes for themed launcher icons: Tonal Spot, Vibrant,
Neutral, Monochrome, Expressive, Rainbow and Fruit Salad. The picker and details use
MIUIX components; Material Color Utilities supplies color calculations only. The
existing screen chrome, explicit Apply action, acknowledgement states and saved
draft format are retained.

Expressive, Rainbow and Fruit Salad use the actual `SchemeExpressive`,
`SchemeRainbow` and `SchemeFruitSalad` classes in the already configured
`com.materialkolor:material-color-utilities:5.0.1` Kotlin port. Their cached Android
AAR was inspected with `javap`: all three accept `Hct`, `isDark`, `contrastLevel`,
`SpecVersion` and `Platform`. Every style stays on `SPEC_2021`, `PHONE`, contrast
level `0.0`, preserving the existing palette recipe and fixed icon-role mapping.
This does not adopt newer MCU specification defaults or Material 3 UI components.

Android recommends Rainbow and Fruit Salad for static colors. When either is
selected with a wallpaper source, the picker recommends basic colors without
silently changing the source or discarding the draft. The fixed-color list and
wallpaper extraction/fallback behavior are unchanged. Empty wallpaper results
remain empty. The enum keeps its previous entries and adds named values; the
`source:style:colorIndex:color` storage format needs no migration.

Selection details show the seed, the exact light/dark icon role colors, and a
family picker for Primary, Secondary, Tertiary, Neutral and Neutral variant. Each
family shows Android's 13 reference HCT tones:
`0, 10, 20, 30, 40, 50, 60, 70, 80, 90, 95, 99, 100`. These are HCT tones, not the
Android resource suffixes `0..1000`. Generation still supplies all 101 tones per
family (505 colors) for the existing runtime. Tap a role, seed or tone to copy its
hex value. Opaque values use `#RRGGBB`; a non-opaque seed retains `#AARRGGBB`.
Copy actions have localized accessibility labels and Android clipboard feedback.

| Icon mode | Foreground | Background |
| --- | --- | --- |
| Light | `primary30` / `system_accent1_700` | `primary90` / `system_accent1_100` |
| Dark | `primary80` / `system_accent1_200` | `secondary20` / `system_accent2_800` |

The preview's contrast readout uses the actual quantized sRGB role colors:
`Contrast.ratioOfYs(ColorUtils.xyzFromArgb(foreground)[1], …background[1])`.
It does not calculate contrast from nominal HCT tones, infer a score from the
selected style, or claim whole-screen accessibility. Missing or translucent
role colors have no contrast readout. Even low contrast is reported as measured;
the existing runtime acceptance policy remains responsible for accepting a pair.
Wallpaper compositing, masks, icon geometry and renderer behavior require device
validation. A contrast slider is intentionally absent: these fixed icon tones
would not implement Pixel's system-wide UI contrast control.

The selected palette and reported device palette are explicitly separated. The
former is a preview, not proof of successful application. This work does not
write SystemUI overlays, change framework palettes, or claim that other apps or
system surfaces use the selected scheme. Reported `SPRITZ` maps to the Neutral
label, matching AOSP's style-to-scheme mapping.

Generation remains keyed by seed in `produceState` on `Dispatchers.Default`,
with cancellation checks between styles and the existing seed check preventing
an old result from appearing under a new selection. Style changes reuse generated
palettes. Role calculations are remembered by palette and mode. The 13-tone
inspector is composed only while details are expanded. No wallpaper reads, IPC,
or global palette generation were added to composition or click handlers.

Official sources checked on 2026-10-30:

- [AOSP dynamic color](https://source.android.com/docs/core/display/dynamic-color): five families, 13 reference tones, style definitions, and the static-color recommendation for Rainbow/Fruit Salad.
- [Android Developers: dynamic colors](https://developer.android.com/develop/ui/views/theming/dynamic-colors): seed-to-palette flow and wallpaper style variants.
- [Google Pixel: change wallpaper and Home screen styles](https://support.google.com/pixelphone/answer/7289143?hl=en): wallpaper palettes, other colors, preview and explicit Apply. This is UX context, not evidence of HyperOS support for every Pixel feature.
- [AOSP ThemeOverlayController](https://android.googlesource.com/platform/frameworks/base/+/64eaba03c5db8b431aef8a88d40593631951fe99/packages/SystemUI/src/com/android/systemui/theme/ThemeOverlayController.java): mapping from `SPRITZ` to `SchemeNeutral` and the seven relevant variants.
- Google Material Color Utilities: [Expressive](https://github.com/material-foundation/material-color-utilities/blob/main/java/scheme/SchemeExpressive.java), [Rainbow](https://github.com/material-foundation/material-color-utilities/blob/main/java/scheme/SchemeRainbow.java), [Fruit Salad](https://github.com/material-foundation/material-color-utilities/blob/main/java/scheme/SchemeFruitSalad.java), and [Contrast](https://github.com/material-foundation/material-color-utilities/blob/main/java/contrast/Contrast.java). The app uses the existing Kotlin port, not these Java packages directly.
- AOSP Android 16 iconloaderlib [light roles](https://android.googlesource.com/platform/frameworks/libs/systemui/+/refs/tags/android-16.0.0_r1/iconloaderlib/res/values-v31/colors.xml) and [dark roles](https://android.googlesource.com/platform/frameworks/libs/systemui/+/refs/tags/android-16.0.0_r1/iconloaderlib/res/values-night-v31/colors.xml), verified through their `?format=TEXT` responses.

Local checks on 2026-10-30 passed without Gradle: Kotlin 2.4.21 compiled the actual
Monet data/policy sources and both test classes against the cached dependencies;
JUnit 4.13.2 passed all 17 tests. AAPT2 37.0.0 compiled the three new resource XML
files, and XML checks confirmed all 24 keys and format placeholders match across
English, Simplified Chinese and Japanese. All Monet string references resolve in
base resources. These checks do not compile or render the Compose screen.

Integrated Release compilation, R8 and required lint passed. On the HyperOS AVD,
the Chinese and English picker/preview and the expanded role details were visually
checked. Japanese at font scale 1.3 exposed awkward two-column wrapping; the
picker now uses one column for large text or a narrow window. The corrected
Japanese layout was captured. Other widths and a complete TalkBack session were
not exercised; copy actions carry localized accessibility labels.

The seed `#3F4AE2` was copied through the detail card and pasted into KeiHome's
search field, verifying the clipboard payload. The search was cleared afterward.
Expressive applied through the normal API 102 action with a matching receipt:
light uses `#005233` on `#A3F4C6` (7.24:1), dark uses `#87D7AB` on `#402843`
(7.70:1). Settled desktop screenshots showed KeiHome and both ErinAO icons in
both variants; HyperPods kept its original color icon. Opening details did not
apply another scheme. The original wallpaper/Tonal Spot selection is restored
after validation.

See [anr-investigation.md](anr-investigation.md) for exact installed versions,
hot reload, process continuity, test scope and the historical ANR limitations.
