# Monet customization

KeiHome offers seven color recipes for themed launcher icons: Tonal Spot, Vibrant,
Neutral, Monochrome, Expressive, Rainbow and Fruit Salad. Customization adds an
arbitrary seed color, independent overrides for five tonal palettes, a choice of
any family for icon backgrounds and four exact icon role colors. The picker,
editor and details use MIUIX components; Material Color Utilities supplies color
calculations only. The scope is themed launcher icons: this does not write global RRO/SystemUI
theme overlays, change framework palettes or apply a theme to other apps.

`MonetSchemes.kt` uses the existing
`com.materialkolor:material-color-utilities:5.0.1` Kotlin port and the corresponding
`Scheme*` classes for all seven recipes. Base recipes explicitly use `SPEC_2021`,
`PHONE`, `isDark = false` and contrast level `0.0`; their tonal palettes are shared
by the light and dark icon roles. Custom families are generated directly with
`TonalPalette.fromHueAndChroma`, without running their colors through another
style recipe. This preserves the existing base recipe behavior and does not adopt
newer MCU defaults or Material 3 UI components.

Seed sources remain home wallpaper, lock wallpaper and basic color. Basic color
retains the six preset swatches and also opens `ui/main/MonetColorEditor.kt` for any
opaque seed. The editor offers MIUIX `ColorPalette` and `ColorPicker` modes plus
six-digit `#RRGGBB` input. Invalid/incomplete hex or a translucent picker color
cannot be confirmed. The data model also requires opaque seed and exact role
colors; eight-digit alpha input is not supported by this editor.

The hex field uses MIUIX's `TextFieldState` overload with an `InputTransformation`
that rejects non-hex input or more than six digits after an optional `#`. This
replaces the String/onValueChange round trip that could reorder burst input.
Confirmation reads the latest field text, while programmatic swatch updates are
tracked separately so they do not round-trip requested H/C through RGB. The dialog
has a bounded height and a weighted, scrollable editor body; reset, Cancel and
Use color remain outside that body so the keyboard can reduce the content area
without pushing the action buttons out of view.

Android recommends Rainbow and Fruit Salad for static colors. When either is
selected with a wallpaper source, the picker recommends basic colors without
silently changing the source or discarding the draft. Wallpaper extraction and
fallback behavior are unchanged; empty wallpaper results remain empty.

`MonetCustomColors.kt` defines the five independent families:

| Picker label | Palette key | Android resource family |
| --- | --- | --- |
| Primary / accent 1 | `primary` | `system_accent1` |
| Secondary / accent 2 | `secondary` | `system_accent2` |
| Tertiary / accent 3 | `tertiary` | `system_accent3` |
| Neutral 1 | `neutral` | `system_neutral1` |
| Neutral 2 | `neutral-variant` | `system_neutral2` |

Unedited families follow the selected seed and style. A family override stores a
`MonetTonalSpec(hue, chroma)`, with finite hue in `0..360` degrees and requested
chroma in `0..200`. Hue is circular; chroma is colorfulness, not an HSV saturation
percentage. These bounds are the editor/model input range, not a promise that
every requested color is available in sRGB. Tone is HCT lightness from black at
0 to white at 100 and is supplied by the ramp or role, not stored as a family
override.

Picking a family color or entering hex derives its H/C through `Hct.fromInt`.
The additional hue/chroma sliders retain the requested H/C values separately
from the displayed T50 sample. Confirming or reopening the editor therefore does
not replace a requested high chroma with the lower chroma of that gamut-mapped
sample. Each tone is generated from the requested H/C and can realize a different,
lower chroma where the display gamut requires it. There is no arbitrary RGB
override of individual ramp entries.

Picker movement and hex/H/C edits stay in editor-local state. `Use color` confirms
the value into the screen's saved-instance draft and updates its previews; it does
not submit a launcher action. Cancel/dismiss leaves the draft unchanged. For a
family or role, `Follow palette` explicitly removes that override from the draft
and closes the editor. The background-family dropdown also changes only the draft.
`Restore saved selection` clears the draft and returns to the reported saved
choice. Only the separate Apply action submits the choice through the existing
acknowledgement flow.

`MainScreen.kt` hosts the Status, Monet and Settings forms in regular scrollable
`Column`s under the per-tab `SaveableStateProvider`. This removes the single-item
`LazyColumn`'s nested saveable owner that caused draft and expansion state to be
lost when a tab was disposed. Apps retains its lazy list. The historical
validation below does not cover this state-ownership fix.

`MonetCustomization.kt` retains the original four-field wire form
`source:style:colorIndex:color` when there are no palette overrides, explicit
background-family selection or exact role overrides. An arbitrary basic seed alone
still uses this original form. Advanced choices append a versioned extension:

```text
source:style:colorIndex:color:v2:familyOverrides:backgroundFamily:iconOverrides
```

`familyOverrides` contains semicolon-separated `FAMILY=hue,chroma` entries;
`iconOverrides` contains semicolon-separated `ROLE=argbInt` entries. Names use the
family/role enum names, and ARGB values are decimal Kotlin `Int` values. The
background field, `fields[6]` (the seventh colon-separated field), is `AUTO` or one
of `PRIMARY`, `SECONDARY`, `TERTIARY`, `NEUTRAL` and `NEUTRAL_VARIANT`. `AUTO` maps
to `backgroundFamily = null`, retaining the AOSP role defaults. Either map field
may be empty. The decoder accepts both the original four fields and the
eight-field `v2` form; legacy choices get empty overrides and a null background
family. It also accepts the earlier experimental boolean field: `true` maps to
`NEUTRAL`, and `false` maps to `AUTO`/null. New `v2` encoding writes `AUTO` or the
family name, never a boolean. Existing saved choices need no rewrite. This is
backward-compatible reading by the new code, not a promise that older
four-field-only readers understand advanced choices. Decoding returns null on
parsing or model validation failure, including non-finite/out-of-range H/C and
non-opaque stored colors.

Selection details show the seed, the exact light/dark icon role colors, and a
family picker for Primary, Secondary, Tertiary, Neutral and Neutral variant. Each
family shows Android's 13 reference HCT tones:
`0, 10, 20, 30, 40, 50, 60, 70, 80, 90, 95, 99, 100`. These are HCT tones, not the
Android resource suffixes `0..1000`. Generation still supplies all 101 tones per
family (505 colors) for the existing runtime. Android resource suffixes map to HCT
tone as `100 - suffix / 10`, so `_700` means T30 and `_100` means T90. Tap a role,
seed or tone to copy its hex value. Opaque values use `#RRGGBB`; the generic detail
formatter retains `#AARRGGBB` for non-opaque reported values. Copy actions have
localized accessibility labels and Android clipboard feedback.

The default icon roles match the supplied-monochrome-layer path verified in AOSP
`android-17.0.0_r1`. The MIUIX dropdown offers those automatic defaults plus all
five palette families. `backgroundFamily: MonetPaletteFamily?` is null for AUTO;
an explicit selection uses that family for both backgrounds while retaining T90
in light mode and T20 in dark mode:

| Icon mode | Foreground | AUTO background | Selected-family background |
| --- | --- | --- | --- |
| Light | `primary30` / `system_accent1_700` | `primary90` / `system_accent1_100` | Selected family at T90 |
| Dark | `primary80` / `system_accent1_200` | `secondary20` / `system_accent2_800` | Selected family at T20 |

For example, choosing Tertiary resolves `tertiary90` and `tertiary20`; choosing
Neutral variant resolves `neutral-variant90` and `neutral-variant20`. The dropdown
does not change the foreground family or the tonal ramps.

`LIGHT_FOREGROUND`, `LIGHT_BACKGROUND`, `DARK_FOREGROUND` and `DARK_BACKGROUND`
can each have an exact opaque ARGB override. Resolution is exact role override
first, then the selected family for an unoverridden background,
then the default family/tone. These four values are separate from the tonal ramps:
an exact color is used as entered, is not resampled at the role's default tone and
does not rewrite any of the 505 palette entries. Both preview and runtime use
`MonetIconRole.color` for this precedence. Detail labels name an overridden role
without implying that its exact RGB value still has the default HCT tone.

The five families are not five icon layers. Primary supplies the default
foregrounds and light background; Secondary supplies the default dark background.
Selecting any family, including Tertiary or Neutral variant, makes its T90/T20
colors available to the launcher background roles unless exact background colors
take priority. A family that no resolved icon role uses affects palette previews
and details rather than launcher icon colors; Neutral also colors the preview
surroundings and labels. An exact role color is a stored color, not a live
reference to a family. The monochrome layer supplies glyph coverage; palette/role
editing does not replace that artwork or the launcher's outer icon mask. The
Monochrome recipe and an icon's monochrome layer are separate concepts.

The preview's contrast readout uses the actual quantized sRGB role colors:
`Contrast.ratioOfYs(ColorUtils.xyzFromArgb(foreground)[1], …background[1])`.
It does not calculate contrast from nominal HCT tones, infer a score from the
selected style, or claim whole-screen accessibility. Missing or translucent
role colors have no contrast readout. Even low contrast is reported as measured;
Apply is disabled until both light and dark pairs reach at least 4.5:1 and the
action is available. Runtime `MonetColorPolicy.selectPair` separately validates
the current mode's resolved pair for opacity and the same 4.5:1 threshold; it does
not invent missing colors or silently adjust exact overrides. Native acknowledgement
is still required before persisting the applied selection. The 4.5:1 icon threshold
is KeiHome's policy; Android's general guidance is 3:1 for non-text graphics and
4.5:1 for ordinary text. Wallpaper compositing, partially transparent mask details,
icon geometry and renderer behavior still require device validation. A global
contrast slider is absent: palette sampling and exact icon overrides do not
implement Pixel's system-wide UI contrast control.

The selected palette and reported device palette are explicitly separated. The
former is a preview, not proof of successful application or of system-wide use.
Reported `SPRITZ` maps to the Neutral label, matching AOSP's style-to-scheme
mapping.

Base recipe previews are generated by `produceState` on `Dispatchers.Default`,
keyed by seed with cancellation checks between styles. The customized palette is
generated separately with a key containing seed, style and the complete family
override map. Both results are checked against their current keys before use;
the palette reader also caches by seed, style and family overrides. Exact role
edits and the background-family dropdown only resolve colors from an existing
palette and do not regenerate its ramps. Editor movement computes local swatches;
confirming a seed or family edit can trigger background palette generation for
the draft. Apply remains the boundary for the launcher action/IPC. The 13-tone
inspector is composed only while details are expanded.

Android 17 source availability and generator behavior were verified separately
from the app. The official release is `android-17.0.0_r1`, build
`CP2A.260605.016`, with release branch `android17-release`. Its SystemUI Monet
`ColorScheme` requests `SPEC_2026`; bundled `DynamicScheme` applies variant
fallback, including `SPEC_2025` for Tonal Spot, Vibrant, Expressive and Neutral,
and `SPEC_2021` for Rainbow, Fruit Salad and Monochrome. The five family names,
shade mapping and default icon roles are useful contracts, but KeiHome's pinned
`SPEC_2021` recipes are not a claim of Android 17 pixel-identical palette generation.
Neither Android 16 nor an unpinned `main` branch is used as Android 17 evidence.

Android 17 also has a flag-controlled synthesized-monochrome path with an
alternative background (`accent1` T50 in light mode, T20 in dark mode) and
foreground luminance adaptation. The supplied-mask previews and the role table
above do not reproduce that path or establish HyperOS rendering behavior.

Official sources checked on 2026-10-30:

- [AOSP dynamic color](https://source.android.com/docs/core/display/dynamic-color): five families, 13 reference tones, style definitions, and the static-color recommendation for Rainbow/Fruit Salad.
- [Android Developers: dynamic colors](https://developer.android.com/develop/ui/views/theming/dynamic-colors): seed-to-palette flow and wallpaper style variants.
- [Google Pixel: change wallpaper and Home screen styles](https://support.google.com/pixelphone/answer/7289143?hl=en): wallpaper palettes, other colors, preview and explicit Apply. This is UX context, not evidence of HyperOS support for every Pixel feature.
- [Android 17 release announcement](https://groups.google.com/g/android-building/c/hIvpHWs2HeI) and [official build/tag table](https://source.android.com/docs/setup/reference/build-numbers): release/tag identity.
- Android 17 r1 [ColorScheme](https://android.googlesource.com/platform/frameworks/libs/systemui/+/refs/tags/android-17.0.0_r1/monet/src/com/android/systemui/monet/ColorScheme.java), [DynamicScheme](https://android.googlesource.com/platform/external/libmonet/+/refs/tags/android-17.0.0_r1/dynamiccolor/DynamicScheme.java) and [ThemeOverlayController](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-17.0.0_r1/packages/SystemUI/src/com/android/systemui/theme/ThemeOverlayController.java): family mapping, `SPRITZ` to Neutral, requested specification/variant fallback and the system style allow-list.
- Android 17 r1 [TonalPalette](https://android.googlesource.com/platform/frameworks/libs/systemui/+/refs/tags/android-17.0.0_r1/monet/src/com/android/systemui/monet/TonalPalette.java): shade-to-tone conversion and 13 reference shades.
- Android 17 r1 iconloaderlib [light roles](https://android.googlesource.com/platform/frameworks/libs/systemui/+/refs/tags/android-17.0.0_r1/iconloaderlib/res/values-v31/colors.xml) and [dark roles](https://android.googlesource.com/platform/frameworks/libs/systemui/+/refs/tags/android-17.0.0_r1/iconloaderlib/res/values-night-v31/colors.xml), verified through their `?format=TEXT` responses.
- Android 17 r1 [MonoIconThemeController](https://android.googlesource.com/platform/frameworks/libs/systemui/+/refs/tags/android-17.0.0_r1/iconloaderlib/src/com/android/launcher3/icons/mono/MonoIconThemeController.kt) and [MonoThemedBitmap](https://android.googlesource.com/platform/frameworks/libs/systemui/+/refs/tags/android-17.0.0_r1/iconloaderlib/src/com/android/launcher3/icons/mono/MonoThemedBitmap.kt): alpha-mask rendering and the separate synthesized-icon adaptation path.
- Google MCU [Hct](https://github.com/material-foundation/material-color-utilities/blob/bd811e4ca2ab94c4ce3cadc89c19b1820b9704b3/java/hct/Hct.java) and [TonalPalette](https://github.com/material-foundation/material-color-utilities/blob/bd811e4ca2ab94c4ce3cadc89c19b1820b9704b3/java/palettes/TonalPalette.java), pinned to upstream commit `bd811e4ca2ab94c4ce3cadc89c19b1820b9704b3` (2026-10-19): HCT, requested versus realizable chroma and palette construction. These are upstream references; the app uses MaterialKolor 5.0.1.
- [Android adaptive icons](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive) and [accessibility guidance](https://developer.android.com/design/ui/mobile/guides/foundations/accessibility): monochrome artwork, launcher support and contrast guidance.

The following validation is historical, recorded on 2026-10-30 for the earlier
recipe picker and palette details, before the advanced color editor and `v2`
extension. It does not establish current custom-seed, H/C, background-family,
exact-role, input/state-retention or wire-format correctness. Current validation
is recorded separately below.

The earlier investigation inspected the cached AAR with `javap`, confirming the
Expressive, Rainbow and Fruit Salad constructors accepted `Hct`, `isDark`,
`contrastLevel`, `SpecVersion` and `Platform`. Local checks then passed without
Gradle: Kotlin 2.4.21 compiled the then-current Monet data/policy sources and both
test classes against cached dependencies; JUnit 4.13.2 passed all 17 tests. AAPT2
37.0.0 compiled the three resource XML files, and XML checks confirmed all 24 keys
and format placeholders matched across English, Simplified Chinese and Japanese.
All then-current Monet string references resolved in base resources. Those checks
did not compile or render the Compose screen.

That earlier implementation also passed integrated Release compilation, R8 and
required lint. On the HyperOS AVD, the Chinese and English picker/preview and the
expanded role details were visually checked. Japanese at font scale 1.3 exposed
awkward two-column wrapping; the picker was changed to one column for large text
or a narrow window, and the corrected Japanese layout was captured. Other widths
and a complete TalkBack session were not exercised.

During that earlier validation, the seed `#3F4AE2` was copied through the detail
card and pasted into KeiHome's search field, verifying the clipboard payload.
The search was cleared afterward. Expressive applied through the normal API 102
action with a matching receipt: light used `#005233` on `#A3F4C6` (7.24:1), dark
used `#87D7AB` on `#402843` (7.70:1). Settled desktop screenshots showed KeiHome
and both ErinAO icons in both variants; HyperPods kept its original color icon.
Opening details did not apply another scheme. The original wallpaper/Tonal Spot
selection was restored after that validation.

See [anr-investigation.md](anr-investigation.md) for exact installed versions,
hot reload, process continuity, test scope and the historical ANR limitations.

## Advanced color editor validation, 2026-10-30

Release `1793349722` passed compilation, R8 and required lint. Focused checks across
this change passed 41 distinct JVM tests: 8 custom-color, 4 customization-format,
13 scheme, 8 runtime color-policy and 8 status-dashboard tests. After the final
background-family change, the 20 directly affected custom/serialization/policy
tests were rerun. Debug device tests passed all 3 selected cases for exact HEX
commit, incomplete input/cancel, draft refresh/navigation retention and repeated
runtime-card expansion retention. The initial device run exposed the single-item
lazy container losing drafts; the corrected scrollable forms passed the replay.

Xiaomi IME altered raw `adb input text` letter sequences during investigation.
Committed-text instrumentation verified `087F8C` exactly, including confirm and
page/refresh retention. Release numeric HEX entry verified `116655`, `806666` and
`999999`. These results do not claim to repair the OEM input method. The editor's
state-based input, partial-value gate and keyboard-visible confirmation controls
were exercised independently of that raw-key injection behavior.

On `emulator-5580`, the Release UI applied seed `#116655`, a Neutral 2 H/C override
derived from `#806666`, Neutral 2 as the background family, and exact dark
foreground `#999999`. The normal API 102 action returned a matching persisted
receipt (`1793350381990`). The selected role pairs were:

| Mode | Foreground | Background | Measured contrast |
| --- | --- | --- | --- |
| Light | `#005142` | `#FCDBDA` | 7.22:1 |
| Dark | `#999999` | `#402B2B` | 4.61:1 |

Settled launcher screenshots showed KeiHome and both ErinAO variants using these
colors in both modes; HyperPods kept its original color icon. The Apps page's
Monet previews matched the selected roles. Per-family and per-role reset, AUTO
background selection and restoring the original home-wallpaper/Tonal Spot choice
were exercised through the app's Apply flow. The original saved selection,
system-language following, dark mode and test AppOp were restored afterward.

Every APK installation increased versionCode and verified API 102 Java reload.
No native payload change or development process restart was needed for this task.
The final deployment, color application, theme cycle and restoration retained
launcher PID/start `8576/146965`, system_server `8814/2705` and SystemUI
`9673/3500`. The bounded validation log contained no matched fatal, ANR, watchdog
kill or SCOUT hang after its start marker. This UI/color check is not a closure
of the historical mixed-load ANR investigation.

The collapsed status dashboard was visually checked in English, Simplified
Chinese and Japanese on the current AVD size. It showed framework-provided
`LSPosed 2.2.1-log (7936)` separately from API 102, and all four cards fit on one
screen. An unreported launcher target remains unreported rather than inferred
from successful color rendering. No full TalkBack or arbitrary-font-scale matrix
was repeated in this change.

Evidence and the Release artifact are in
`/Users/voyager/.codex/visualizations/2026/10/30/keihome-custom-colors/`.
