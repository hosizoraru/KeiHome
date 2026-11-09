# Native MTZ day/night and Monet imports

The user-provided `HyperOS Monet Launcher.mtz` exposed two independent size
accounting problems. Its outer `icons` ZIP is 263,547,157 bytes; the inner
resources expand to 277,073,541 bytes. Counting the intermediate ZIP again
against the final-resource budget rejected the legitimate one-level container.
The parser now tracks a separate per-level expansion budget and a shared
final-resource budget. CRC, actual byte counts, entry count, compression ratio,
input size, cancellation and the one-level nesting limit remain enforced.

Native module rewrites preserve each original ZIP entry's compression method.
Unconditionally storing every original PNG expanded this particular module past
the output limit when adding fallback icons. New generated image entries retain
the existing stored-image policy; imported source image bytes remain unchanged.

## Capability and conversion boundary

The source's ordinary icons use two fixed colored PNGs and a closed MAML
day/night crossfade. This is not an existing live wallpaper-palette binding.
`NativeMamlPair` recognizes the complete bounded declarative program, without
executing it. The browser can then preview the actual original light and dark
images independently.

`NativeTwoToneMask` additionally requires both original images to prove the same
foreground/background coverage. The proof checks dimensions, alpha, palette
separation, color residuals, paired coverage and connected icon outlines.
Arbitrary colored images, unknown MAML, remote paths and variable-driven image
selection do not qualify.

Only exact installed targets with a proven pair are converted during export.
Their original PNG bytes become the Monet-OFF day/night resources. Verified
foreground/background masks use the existing `NativePaletteIcon` packed-state
binding when Monet is ON, including wallpaper/custom palette and brightness.
The generated selector requests zero continuous frame rate and replaces the
recognized Folme/crossfade timers. This is an import/export worker operation;
there is no new launcher-frame hook or per-icon polling timer.

The original imported module stays unchanged in private storage. Uninstalled
source entries and unrecognized dynamic programs are copied unchanged. In
particular, the source calendar's date-driven XML and 62 day/night date images
remain authored resources, rather than a flattened screenshot or inferred
palette mask. A source identity revision requests regeneration of previously
generated imports once; it does not require source reimport.

## APK packs

APK resources continue through their existing Android resource-table path.
Explicit adaptive monochrome geometry retains the adaptive background mask and
the existing preview/export/palette binding. `AndroidPaletteIcon` also recognizes
an adaptive source whose solid background and uniform vector foreground
explicitly reference Android system palette colors. It follows the selected
configuration's compiled resource references, with limits of eight levels,
32 resources and 256 XML nodes. It rejects unknown programs, gradients, bitmaps,
mixed foreground colors and unproved resource references. The original colored
drawable is not mutated.

This covers the installed Arcticons You resources, which declare a palette
without a standard `<monochrome>` element. Lawnicons continues through its
explicit monochrome path. Ordinary Arcticons DayNight vectors and Delta PNGs
remain their authored artwork. A pack's name, a tinted vector or a colored
adaptive foreground alone does not establish monochrome capability. The APK
source identity includes a conversion revision so previously generated archives
can request a single regeneration.

## Names, versions and large inventories

The handoff preserves the supplied title, author, designer and version in its
outer description, and adds a bounded `keihome-theme-metadata.txt` inside the
icons module. The labels use strict UTF-8/Base64 serialization, at most 4 KiB.
They are display metadata, not an authenticity or execution approval.
The submitted MTZ declares version `15.0.231111`; it is no longer replaced with
the generic container version in the generated description. Source files without
a version explicitly show “Version not provided” in their imported card.

Active-theme inventory reads metadata before scanning MAML. If descriptor,
XML-node or resource-name budgets are exhausted, it retains a bounded partial
inventory and the known labels, with `complete=false`. It does not increase the
scan limits or silently report complete statistics. An unavailable Themes
identity also no longer erases metadata already read from the actual module.
A confirmed system-default identity cannot inherit a leftover theme's labels.

The status codec reads old seven-, nine- and ten-field reports as well as the
new optional version field. Previously generated themes need one update/apply
to acquire the new embedded labels; their original import files remain reusable.

The gated `InstalledMonetPackDeviceTest` inspects installed package metadata and
actual launcher components, checks light/dark resources and palette handoffs, and
distinguishes declared monochrome from colored-only resources. Lawnicons,
Arcticons You, Arcticons DayNight and Delta are separate capability cases; an
unsupported resource-only appfilter is reported rather than invented.

## Verification

`NativeMamlMonetDeviceTest` accepts the supplied file only through the explicit
`monetMtzPath` instrumentation argument. It tests import, preview, installed-target
export, exact original image bytes, calendar preservation and bounded output.
`InstalledMonetPackDeviceTest` requires `installedMonet=true`; ordinary offline
test runs do not depend on privately installed packs.

Current evidence lives in
`.artifacts/research/monet-mtz-20261109/`. Device acceptance is scoped to the
HyperOS Phone AVD, with original theme and settings restored afterward. The
submitted artwork is not redistributed in the APK.

## Status preview scope

The status worker snapshots resources for the requested user's actual
launcher packages and activity classes, instead of repeatedly copying the
whole applied archive into the app's preview cache. Exact names are matched;
`package` does not select `package.other`. Global configuration, shared helpers
and unknown layouts remain conservative. The selected name set participates
in the cache key so installs, removals and launcher activity changes invalidate
the snapshot. An unavailable launcher list does not fall back to a full copy.

This changes only the private browser snapshot. The applied theme, imported
source and offline coverage remain complete. On the submitted large theme,
the AVD's current snapshot cache was approximately 4 MB after this change.

See [archive library options](archive-library-options.md) for the separate
community-library investigation and bounded raw-entry-copy experiment.
