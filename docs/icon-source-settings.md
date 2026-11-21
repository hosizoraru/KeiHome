# Icon sources and optional color treatment

## Navigation

The MIUIX bottom bar is ordered Status, Apps, Icon packs, Monet, Settings.
Icon packs contains the current native component, the library entry and the
system-material preference. The library is a secondary page with three sources:

- Built-in styles: native HyperOS settings and locally generated ColorOS profiles.
- APK packs: installed packages and APKs copied through the document picker.
- MTZ/ZIP packs: imported native modules and classic appfilter archives.

Back navigation uses NavigationEvent. Source lists use lazy items; preview bitmaps
share the existing bounded cache. Candidate color schemes are generated off the UI
thread. Bundled profiles are regenerated into an app-version-specific cache and
do not consume the user's private imported-pack quota.

## Source policy

| Source | Ordinary artwork | Optional Monet |
| --- | --- | --- |
| HyperOS Default/Classic/Boundless | Native theme ownership and night resources | Global Monet control |
| ColorOS Classic/Daylight/Night Shadow/Material/Pebble | ART+ when available, original-activity fallback otherwise | Disabled on applying the ordinary profile; no palette template is exported |
| ColorOS Inspiration | Classic source masks, original artwork retained for off | Explicit enable switch and separate foreground/background colors |
| APK packs | Package resource configurations, including light/night | Only a declared Android adaptive-icon monochrome layer |
| MTZ/native icons ZIP | Original module retained intact | Whatever the original module implements; no arbitrary third-party script rewriting |

No full-color image is converted to a monochrome silhouette merely because it is
in a pack. Unusable masks retain ordinary artwork. Unsupported dynamic resources
retain the native icon instead of receiving a frozen date/time snapshot.

Applying a normal ColorOS profile explicitly requests Monet off. Imported native
component IDs retain this capability. The Monet page prevents enabling/applying
a palette while that ordinary component is active. An Inspiration import does
not enable Monet before the user has applied it. Switching Monet off preserves
the stored color choice; refreshing or returning to a page does not submit an
enable request.

Configured ColorOS imports have identities containing style, appearance and
source-asset ID. Inspiration also has its own native title. A result for Classic,
or a different appearance, cannot become the Apply button for Inspiration.

## Color behavior

ColorOS source `isDarkModeEnable()` is restricted to the Classic position. Its
0xD6 multiplier must not be indiscriminately applied to every other style.
Original application resources are still resolved separately for light/night.
An explicit night-dim appearance applies the requested 214/255 treatment.

The four appearance choices affect source selection and the published palette:
follow system, always light, always dark, and dim the light artwork at night.
Forced-appearance choices persist in the backward-compatible v3 customization
encoding; v1/v2 values remain readable.

ColorOS Inspiration is an independently implemented two-tone interpretation,
not a claim of a byte-identical port of the proprietary color engine. HCT tonal
palettes use light foreground/background tones 40/95 and dark 80/20 from the
primary family. Explicit role/family overrides still take precedence. The normal
Pixel/AOSP selection retains its existing roles.

Sources include home wallpaper, lock wallpaper, system colors and basic/custom
colors. System-follow reads the actual framework palette on each synchronization;
it does not save a one-time wallpaper-derived color under a system label.
Wallpaper candidates have paired foreground/background swatches. The existing
MIUIX editor continues to expose tonal-family and explicit icon-role overrides.

## Local built-in assets

Run the following with the inspected user-owned firmware inputs:

```bash
uv run --with pillow --with resvg-py python scripts/prepare-coloros-builtins.py
```

Ten standalone appfilter ZIPs (five styles, ART+ on/off) are generated under
`.artifacts/generated/assets/builtin-icon-packs`. The optional Gradle asset source
includes them in local APKs. OEM inputs and generated artwork remain ignored by
Git. A build without these inputs shows the missing-resource message in the
ColorOS settings, and ColorOS generation cannot proceed.

For a worktree build, pass
`-Pkeihome.generatedAssetsDir=/absolute/path/to/.artifacts/generated/assets`
to use the generated inputs from the main checkout. An explicit directory must
contain ZIP profiles under `builtin-icon-packs/`. For visual QA, check that the
APK contains `assets/builtin-icon-store/catalog.json` and `objects.kicons`
before installing.

The November 22 ColorOS preview report was traced to the optional asset
input. Archived `b0660` included the store, while the isolated-worktree builds
`b0661`, `b0662`, `b0663`, and `b0694` omitted it. Both the style thumbnails
and the selected style's icon preview depend on that store. Those builds also
omitted the generated `system-icon-fallback/` assets that supply the Classic
and Boundless fallback choices. The signed `b0695` included both asset groups.
On Xiaomi 17 Pro, the ColorOS page displayed its style thumbnails and the
selected Classic and Daylight light/dark previews.

The profiles retain per-file provenance and verified resource masks. Material
uses a standard radius-8 rectangle in a 150-unit viewport; the vendor's smooth/G2
curve is not reproduced. Pebble uses its extracted resource path. Other custom
sizes, G2 geometry and arbitrary OEM fancy animations remain outside this port.
Only the existing Classic clock/calendar contracts have host-owned live
templates; other unsupported time artwork remains native.

## Focused verification

- Converter suite: 29 tests.
- Monet request/codec/color-policy/custom-color/source-policy JVM checks.
- Release Kotlin, R8 and required lint.
- HyperOS-only installation with strictly increasing versionCode and API 102
  reload; no native payload replacement.
- Real Monet off request: both `key_monochrome` and `keihome_maml_enabled` became
  zero. Refresh retained zero.
- Native Classic import returned `IMPORTED`, `convertedReady=true`; the applied
  `/data/system/theme/icons` SHA-256 matched the conversion receipt.
- Native Inspiration import and application also matched the conversion receipt.
  Importing left Monet disabled; the explicit switch subsequently enabled both
  the native and generated-MAML palette paths.
- System-follow preview no longer assumes that the framework exposes tones 12
  and 96. A focused sparse-palette regression check passed. Preview surfaces use
  the nearest reported neutral tone; actual icon roles still require their
  declared tones.
- Release `1793565758` applied and retained
  `SYSTEM_COLOR:COLOROS_INSPIRATION:0:-1611228`. Turning Monet off set both switches
  to zero; refreshing retained zero and the saved customization. Re-enabling
  restored both switches to one. The final launcher screenshot shows two-tone
  supported icons alongside original artwork and the live clock/calendar.
- Launcher PID/start `9715/3611`, SystemUI `9696/3609`, and system_server
  `8812/2684` remained unchanged through the final APK update, API 102 reload and
  focused off/refresh/on sequence.

Device evidence is retained under `.artifacts/research/coloros/settings-*`.
These UI/source checks do not close the historical watchdog/SystemUI/Flutter
image-lifetime investigation. Native application was exercised with Classic and
Inspiration; the other three ordinary styles have generated assets and converter
coverage, but have not each received the same end-to-end device acceptance.
