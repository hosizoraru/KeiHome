# Generic Monet coloring

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

Work runs only during generation, with cancellation checks, bounded PNG
dimensions/input bytes and a 64 MiB supplemental-data memory budget. There is no
per-frame extraction or separate persistent bitmap cache. Additional mask
resources become part of the generated icon pack. Temporary publication files
are removed on success and failure.

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
