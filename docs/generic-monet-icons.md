# Generic Monet coloring

## Optional space around full-bleed artwork

The **Inset full-bleed icons** preference independently defaults to off. It
works without generic coloring. The two switches separately control deriving
new palette artwork and insetting large foregrounds in app-assembled palette
artwork, including APK/inspiration monochrome masks. Either change requires
regenerating and applying the pack.

Both publication paths use the same completed-mask classifier and resampler.
Substantial occupation of opposite edge bands, including broad bottom-heavy
artwork, triggers scaling into a centered canvas about 60% of its original
width/height. The published outer silhouette is intersected by minimum alpha
coverage before insetting, preserving rounded corners without multiplying
already clipped antialiased edges. Centered glyphs and isolated protrusions keep their dimensions.
Original day/night artwork and the outer background keep their original bytes.
This is an optical heuristic, not adaptive child expansion or a package-name
rule; `NativeLayerViewport` is not applied again.

Export provenance and canonical template verification admit existing
app-assembled palette masks. Unknown trees, imported authored programs, custom
and preserved icons remain protected. Processing runs only during generation;
the native palette and snapshot renderers consume the result without added
per-frame work. Schema 15 records the independent `monet-inset` choice; schemas
1-14 default to off. Changing either direction requests regeneration even
when generic coloring is off.

## Default completion

The Monet overview offers **Color unthemed icons**, off by default. Change the
setting, regenerate the chosen icon pack and apply it. Enabling coloring does
not enable Monet or apply a palette. Disabling the setting also requires
regeneration to remove layers from a previously published pack.

The generation manifest records this setting in schema 11. Older manifests
mean off. The existing update notice compares both states with the published
generation. Schema 12 requests regeneration for the native-pair/flat-alias
geometry correction; schema 13 additionally corrects supplied ColorOS
monochrome child textures, independently of this generic coloring switch.

`PublishedGenericMonet` runs after shape/mask composition and before per-app
custom artwork. It adds the existing native, event-driven `NativePaletteIcon`
template to PNG artwork and strictly recognized image-only day/night pairs.
It also accepts pairs without a flat PNG alias. Original day/night images
remain available when Monet is off.

Existing palette templates, unknown MAML programs, animated resources, per-app
customizations and preserved originals win. The pass does not execute arbitrary
source MAML. Flat auxiliary shape resources are excluded.

Foreground extraction uses contrast against the dominant perimeter color,
retains alpha and antialiasing, and handles light marks on dark plates and dark
marks on light plates. Transparent cutouts retain their silhouette. Opaque
plates receive rounded corners and a separate background layer. Uniform
artwork without a recoverable mark is skipped. This is an approximate duotone
treatment, not reconstruction of an artist's vector outline.

When a flat candidate also has a complete native static pair, use the pair's
framework composition as the source, preserving its geometry and raw textures.
Layer-only resources remain original. See [layer geometry](monet-layer-geometry.md)
for viewport boundaries, protected resources and regeneration compatibility.

Generation stages derived PNGs and manifests in a private temporary file.
The writer holds one icon's artwork at a time and copies unchanged ZIP entries
in their original compressed form. The input, expanded output, entry count,
per-image and native-import storage limits still apply. Cancellation and
failure unwind the same staging cleanup as a completed generation.

When a recognized day/night template is replaced, the generated template owns
its complete resource directory. The original source stays intact. Approval
checks each generated directory for its exact expected resources and indexes
those directories once per archive.

The progress dialog uses stage weights and the worker's completed/total
counters. Monet derivation reports icon candidates and writing reports archive
entries. The bar interpolates within each stage and the numeric counter remains
visible. The overall percentage estimates work across different units and does
not predict a completion time.

### Large-pack acceptance

Build b0686 imported and applied four installed APK packs with 19,477 to
49,252 mappings and the HyperOS Monet MTZ with 13,387 mappings on the HyperOS
Phone AVD. File-picker tests also imported and applied the 22,676-mapping
Lawnicons APK and the original native ZIP module extracted from the MTZ.
The ZIP exercised 13,452 Monet candidates. The physical Xiaomi 17 Pro
imported and applied the MTZ with its existing preferences. Both devices
restored their original themes, night mode, palette and custom selections.

The b0684 deployment recorded a reload failure for `com.miui.securitycenter:cache`.
The system event log shows `umms_selfcheck` killing that PID before the failed
LSPosed receipt. The b0686 AVD deployment recorded nine successful reloads
among observed targets. The launcher, SystemUI and system server retained
their process identities during testing.

The Monet overview also contains the inspiration-pack installation card.
Installing the pack, applying colors and enabling Monet remain separate
operations. The obsolete design-only navigation entry is no longer shown.

## KeiHome launch icon

When the published Monet palette is active, the Shell splash integration may
replace KeiHome's own declared launch icon with the current applied icon
snapshot. This uses the existing light/dark/Monet-aware renderer and bounded
snapshot cache; native entrance/exit timing remains in control.

This exception is restricted to KeiHome's own package, requires an enabled
packed palette, and still rejects snapshots, protected windows, branding and
non-opaque artwork. It also works independently of the missing-icon completion
switch. Other apps' declared splash icons retain their existing protection.
If a pack supplies no Monet artwork, coloring it requires the generic option
and regeneration, just like other icons.

## Focused verification

JVM tests cover contrast polarity, transparent alpha, uniform-image skipping,
cancellation, both generation-identity states, and the own-package splash
exception/protection rules.

Android tests in `PublishedGenericMonetDeviceTest` exercise publication,
original day/night retention, disabled no-op, existing palette/dynamic/preserved
protection and fancy-only pairs. The optional real-theme test reads an owned
copy at `files/generic-monet-acceptance/source.zip`, validates the generated
archive and writes a two-palette comparison. It never applies that copy to the
live theme. Remove the owned input/output directory after collecting evidence.

Run instrumentation against the unminified Debug target. Verify UI and splash
behavior against the signed, minified Release separately; do not use the Debug
test APK against the R8 target.
