# KeiHome icon

**A home for icons** combines an open home frame with two app tiles. The raised
tile suggests arranging and customizing a home screen. Home and app icons are
the identity; there is no letter monogram.

## Editable assets

- `keihome-icon.svg`: master, three filled vector paths on transparency.
- `exports/keihome-light.svg` and `keihome-dark.svg`: complete adaptive layers
  with the corresponding background.
- `exports/keihome-monochrome.svg`: transparent, single-color master silhouette.
- `preview.svg` / `preview.png`: masks, day/night and illustrative Monet colors,
  including 48px and 24px samples.
- `exploration.md`: image-generation concepts, selected reference and prompt.

Run `python3 scripts/export-brand-icon.py` after changing the SVG geometry.
It exports both Android VectorDrawables and the SVG previews from those same
paths. The regular palette comes from `values/colors.xml`; the dark palette
comes from `values-night/colors.xml`. The exporter checks that the master and
Android light colors agree.

## Android adaptation

Following [Android's adaptive-icon guidance](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive):

- Each layer is 108 by 108 dp. The mark fits inside the centered 66dp safe area.
- Background is full bleed. There is no baked-in outer mask or shadow.
- `ic_launcher` and `ic_launcher_round` both declare foreground, background and
  monochrome layers; the system chooses the icon mask.
- Light and dark versions keep identical geometry with different resource colors.
- The monochrome layer contains opaque white geometry on transparency, with no
  colored backdrop. The launcher supplies the actual Monet foreground/background.

The preview uses the visible 72dp adaptive crop; displaying the entire 108dp
layer as an icon would make the mark look smaller than it does on a launcher.
Preview Monet colors are examples, not hardcoded runtime colors.

Raster preview regeneration, using the local Homebrew Cairo library on macOS:

```sh
DYLD_FALLBACK_LIBRARY_PATH=/opt/homebrew/lib uv run --with cairosvg python -c \
  'import cairosvg; cairosvg.svg2png(url="docs/branding/preview.svg", write_to="docs/branding/preview.png")'
```

## Verification - 2026-11-01

- Release `1793454010`: resource compilation, R8 and release lint passed.
- SVG and Android foreground/monochrome use the same three paths. At a 10x
  raster inspection, painted bounds were approximately 57.9 by 52.7dp; the
  furthest opaque sample was 32.84dp from the center, inside the 33dp safe circle.
- Inspected squircle, circle and rounded-square previews, including 24px.
- HyperOS Phone AVD `emulator-5580`: the app's resource preview displayed the new
  light, dark, Monet-light and Monet-dark artwork. The launcher displayed the
  new regular and themed artwork.
- Package replacement initially left the launcher's old monochrome cache visible.
  Disabling and re-enabling themed icons through the existing system setting
  made it read the new artwork. This is a validation workaround, not a cache
  invalidation fix in this icon-only change.
- API 102 Java hot reload passed. Launcher PID/start ticks remained
  `29116 / 2930413`; SystemUI and system-server identities also remained unchanged
  during installation and the observed switches.

Release APK and device evidence are retained outside Git at
`/Users/voyager/.codex/visualizations/2026/11/01/keihome-icon/`.
