# Monet and cross-surface layer geometry

The November 17 audit follows native artwork through preview, optional generic
Monet generation, exported palette templates, system fallback, notification and
island snapshots, and startup completion.

| Input | Geometry |
| --- | --- |
| Native static `0.png` / `1.png` textures | Framework adaptive child bounds, once |
| APK adaptive monochrome child | Framework adaptive child bounds, once |
| ColorOS supplied monochrome PNG | Raw adaptive child bounds, once, through the APK monochrome renderer |
| Completed palette foreground/background masks | Final output bounds |
| Declared quiet snapshots, including per-layer quiet images | Authored snapshot bounds |
| Completed splash artwork inside an adaptive wrapper | Keep the existing compensating inset |
| ColorOS ordinary adaptive composition | Preserve the verified OEM viewport |

`NativeLayerViewport` obtains the actual child bounds from
`AdaptiveIconDrawable`. It is shared by native preview, Perfect fallback,
optional static-night composition, APK monochrome extraction, generic Monet
derivation from native aliases, and cross-surface native snapshots. Handwritten
adaptive bounds remain only in the distinct ColorOS ordinary-icon contract.

## Native aliases during generic coloring

The supplied PerfectIconsPacker MTZ contains 2,443 complete native pairs and 88
flat files; 67 names have both. A flat alias is not necessarily the picture the
native launcher displays. For an existing generic-coloring candidate with both,
use the complete native pair instead of allowing its smaller flat alias to
become the higher-priority generated fancy icon.

Compose source day/night images into the final native viewport before contrast
extraction. Use an entire day pair when no complete night pair exists. Preserve
all source numbered textures byte for byte. Existing palette templates,
unknown/animated programs, preserved resources and custom choices retain their
priority. Incomplete or unrecognized day layer groups are skipped.

This change does not add generic coloring to every layer-only native icon.
Candidates without a flat alias or a supported static day/night template keep
their original resources. The existing 64 MiB derivative budget is unchanged.

Generation schema 12 marks the corrected generic output; schema 11 remains
readable. Old generic output requests one regeneration. The APK monochrome
viewport now shares Android's implementation; its existing source identity
stays valid because the prior OEM formula has the same bounds on this device.

## Cross-surface snapshots

Use declared quiet snapshots before static pairs and pairs before flat aliases
within an appearance. Apply adaptive expansion only to raw static textures.
Keep texture resolution until the final composite rather than shrinking a
texture to the finished icon size and then enlarging it again. Completed masks
and quiet images keep their original geometry.

The shared snapshot renderer feeds notification/island artwork and startup
completion, so this correction is made at their common renderer.

## Regression evidence

- `SurfaceIconSnapshotRendererDeviceTest`: native day/night pairs with
  conflicting flat aliases match framework child drawing; completed palette
  and quiet geometry remain unchanged.
- `PublishedGenericMonetDeviceTest`: completed and partial night pairs,
  framework-equivalent source composition, foreground coverage and unchanged
  raw textures.
- `ColorOsFallbackRendererTest`: APK monochrome alpha matches Android at
  output sizes 60, 64, 95, 96, 129 and 192; separate OEM viewport tests remain.
- JVM update-notice and generation-manifest tests cover legacy/current output.

The framework and direct bitmap paths have a small Skia sampling difference:
the measured native fixtures have identical foreground bounds and coverage,
with at most one corner pixel above a channel error of 12/255 (maximum 18/255).
The assertion retains full-image mean/error bounds as well as independent ink
bounds and coverage checks. Completed-mask position and APK monochrome alpha
checks stay exact.

## ColorOS supplied monochrome boundary

The restored ColorOS inspiration theme exposed a second raw-layer boundary:
`colorOsMonochrome()` decoded the supplied PNG and passed it directly to the
exporter's final-mask scaling. APK monochrome used the adaptive child viewport,
but supplied monochrome bypassed it. This affected authored masks even when
generic Monet coloring was disabled.

The local ColorOS launcher reference confirms the contract:
`BaseIconFactory.ClippedMonoDrawable` extends `InsetDrawable` with
`-AdaptiveIconDrawable.getExtraInsetFraction()` before clipping to the adaptive
mask. The source profile retains the original 240-pixel child texture; it is
not an already completed 240-pixel launcher icon.

In the user's original theme the phone glyph spans 73 of 192 pixels (38%);
the settings glyph spans 92 (48%). After regenerating the current ColorOS
inspiration theme these span 110 (57%) and 136 (71%) respectively.
Expand the raw child viewport exactly once. Supplied PNG and APK monochrome
now enter the same rasterizer.
The exporter, native palette template and cross-surface renderer continue to
consume completed masks with final output coordinates. Colored ColorOS
artwork and authored flat/snapshot resources keep their distinct contracts.

Generation schema 13 marks this supplied-mask correction. Older ColorOS
generation requests one regeneration, independently of generic coloring.
Schema 12 still identifies the preceding generic/native-alias correction.
Changing the runtime alone cannot repair previously exported `mask.png` files.

`ColorOsMonochromeGeometryDeviceTest` compares the five reported system-app
masks against direct Android child drawing in both appearances at 64, 96 and
192 pixels, and checks the exported masks separately. The profile has 69 OEM
masks and 531 runtime-origin fallback masks; all 600 paths are traversed.
Runtime-origin masks can be unavailable or have unusable opaque alpha.
An unusable optional mask is isolated from pack generation,
matching APK monochrome fallback; corruption and cancellation still propagate.
The oracle is the
framework child, rather than a second copy of the production scaling formula.

Execution results and exact APK identity belong to
`.artifacts/research/monet-layer-geometry-20261117/`, separately from the earlier
Perfect MTZ acceptance. The reopened ColorOS visual acceptance and exact
candidate identity belong to
`.artifacts/research/monet-optical-geometry-20261117/`.
