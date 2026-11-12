# Icon masks and preview parity

Icon packs -> More settings -> Icon mask owns the automatic-completion switch,
custom XML import, restore action and an isolated comparison. The switch remains
on by default. Importing or comparing a mask does not apply a theme.

## Selection and preservation

`IconMaskPolicy` takes one immutable snapshot on a worker. An explicitly imported
custom mask overrides a supported simple native mask configuration. Otherwise
the pack's authored mask wins. A missing simple configuration uses the retained
HyperOS 4 100-unit path, including when the product archive is unavailable.

Legacy bitmap plates, authored filters/matrices, unsupported XML programs and
explicit layer/dynamic flags keep their existing preservation rules. The
completion switch disables additions and custom overrides; it does not remove
the pack's own mask or restore transparency already baked into source images.

`CustomIconMaskStore` uses an app-private atomic record and bounded SAF reads.
Only a direct, unambiguous `IconTransform/Config[name=ConfigIconMask]` path is
imported from `transform_config.xml`; other transformation instructions are not
executed. XML, canonical SVG commands, finite in-viewport geometry and a
nonempty filled region are validated before replacing the previous record.
Invalid files leave the previous mask intact.

## One final compositor

The proposed APK/native archive preview uses the same selected contract as
publication. Full-mask clipping is an alpha intersection, without a white
background, color removal, padding or rescaling. Source density is retained.
Configuration and mask decisions are cached per source and policy, outside the
per-icon draw loop. Import, restore and the master switch invalidate preview
state and generation notices.

Static Perfect Icons dark conversion retains uncropped intermediate images.
The final mask belongs to the preview compositor or the native theme loader;
export must not bake that same edge into a PNG and then apply it a second time.
Static-render cache revision 3 separates older cropped intermediates.
Authored dynamic resources remain native resources.

Current-theme previews read the XML actually published in that theme. Pending
private mask settings are not substituted. Generation manifest schema 4 records
the policy identity, including a canonical custom-path digest. Schemas 1-3
remain readable; changing the effective policy requires regeneration and
reapplication to update the active theme.

## Comparison

The child page samples up to three real entries from a locally available pack.
It compares the pack's own configuration (including no mask), HyperOS 4 and the
imported custom mask, with a light/dark preview option. Comparison overrides are
ephemeral; they neither change the saved choice nor start a download.
Special legacy programs remain preserved and are identified in the explanation.
Intrinsic transparency in a source image cannot be restored by another mask.

## Dynamic resource boundary

Dynamic resources are checked against native layer configuration, root
`config.xml`, declared layer directories, quiet images, fancy manifests and
their referenced resource closure. Legacy root-quiet and valid fancy-only
variants retain their compatibility paths. Screenshot descriptions inform
format research; they are not permission to copy a packer's implementation or
to reject all historical layouts.

Focused test and device receipts are retained under
`.artifacts/research/icon-mask-20261112/`. Device captures validate the Phone AVD;
they do not establish physical-device acceptance.
