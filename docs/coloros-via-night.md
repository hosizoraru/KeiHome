# Via night artwork and ColorOS cache freshness

## Source evidence, 2026-11-03

Only HyperOS `emulator-5580` and the read-only ColorOS `emulator-5574` source
were used. No physical device is included in this investigation.

Both devices have package `mark.via`. HyperOS has Via 7.3.3 and ColorOS has
7.3.4; their `res/` entries and `resources.arsc` are byte-identical. The icon
resource difference therefore does not come from those APK versions.

The source framework and launcher match the previously inspected SHA-256:

- `oplus-framework.jar`: `52c4783bc6027756b56dcb7780d838dd4b36b9726b28f4db2f0a03d49237a0ae`
- `OplusLauncher.apk`: `b67118677ed9f6bc23ecbd22a2f7f4826b4f31c08641347cc58648fa986196fc`

DexSQL decoded the actual framework methods:

- `OplusUXIconLoaderImpl.findAppDrawable` (DEX file 0, method 4903) reads
  `/data/oplus/uxicons/<package>/` first for third-party applications whenever
  ART+ or native dark mode is enabled, then falls through to firmware artwork.
- `RectangleStyle.tryLoadThemeContent` (file 0, method 6013) selects
  `rec_night` in dark mode and composes it over the native top-to-bottom
  `#383838` to `#151515` gradient. The regular night dim filter follows.

The live ColorOS cache contains 16 Via PNGs, including `recfg`, `recbg`,
`rec_night`, monochrome and size variants. The previous local runtime snapshot
and built-in Classic ART+ manifest contain no `mark.via` source mapping.
The previously generated HyperOS theme nevertheless has Via day/night MAML
pairs because original-icon fallback generated them: the sampled white
background becomes `#D6D6D6`, matching the 0.84 night dim, instead of selecting
the ColorOS redraw. A night filename alone does not prove night redraw coverage.

## Correction and scope

`capture-coloros-runtime-icons.py` refreshes the complete inspected runtime
snapshot, with explicit source selection, framework authentication, bounded
PNG admission, provenance and preservation of the previous local snapshot.
The refreshed source contains 905 usable PNGs for 53 packages, adding Via and
Apple Music. No existing usable icon changed. Seven old local files excluded
by the importer's existing filename rules are omitted from the new snapshot.

The existing importer already selects runtime night art independently of ART+
and exports mappings for applications absent from the target install inventory.
The new regression case verifies that newly captured third-party art reaches
the night appfilter and pixels with ART+ both on and off. No Via package special
case, new per-frame work or launcher-native patch is required.

After rebuilding, an already applied static theme still needs the normal
"Update current icon pack" generation and native Themes application. This
updates the resource bytes; package-change callbacks alone cannot replace an
old user-applied theme. The port includes the captured local resources, not
ColorOS's online artwork download service. Future resources newly appearing
on the source device require a fresh source snapshot and regenerated pack.

Device acceptance and its exact release identity are recorded below once
completed. Source evidence is under `.artifacts/research/via-night-20261103/`.
